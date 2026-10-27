package dev.gyeol.worker.recommend

import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.BedrockTool
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

data class RecommendEvalRequest(val sessionId: Long, val models: List<String>, val judgeModel: String?)
data class PickEval(val rank: Int, val title: String, val artist: String, val score: Double, val scoreRank: Int, val reason: String?, val evidence: List<String>, val verdict: String?)
data class ModelEval(
    val model: String, val raw: Int, val accepted: Int, val withReason: Int, val supported: Int, val distinctArtists: Int,
    val tokens: List<Int>, val latencyMs: Int, val summary: String?, val picks: List<PickEval>,
)

/**
 * 추천 재정렬 모델 비교 (tools/recommend-eval). 저장하지 않는다.
 * 같은 후보로 모델마다 재정렬 → 검증 → 심판 모델이 이유마다 인용한 근거로 뒷받침되는지 판정.
 * 로컬에서만 켠다 (gyeol.worker.llm.debug-endpoint=true). 호출마다 Bedrock 비용이 든다.
 */
@RestController
@ConditionalOnProperty("gyeol.worker.llm.debug-endpoint", havingValue = "true")
class RecommendEvalController(private val service: RecommendService, private val props: WorkerProperties, private val json: JsonMapper) {
    private val bedrock by lazy { BedrockReranker(props, json) }
    private val tool by lazy { BedrockTool(props.llm.region, json) }

    @PostMapping("/internal/recommend/eval")
    fun eval(@RequestBody req: RecommendEvalRequest): List<ModelEval> {
        val input = service.rankInput(req.sessionId)
        val chunkText = input.candidates.flatMap { it.chunks }.toMap()
        return req.models.map { model ->
            val r = if (model == "fake") FakeReranker().rerank(input.facets, input.seeds, input.candidates, props.recommend.picks)
            else bedrock.rerank(input.facets, input.seeds, input.candidates, props.recommend.picks, model)
            val finals = RerankValidator.validate(r, input.seeds, input.candidates, props.recommend.picks)
            val withReason = finals.filter { it.reason != null }
            val verdicts = if (withReason.isEmpty()) emptyMap() else judge(req.judgeModel ?: "global.anthropic.claude-sonnet-4-6", withReason, chunkText)
            ModelEval(
                model, r.picks.size, finals.count { f -> r.picks.any { it.candidate == f.candidate.index } }, withReason.size,
                verdicts.values.count { it == "SUPPORTED" }, finals.map { it.candidate.artist }.distinct().size,
                listOf(r.inputTokens, r.outputTokens), r.latencyMs, r.tasteSummary,
                finals.mapIndexed { i, f ->
                    PickEval(i + 1, f.candidate.title, f.candidate.artist, f.candidate.score, f.candidate.index, f.reason,
                        f.evidence.mapNotNull { chunkText[it]?.take(80) }, verdicts[f.candidate.index])
                },
            )
        }
    }

    private fun judge(model: String, picks: List<RerankValidator.Final>, chunks: Map<Long, String>): Map<Int, String> {
        val user = picks.joinToString("\n\n") { f ->
            "## 추천 ${f.candidate.index}: ${f.candidate.artist} - ${f.candidate.title}\n근거 자료:\n" +
                f.evidence.joinToString("\n") { "- ${chunks[it]}" } + "\n이유: ${f.reason}"
        }
        // 심판 호출이 503을 자주 받아 재정렬 결과를 버리지 않도록 여기서 다시 시도한다 (30·60초)
        val r = (0..2).firstNotNullOfOrNull { i ->
            runCatching { tool.call(model, JUDGE, user, "record_verdicts", SCHEMA, 3000) }.onFailure { if (i < 2) Thread.sleep(30_000L * (i + 1)) }.getOrNull()
        } ?: return emptyMap()
        return r.input.path("items").toList().associate { it.path("index").asInt() to it.path("verdict").asString() }
    }

    companion object {
        private val JUDGE = """
            너는 음악 추천 검수자다. 추천마다 근거 자료와 추천 이유가 주어진다.
            이유에 적힌 곡·가수에 대한 사실이 **근거 자료로 뒷받침되는지**만 판정한다. 네가 따로 아는 지식은 쓰지 않는다.
            "사용자가 들은 곡과 결이 맞다" 같은 비교 표현은 판정 대상이 아니고, 그 바탕이 되는 사실(장르·분위기·작곡가 등)만 본다.
            - SUPPORTED: 사실이 모두 근거 자료에 있다
            - UNSUPPORTED: 근거 자료에 없는 사실이 하나라도 있다
        """.trimIndent()
        private val SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object", "required" to listOf("items"),
            "properties" to mapOf(
                "items" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object", "required" to listOf("index", "verdict"),
                        "properties" to mapOf("index" to mapOf("type" to "integer"), "verdict" to mapOf("type" to "string", "enum" to listOf("SUPPORTED", "UNSUPPORTED"))),
                    ),
                ),
            ),
        )
    }
}
