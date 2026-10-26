package dev.gyeol.worker.llm

import dev.gyeol.worker.WorkerProperties
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.SpecificToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.Tool
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification

/**
 * Bedrock Converse API로 제목을 묶어 정리한다 (gyeol.worker.llm.mode=bedrock).
 * Bedrock Claude는 structured outputs를 지원하지 않아 도구 호출(tool use) 스키마로 받고, 개수·칸을 직접 검증한다.
 * 형식이 틀리면 예외 → 큐가 다시 시도한다.
 */
class BedrockTitleCleaner(private val props: WorkerProperties.Llm) : TitleCleaner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: BedrockRuntimeClient = BedrockRuntimeClient.builder().region(Region.of(props.region)).build()

    override fun clean(items: List<RawTitle>, model: String?): CleanResult {
        val modelId = model ?: props.model
        val lines = items.joinToString("\n") { "${it.index}\t${it.title}\t${it.channel.orEmpty()}" }
        val req = ConverseRequest.builder()
            .modelId(modelId)
            .system(SystemContentBlock.fromText(SYSTEM))
            .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText("번호\t제목\t채널\n$lines")).build())
            .toolConfig(
                ToolConfiguration.builder()
                    .tools(Tool.fromToolSpec(ToolSpecification.builder().name(TOOL).description("정리한 곡 정보를 번호마다 하나씩 기록한다").inputSchema(ToolInputSchema.fromJson(SCHEMA)).build()))
                    .toolChoice(ToolChoice.fromTool(SpecificToolChoice.builder().name(TOOL).build()))
                    .build(),
            )
            .inferenceConfig { it.maxTokens(props.maxTokens).temperature(0f) }
            .build()

        val res = client.converse(req)
        val input = res.output().message().content().firstNotNullOfOrNull { it.toolUse()?.input() }
            ?: error("도구 호출 응답이 없습니다 (stop=${res.stopReasonAsString()})")
        val songs = input.asMap()["songs"]?.asList() ?: error("songs 칸이 없습니다")
        val parsed = songs.mapNotNull { parse(it) }
        val byIndex = parsed.associateBy { it.index }
        // 빠진 번호는 판정하지 못한 것으로 (개수가 크게 모자라면 형식 오류로 보고 다시 시도)
        if (byIndex.size < items.size / 2) error("응답 개수가 모자랍니다: ${byIndex.size}/${items.size}")
        val result = items.map { byIndex[it.index] ?: CleanedTitle(it.index, null, null, null, null, Version.NOT_SONG) }
        val usage = res.usage()
        log.info("LLM 제목 정리: {}건, 모델 {}, 토큰 {}/{}, {}ms", items.size, modelId, usage.inputTokens(), usage.outputTokens(), res.metrics().latencyMs())
        return CleanResult(result, modelId, usage.inputTokens(), usage.outputTokens(), res.metrics().latencyMs().toInt())
    }

    private fun parse(d: Document): CleanedTitle? {
        val m = d.asMap()
        fun str(k: String) = m[k]?.takeIf { !it.isNull }?.asString()?.trim()?.ifEmpty { null }
        val index = m["index"]?.asNumber()?.toInt() ?: return null
        val version = runCatching { Version.valueOf(str("version") ?: "NOT_SONG") }.getOrDefault(Version.NOT_SONG)
        return CleanedTitle(index, str("artist"), str("artistAlt"), str("title"), str("titleAlt"), version)
    }

    companion object {
        private const val TOOL = "record_songs"

        private val SYSTEM = """
            너는 유튜브 동영상의 제목과 채널 이름에서 곡 정보를 뽑는다. 입력은 "번호<TAB>제목<TAB>채널" 줄들이다.
            규칙:
            - 제목과 채널에 실제로 적힌 표기만 쓴다. 적혀 있지 않은 가수·곡명은 지어내지 말고 null로 둔다.
            - title: 곡명만. [MV], Official, M/V, Lyrics, 가사, 4K, Live, 직캠 같은 장식과 가수 이름은 뺀다.
            - titleAlt: 제목에 다른 언어로 함께 적힌 곡명 (예: "Through the Night(밤편지)" → title "밤편지", titleAlt "Through the Night"). 없으면 null.
            - artist: 부른 가수나 그룹. 제목에 없으면 채널 이름에서 (예: "IU Official" → "IU"). 유통사·방송사 채널(1theK, Stone Music, M2, KBS 등)은 가수가 아니다.
            - artistAlt: 제목에 다른 언어로 함께 적힌 가수 이름 (예: "IU(아이유)" → artist "아이유", artistAlt "IU"). 없으면 null.
            - version: ORIGINAL(원곡 음원·MV), LIVE(라이브·직캠·콘서트·음악방송), REMIX, COVER(다른 사람이 부른 커버), INSTRUMENTAL(MR·반주·inst), MEDLEY(여러 곡 메들리), NOT_SONG(플레이리스트·모음·리액션·예능·뉴스 등 곡 하나가 아님).
            - 모든 번호에 대해 하나씩 기록한다.
        """.trimIndent()

        private val SCHEMA: Document = Document.fromMap(
            mapOf(
                "type" to Document.fromString("object"),
                "required" to Document.fromList(listOf(Document.fromString("songs"))),
                "properties" to Document.fromMap(
                    mapOf(
                        "songs" to Document.fromMap(
                            mapOf(
                                "type" to Document.fromString("array"),
                                "items" to Document.fromMap(
                                    mapOf(
                                        "type" to Document.fromString("object"),
                                        "required" to Document.fromList(listOf("index", "version").map(Document::fromString)),
                                        "properties" to Document.fromMap(
                                            mapOf(
                                                "index" to Document.fromMap(mapOf("type" to Document.fromString("integer"))),
                                                "artist" to nullableString(),
                                                "artistAlt" to nullableString(),
                                                "title" to nullableString(),
                                                "titleAlt" to nullableString(),
                                                "version" to Document.fromMap(
                                                    mapOf(
                                                        "type" to Document.fromString("string"),
                                                        "enum" to Document.fromList(Version.entries.map { Document.fromString(it.name) }),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        private fun nullableString() = Document.fromMap(mapOf("type" to Document.fromList(listOf(Document.fromString("string"), Document.fromString("null")))))
    }
}
