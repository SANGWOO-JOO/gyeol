package dev.gyeol.worker.describe

import dev.gyeol.core.knowledge.DescribeTarget
import dev.gyeol.worker.WorkerProperties
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/**
 * 가짜 곡 설명 (LLM_MODE=fake). 청크의 장르·태그를 그대로 옮기고 그 청크를 근거로 단다. 분위기·템포는 비운다.
 * 비용 없이 파이프라인을 시험하는 용도라 정확도 측정에는 쓰지 않는다.
 */
class FakeDescriber : TrackDescriber {
    private val tagLine = Regex("""(?:장르·태그|Last\.fm 태그:)\s*([^;.]+)""")

    override fun describe(targets: List<DescribeTarget>, rag: Boolean, model: String?) = DescribeResult(
        targets.map { t ->
            val hit = if (rag) t.chunks.firstNotNullOfOrNull { c -> tagLine.find(c.text)?.let { c.id to it.groupValues[1] } } else null
            val genres = hit?.second?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3).orEmpty()
            val ev = listOfNotNull(hit?.first)
            RawDescription(
                t.trackId,
                mapOf("genres" to Claim(genres, ev), "moods" to Claim(null, emptyList()), "tempo" to Claim(null, emptyList()), "energy" to Claim(null, emptyList()), "vocal" to Claim(null, emptyList())),
                Claim(if (genres.isEmpty()) null else "${t.artist}의 ${genres.first()} 곡", ev),
            )
        },
        model = "fake", inputTokens = 0, outputTokens = 0, latencyMs = 0,
    )
}

/** Bedrock 곡 설명 (LLM_MODE=bedrock). 5곡씩, 청크 번호로 인용하게 한다 */
class BedrockDescriber(private val props: WorkerProperties.Llm, json: JsonMapper) : TrackDescriber {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tool = BedrockTool(props.region, json)

    override fun describe(targets: List<DescribeTarget>, rag: Boolean, model: String?): DescribeResult {
        val modelId = model ?: props.model
        val user = targets.mapIndexed { i, t ->
            val head = "## 곡 $i: ${t.artist} - ${t.title}${t.releaseDate?.let { " (발매 ${it.take(4)})" } ?: ""}"
            if (!rag) head else head + "\n" + t.chunks.joinToString("\n") { c -> "[${c.id}] (${label(c.entityType.name)}, ${c.source}, ${c.lang}) ${c.text}" }
        }.joinToString("\n\n")
        val r = tool.call(modelId, if (rag) SYSTEM_RAG else SYSTEM_NO_RAG, user, "record_descriptions", SCHEMA, props.maxTokens)
        log.info("곡 설명: {}곡, RAG {}, 모델 {}, 토큰 {}/{}, {}ms", targets.size, rag, modelId, r.inputTokens, r.outputTokens, r.latencyMs)
        return DescribeResult(DescriptorBuilder.parse(r.input, targets), r.model, r.inputTokens, r.outputTokens, r.latencyMs)
    }

    private fun label(t: String) = when (t) { "TRACK" -> "곡"; "WORK" -> "곡(작품)"; "RELEASE_GROUP" -> "음반"; else -> "가수" }

    companion object {
        private val COMMON = """
            각 곡의 음악적 특징을 한국어로 짧게 적는다. 칸:
            - genres: 장르 1~3개 (예: 발라드, 포크, 댄스 팝, 인디 록, 시티팝)
            - moods: 분위기 1~3개 (예: 잔잔함, 그리움, 신남, 몽환적, 강렬함)
            - tempo: SLOW | MID | FAST | UNKNOWN
            - energy: LOW | MID | HIGH | UNKNOWN
            - vocal: 보컬 형태와 특징 한 구절 (예: "여성 솔로, 담담한 음색", "남성 그룹", "연주곡")
            - summary: 곡을 소개하는 한국어 1~2문장
            가사 내용은 다루지 않는다.
        """.trimIndent()

        private val SYSTEM_RAG = COMMON + """

            곡마다 [번호]가 붙은 근거 자료가 주어진다. 근거 자료에 실제로 적힌 내용만 쓰고, 칸마다 근거로 쓴 자료 번호를 evidence에 적는다.
            근거 자료로 뒷받침할 수 없는 칸은 비워 둔다 (배열은 [], 값은 UNKNOWN 또는 null). 음반·가수에 대한 자료를 곡에 적용할 때는 무리하게 일반화하지 않는다.
        """.trimIndent()

        private val SYSTEM_NO_RAG = COMMON + """

            근거 자료 없이 알고 있는 지식으로 답한다. evidence는 비워 둔다. 모르면 비워 둔다.
        """.trimIndent()

        private val arr = mapOf("type" to "array", "items" to mapOf("type" to "string"))
        private val ids = mapOf("type" to "array", "items" to mapOf("type" to "integer"))
        private val SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object", "required" to listOf("tracks"),
            "properties" to mapOf(
                "tracks" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object", "required" to listOf("index", "genres", "moods", "tempo", "energy", "evidence"),
                        "properties" to mapOf(
                            "index" to mapOf("type" to "integer"),
                            "genres" to arr, "moods" to arr,
                            "tempo" to mapOf("type" to "string", "enum" to listOf("SLOW", "MID", "FAST", "UNKNOWN")),
                            "energy" to mapOf("type" to "string", "enum" to listOf("LOW", "MID", "HIGH", "UNKNOWN")),
                            "vocal" to mapOf("type" to listOf("string", "null")),
                            "summary" to mapOf("type" to listOf("string", "null")),
                            "evidence" to mapOf(
                                "type" to "object",
                                "properties" to mapOf("genres" to ids, "moods" to ids, "tempo" to ids, "energy" to ids, "vocal" to ids, "summary" to ids),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
