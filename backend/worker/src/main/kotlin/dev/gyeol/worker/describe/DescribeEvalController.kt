package dev.gyeol.worker.describe

import dev.gyeol.core.knowledge.DescriptorRepository
import dev.gyeol.worker.WorkerProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

data class DescribeEvalRequest(val trackIds: List<Long>, val rag: Boolean, val model: String?, val judgeModel: String?)
data class TrackEval(val trackId: Long, val title: String, val artist: String, val chunkCount: Int, val summary: String?, val fields: Map<String, Field>, val verdicts: Map<String, String>)
data class DescribeEvalResponse(val tracks: List<TrackEval>, val describeTokens: List<Int>, val judgeTokens: List<Int>, val describeMs: Int)

/**
 * 곡 설명 정확도 측정 (tools/describe-eval). 저장하지 않는다.
 * RAG 켬/끔으로 설명을 만들고, 다른 모델(심판)이 같은 근거 청크를 보고 칸마다 뒷받침되는지 판정한다.
 * 로컬에서만 켠다 (gyeol.worker.llm.debug-endpoint=true). 호출마다 Bedrock 비용이 든다.
 */
@RestController
@ConditionalOnProperty("gyeol.worker.llm.debug-endpoint", havingValue = "true")
class DescribeEvalController(
    private val descriptors: DescriptorRepository,
    private val describer: TrackDescriber,
    private val props: WorkerProperties,
    private val json: JsonMapper,
) {
    private val tool by lazy { BedrockTool(props.llm.region, json) }

    @PostMapping("/internal/describe/eval")
    fun eval(@RequestBody req: DescribeEvalRequest): DescribeEvalResponse {
        val targets = req.trackIds.mapNotNull { descriptors.target(it) }.filter { it.chunks.isNotEmpty() }
        val d = describer.describe(targets, req.rag, req.model)
        val built = targets.map { t -> t to DescriptorBuilder.build(t, d.items.firstOrNull { it.trackId == t.trackId } ?: RawDescription(t.trackId, emptyMap(), Claim(null, emptyList())), req.rag) }
        // 심판: 근거 청크만 보고 칸마다 SUPPORTED / UNSUPPORTED (값이 없으면 판정하지 않음)
        val user = built.mapIndexed { i, (t, b) ->
            val claims = (b.second.filterKeys { it != "era" }.mapValues { it.value.value } + ("summary" to b.first)).filterValues { it != null }
            "## 곡 $i: ${t.artist} - ${t.title}\n근거 자료:\n" + t.chunks.joinToString("\n") { "- ${it.text}" } + "\n주장: " + json.writeValueAsString(claims)
        }.joinToString("\n\n")
        val j = tool.call(req.judgeModel ?: "global.anthropic.claude-sonnet-4-6", JUDGE, user, "record_verdicts", JUDGE_SCHEMA, 4000)
        val verdicts = j.input.path("tracks").toList().associate { n ->
            n.path("index").asInt() to n.path("verdicts").properties().associate { (k, v) -> k to v.asString() }
        }
        return DescribeEvalResponse(
            built.mapIndexed { i, (t, b) -> TrackEval(t.trackId, t.title, t.artist, t.chunks.size, b.first, b.second, verdicts[i].orEmpty()) },
            listOf(d.inputTokens, d.outputTokens), listOf(j.inputTokens, j.outputTokens), d.latencyMs,
        )
    }

    companion object {
        private val JUDGE = """
            너는 음악 정보 검수자다. 곡마다 근거 자료와 주장(칸별 값)이 주어진다.
            주장의 각 칸이 **근거 자료에 적힌 내용으로 뒷받침되는지**만 판정한다. 네가 따로 아는 지식은 쓰지 않는다.
            - SUPPORTED: 근거 자료에 그 내용이 있거나 바로 도출된다 (가수 단위 자료를 곡에 적용한 경우도 자료가 그 곡의 특징으로 볼 수 있으면 포함)
            - UNSUPPORTED: 근거 자료에 없거나 어긋난다
            주장에 있는 칸만 판정한다.
        """.trimIndent()
        private val verdict = mapOf("type" to "string", "enum" to listOf("SUPPORTED", "UNSUPPORTED"))
        private val JUDGE_SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object", "required" to listOf("tracks"),
            "properties" to mapOf(
                "tracks" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object", "required" to listOf("index", "verdicts"),
                        "properties" to mapOf(
                            "index" to mapOf("type" to "integer"),
                            "verdicts" to mapOf("type" to "object", "properties" to listOf("genres", "moods", "tempo", "energy", "vocal", "summary").associateWith { verdict }),
                        ),
                    ),
                ),
            ),
        )
    }
}
