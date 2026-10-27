package dev.gyeol.worker.recommend

import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.BedrockTool
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/** 재정렬에 보내는 후보 하나 (번호 i, 곡 설명, 근거 청크) */
data class RerankCandidate(val index: Int, val trackId: Long, val title: String, val artist: String, val description: String?, val chunks: List<Pair<Long, String>>, val score: Double)
/** 들은 곡 하나 (번호 i) */
data class RerankSeed(val index: Int, val trackId: Long, val title: String, val artist: String)

data class Pick(val candidate: Int, val reason: String?, val evidence: List<Long>, val basedOn: List<Int>)
data class RerankResult(val picks: List<Pick>, val tasteSummary: String?, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

/** LLM 재정렬과 이유 (L3-4). 구현: 가짜(점수 순 + 정형 문장), Bedrock(Haiku 4.5 또는 Sonnet 4.6) */
interface Reranker {
    fun rerank(facets: String, seeds: List<RerankSeed>, candidates: List<RerankCandidate>, picks: Int, model: String? = null): RerankResult
}

/**
 * LLM 답 검증: 준 후보 번호만, 같은 후보는 한 번만, 근거 청크는 그 후보에게 준 것만, 들은 곡 번호는 준 것만.
 * 모자라면 점수 순으로 채우고 이유는 비운다(화면에서 "근거 부족").
 */
object RerankValidator {
    data class Final(val candidate: RerankCandidate, val reason: String?, val evidence: List<Long>, val basedOn: List<Long>)

    fun validate(result: RerankResult, seeds: List<RerankSeed>, candidates: List<RerankCandidate>, picks: Int): List<Final> {
        val byIndex = candidates.associateBy { it.index }
        val seedById = seeds.associateBy { it.index }
        val used = HashSet<Int>()
        val out = result.picks.mapNotNull { p ->
            val c = byIndex[p.candidate] ?: return@mapNotNull null
            if (!used.add(p.candidate)) return@mapNotNull null
            val allowed = c.chunks.map { it.first }.toSet()
            val ev = p.evidence.filter { it in allowed }.distinct()
            // 근거 청크를 하나도 못 대면 이유를 쓰지 않는다
            Final(c, p.reason?.takeIf { ev.isNotEmpty() && it.isNotBlank() }, ev, p.basedOn.mapNotNull { seedById[it]?.trackId }.distinct())
        }.take(picks).toMutableList()
        candidates.sortedByDescending { it.score }.filter { it.index !in used }.take(picks - out.size).forEach { out += Final(it, null, emptyList(), emptyList()) }
        return out
    }
}

/** 가짜 재정렬 (LLM_MODE=fake): 점수 순, 첫 근거 청크로 정형 문장 */
class FakeReranker : Reranker {
    override fun rerank(facets: String, seeds: List<RerankSeed>, candidates: List<RerankCandidate>, picks: Int, model: String?) = RerankResult(
        candidates.sortedByDescending { it.score }.take(picks).map { c ->
            Pick(c.index, c.chunks.firstOrNull()?.let { "${c.artist}의 곡으로, 들은 곡과 결이 비슷합니다." }, listOfNotNull(c.chunks.firstOrNull()?.first), listOfNotNull(seeds.firstOrNull()?.index))
        },
        tasteSummary = null, model = "fake", inputTokens = 0, outputTokens = 0, latencyMs = 0,
    )
}

class BedrockReranker(private val props: WorkerProperties, json: JsonMapper) : Reranker {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tool = BedrockTool(props.llm.region, json)

    override fun rerank(facets: String, seeds: List<RerankSeed>, candidates: List<RerankCandidate>, picks: Int, model: String?): RerankResult {
        val modelId = model ?: props.recommend.model.ifBlank { props.llm.model }
        val user = buildString {
            appendLine("## 사용자 취향"); appendLine(facets)
            appendLine("## 많이 들은 곡 (번호)"); seeds.forEach { appendLine("s${it.index}. ${it.artist} - ${it.title}") }
            appendLine("## 후보 곡 (번호, 곡 설명, 근거 자료)")
            candidates.forEach { c ->
                appendLine("c${c.index}. ${c.artist} - ${c.title}: ${c.description ?: "(설명 없음)"}")
                c.chunks.forEach { (id, text) -> appendLine("   [${id}] ${text.take(200)}") }
            }
        }
        val r = tool.call(modelId, SYSTEM.replace("{N}", picks.toString()), user, "record_recommendations", schema(), 6000)
        val input = r.input
        val list = input.path("picks").toList().map { p ->
            Pick(
                p.path("candidate").asInt(-1), p.path("reason").asString("").ifBlank { null },
                p.path("evidence").toList().mapNotNull { it.asLong(-1).takeIf { x -> x > 0 } },
                p.path("basedOn").toList().map { it.asInt(-1) },
            )
        }
        log.info("추천 재정렬: 후보 {}곡 → {}곡, 모델 {}, 토큰 {}/{}, {}ms", candidates.size, list.size, modelId, r.inputTokens, r.outputTokens, r.latencyMs)
        return RerankResult(list, input.path("tasteSummary").asString("").ifBlank { null }, r.model, r.inputTokens, r.outputTokens, r.latencyMs)
    }

    private fun schema(): Map<String, Any?> = mapOf(
        "type" to "object", "required" to listOf("picks", "tasteSummary"),
        "properties" to mapOf(
            "tasteSummary" to mapOf("type" to "string"),
            "picks" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object", "required" to listOf("candidate", "reason", "evidence"),
                    "properties" to mapOf(
                        "candidate" to mapOf("type" to "integer"),
                        "reason" to mapOf("type" to "string"),
                        "evidence" to mapOf("type" to "array", "items" to mapOf("type" to "integer")),
                        "basedOn" to mapOf("type" to "array", "items" to mapOf("type" to "integer")),
                    ),
                ),
            ),
        ),
    )

    companion object {
        private val SYSTEM = """
            너는 음악 추천 큐레이터다. 사용자 취향과 많이 들은 곡, 후보 곡(곡 설명과 [번호]가 붙은 근거 자료)이 주어진다.
            후보 중 사용자에게 가장 잘 맞는 {N}곡을 골라 잘 맞는 순서로 기록한다.
            - candidate: 후보 번호 (c 뒤의 숫자). 같은 곡을 두 번 고르지 않는다
            - reason: 한국어 1~2문장. 이 곡의 어떤 점이 사용자가 들은 곡과 결이 맞는지. **근거 자료에 적힌 내용만** 쓰고 지어내지 않는다. 가사 내용은 언급하지 않는다
            - evidence: reason의 근거로 쓴 자료 번호 (그 후보 아래에 있는 [번호]만)
            - basedOn: 이 추천의 바탕이 된 많이 들은 곡 번호 (s 뒤의 숫자, 1~2개)
            - 가수가 겹치지 않게 다양하게 고른다
            - tasteSummary: 사용자 취향을 한국어 2문장으로 요약
        """.trimIndent()
    }
}
