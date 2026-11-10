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

    override fun medleySongs(items: List<RawTitle>, model: String?): MedleyResult {
        val modelId = model ?: props.model
        val lines = items.joinToString("\n") { "${it.index}\t${it.title}\t${it.channel.orEmpty()}" }
        val req = ConverseRequest.builder()
            .modelId(modelId)
            .system(SystemContentBlock.fromText(MEDLEY_SYSTEM))
            .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText("번호\t제목\t채널\n$lines")).build())
            .toolConfig(
                ToolConfiguration.builder()
                    .tools(Tool.fromToolSpec(ToolSpecification.builder().name(MEDLEY_TOOL).description("번호마다 메들리에 든 곡을 기록한다").inputSchema(ToolInputSchema.fromJson(MEDLEY_SCHEMA)).build()))
                    .toolChoice(ToolChoice.fromTool(SpecificToolChoice.builder().name(MEDLEY_TOOL).build()))
                    .build(),
            )
            .inferenceConfig { it.maxTokens(props.maxTokens).temperature(0f) }
            .build()
        val res = client.converse(req)
        val input = res.output().message().content().firstNotNullOfOrNull { it.toolUse()?.input() }
            ?: error("도구 호출 응답이 없습니다 (stop=${res.stopReasonAsString()})")
        fun str(d: Document?) = d?.takeIf { !it.isNull }?.asString()?.trim()?.ifEmpty { null }
        val out = input.asMap()["items"]?.asList().orEmpty().mapNotNull { d ->
            val m = d.asMap()
            val index = m["index"]?.asNumber()?.toInt() ?: return@mapNotNull null
            val songs = m["songs"]?.takeIf { !it.isNull }?.asList().orEmpty().mapNotNull { s -> s.asMap().let { sm -> str(sm["title"])?.let { str(sm["artist"]) to it } } }
            MedleyItem(index, songs)
        }
        val usage = res.usage()
        return MedleyResult(out, modelId, usage.inputTokens(), usage.outputTokens(), res.metrics().latencyMs().toInt())
    }

    override fun spellings(items: List<RawTitle>, model: String?): SpellResult {
        val modelId = model ?: props.model
        val lines = items.joinToString("\n") { "${it.index}\t${it.title}\t${it.channel.orEmpty()}" }
        val req = ConverseRequest.builder()
            .modelId(modelId)
            .system(SystemContentBlock.fromText(SPELL_SYSTEM))
            .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText("번호\t곡명\t가수\n$lines")).build())
            .toolConfig(
                ToolConfiguration.builder()
                    .tools(Tool.fromToolSpec(ToolSpecification.builder().name(SPELL_TOOL).description("번호마다 다른 표기 후보를 기록한다").inputSchema(ToolInputSchema.fromJson(SPELL_SCHEMA)).build()))
                    .toolChoice(ToolChoice.fromTool(SpecificToolChoice.builder().name(SPELL_TOOL).build()))
                    .build(),
            )
            .inferenceConfig { it.maxTokens(props.maxTokens).temperature(0f) }
            .build()
        val res = client.converse(req)
        val input = res.output().message().content().firstNotNullOfOrNull { it.toolUse()?.input() }
            ?: error("도구 호출 응답이 없습니다 (stop=${res.stopReasonAsString()})")
        fun names(d: Document?) = d?.takeIf { !it.isNull }?.asList().orEmpty().mapNotNull { n -> n.takeIf { !it.isNull }?.asString()?.trim()?.ifEmpty { null } }.take(2)
        val out = input.asMap()["items"]?.asList().orEmpty().mapNotNull { d ->
            val m = d.asMap()
            m["index"]?.asNumber()?.toInt()?.let { Spelling(it, names(m["artists"]), names(m["titles"])) }
        }
        val usage = res.usage()
        return SpellResult(out, modelId, usage.inputTokens(), usage.outputTokens(), res.metrics().latencyMs().toInt())
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

        private const val MEDLEY_TOOL = "record_medley"

        private val MEDLEY_SYSTEM = """
            너는 유튜브 메들리·무대 영상의 제목에서 그 영상에 든 곡들을 뽑는다. 입력은 "번호<TAB>제목<TAB>채널" 줄들이다.
            - 제목에 곡명이 실제로 적혀 있는 곡만 순서대로 적는다. 적혀 있지 않은 곡은 지어내지 않는다.
            - title: 곡명만 (따옴표·장식 제외). artist: 그 곡을 부른 가수가 제목이나 채널에 적혀 있으면 그 표기, 없으면 null.
            - 곡명이 하나도 적혀 있지 않으면 songs는 빈 배열.
            - 모든 번호에 대해 하나씩 기록한다.
        """.trimIndent()

        private val MEDLEY_SCHEMA: Document = Document.fromMap(
            mapOf(
                "type" to Document.fromString("object"),
                "required" to Document.fromList(listOf(Document.fromString("items"))),
                "properties" to Document.fromMap(
                    mapOf(
                        "items" to Document.fromMap(
                            mapOf(
                                "type" to Document.fromString("array"),
                                "items" to Document.fromMap(
                                    mapOf(
                                        "type" to Document.fromString("object"),
                                        "required" to Document.fromList(listOf("index", "songs").map(Document::fromString)),
                                        "properties" to Document.fromMap(
                                            mapOf(
                                                "index" to Document.fromMap(mapOf("type" to Document.fromString("integer"))),
                                                "songs" to Document.fromMap(
                                                    mapOf(
                                                        "type" to Document.fromString("array"),
                                                        "items" to Document.fromMap(
                                                            mapOf(
                                                                "type" to Document.fromString("object"),
                                                                "required" to Document.fromList(listOf(Document.fromString("title"))),
                                                                "properties" to Document.fromMap(mapOf("title" to Document.fromMap(mapOf("type" to Document.fromString("string"))), "artist" to nullableString())),
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
                ),
            ),
        )

        private const val SPELL_TOOL = "record_spellings"

        private val SPELL_SYSTEM = """
            너는 음악 데이터베이스 MusicBrainz 검색을 돕는다. 입력은 "번호<TAB>곡명<TAB>가수" 줄들이고, 이 표기로는 MusicBrainz에서 곡을 찾지 못했다.
            MusicBrainz에 등록되어 있을 만한 다른 표기를 제안한다.
            - 로마자로 적힌 한국 가수 이름은 한글로 (Seo InGuk → 서인국, CherryFilter → 체리필터).
            - 로마자로 적힌 일본어 곡명은 원어로 (Kimi Wa Rock Wo Kikanai → 君はロックを聴かない).
            - 영어로 적혔지만 원제가 다른 언어인 곡명은 원제로 (TIME CAPSULE → 타임캡슐).
            - 그 가수의 그 곡을 실제로 알고 있을 때만 쓴다. 모르면 빈 배열로 둔다. 추측으로 지어내지 않는다.
            - artists와 titles는 각각 최대 2개. 입력과 같은 표기는 넣지 않는다.
            - 모든 번호에 대해 하나씩 기록한다.
        """.trimIndent()

        private fun stringArray() = Document.fromMap(mapOf("type" to Document.fromString("array"), "items" to Document.fromMap(mapOf("type" to Document.fromString("string")))))

        private val SPELL_SCHEMA: Document = Document.fromMap(
            mapOf(
                "type" to Document.fromString("object"),
                "required" to Document.fromList(listOf(Document.fromString("items"))),
                "properties" to Document.fromMap(
                    mapOf(
                        "items" to Document.fromMap(
                            mapOf(
                                "type" to Document.fromString("array"),
                                "items" to Document.fromMap(
                                    mapOf(
                                        "type" to Document.fromString("object"),
                                        "required" to Document.fromList(listOf("index", "artists", "titles").map(Document::fromString)),
                                        "properties" to Document.fromMap(
                                            mapOf(
                                                "index" to Document.fromMap(mapOf("type" to Document.fromString("integer"))),
                                                "artists" to stringArray(),
                                                "titles" to stringArray(),
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
