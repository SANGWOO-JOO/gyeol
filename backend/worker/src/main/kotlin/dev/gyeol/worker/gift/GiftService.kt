package dev.gyeol.worker.gift

import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.GiftStatus
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.BedrockTool
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

data class GiftMessage(val boxId: Long)

/** 캐릭터 묘사 문장 (영어, 이미지 모델 입력). 가수 이름은 넣지 않고 구역 이름(장르·분위기)만 쓴다 */
interface GiftPrompter {
    fun describe(genreMix: List<Pair<String, Int>>): Prompt
    data class Prompt(val text: String, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)
}

/** 그림 한 장 (png 바이트) */
interface GiftImageGenerator {
    fun generate(prompt: String, seed: Long): Image
    data class Image(val bytes: ByteArray, val contentType: String, val model: String, val latencyMs: Int)
}

/** 결 화풍 고정 문구. 사람 얼굴·글자·로고·기존 캐릭터를 피한다 (room-plan.md ⑯) */
object GiftStyle {
    const val PREFIX = "A cute original mascot character, round soft blob body, flat pastel colors, simple dot eyes, gentle smile, " +
        "plain light background, centered, children's picture book style, no outlines clutter."
    const val NEGATIVE = "human, person, realistic face, celebrity, text, letters, logo, watermark, brand, existing cartoon character, " +
        "photo, 3d render, scary, weapon"

    fun fullPrompt(subject: String) = "$PREFIX $subject".take(1800)
}

class FakeGiftPrompter : GiftPrompter {
    override fun describe(genreMix: List<Pair<String, Int>>) =
        GiftPrompter.Prompt("Mood: " + genreMix.joinToString(", ") { "${it.first} ${it.second}" }.ifEmpty { "calm" }, "fake", 0, 0, 0)
}

/** 가짜 생성: 기본 캐릭터 그림을 그대로 돌려준다 (로컬·테스트에서 S3 저장 경로까지 확인) */
class FakeGiftImageGenerator : GiftImageGenerator {
    override fun generate(prompt: String, seed: Long): GiftImageGenerator.Image {
        val n = (seed % GiftRepository.PRESETS).toInt().let { if (it < 0) it + GiftRepository.PRESETS else it } + 1
        val bytes = javaClass.getResourceAsStream("/gift/preset-$n.svg")!!.use { it.readBytes() }
        return GiftImageGenerator.Image(bytes, "image/svg+xml", "fake", 0)
    }
}

class BedrockGiftPrompter(private val llm: WorkerProperties.Llm, json: JsonMapper) : GiftPrompter {
    private val tool = BedrockTool(llm.region, json)

    override fun describe(genreMix: List<Pair<String, Int>>): GiftPrompter.Prompt {
        val total = genreMix.sumOf { it.second }.coerceAtLeast(1)
        val user = "이 사람이 담은 노래의 장르·분위기 비율:\n" +
            genreMix.joinToString("\n") { "- ${it.first}: ${it.second * 100 / total}%" }.ifEmpty { "- (정보 없음)" }
        val r = tool.call(llm.model, SYSTEM, user, "record_character", SCHEMA, 300)
        val text = r.input.path("description").asString("").trim().take(400).ifEmpty { "a calm pastel character holding a small music note" }
        return GiftPrompter.Prompt(text, r.model, r.inputTokens, r.outputTokens, r.latencyMs)
    }

    companion object {
        private val SYSTEM = """
            You design a cute original mascot for a music app. Given genre and mood ratios, describe ONE character in English,
            under 60 words: body color palette, a small prop, an expression and a light accessory that reflect the music.
            Never mention real people, artists, bands, songs, countries, brands, logos, text, or existing characters.
            The body is always a round soft blob; do not describe a human face.
        """.trimIndent()
        private val SCHEMA = mapOf(
            "type" to "object", "required" to listOf("description"),
            "properties" to mapOf("description" to mapOf("type" to "string")),
        )
    }
}

/**
 * Stability Stable Image Core (Bedrock, us-west-2). 요청: prompt·negative_prompt·aspect_ratio·output_format·seed, 응답: images[0] base64.
 * 11-13 기준 실제 호출로 확인하지 않음 (Marketplace 구독 확인 후 따로 확인)
 */
class BedrockGiftImageGenerator(private val props: WorkerProperties.GiftProps, private val json: JsonMapper) : GiftImageGenerator {
    private val client = BedrockRuntimeClient.builder().region(Region.of(props.imageRegion)).build()

    override fun generate(prompt: String, seed: Long): GiftImageGenerator.Image {
        val started = System.nanoTime()
        val body = json.writeValueAsString(
            mapOf(
                "prompt" to GiftStyle.fullPrompt(prompt), "negative_prompt" to GiftStyle.NEGATIVE,
                "aspect_ratio" to "1:1", "output_format" to "png", "seed" to (seed and 0xFFFFFFFL),
            ),
        )
        val res = client.invokeModel { it.modelId(props.imageModel).contentType("application/json").accept("application/json").body(SdkBytes.fromUtf8String(body)) }
        val tree = json.readTree(res.body().asUtf8String())
        val b64 = tree.path("images").path(0).asString("").ifEmpty { error("이미지 응답이 비어 있습니다: ${tree.path("finish_reasons")}") }
        return GiftImageGenerator.Image(Base64.getDecoder().decode(b64), "image/png", props.imageModel, ((System.nanoTime() - started) / 1_000_000).toInt())
    }
}

@Configuration
class GiftConfig {
    @Bean
    fun giftPrompter(props: WorkerProperties, json: JsonMapper): GiftPrompter =
        if (props.llm.mode == "bedrock") BedrockGiftPrompter(props.llm, json) else FakeGiftPrompter()

    @Bean
    fun giftImageGenerator(props: WorkerProperties, json: JsonMapper): GiftImageGenerator =
        if (props.llm.mode == "bedrock") BedrockGiftImageGenerator(props.gift, json) else FakeGiftImageGenerator()
}

/**
 * 상자 열기 (gift-image 큐): 구역 비율 → 묘사 문장 → 그림 → S3(covers 버킷 characters/) → 캐릭터 저장·상자 OPENED.
 * 서비스 전체 하루 예산을 넘었거나 생성·저장이 실패하면 기본 캐릭터(preset:N)로 연다. 기본 캐릭터 저장마저 실패하면 예외 → 3번 뒤 실패 큐 → FAILED
 */
@Service
class GiftService(
    private val gifts: GiftRepository,
    private val prompter: GiftPrompter,
    private val images: GiftImageGenerator,
    private val llmCalls: LlmCallRepository,
    private val s3: S3Client,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")

    fun open(boxId: Long) {
        val box = gifts.box(boxId) ?: return
        if (box.status != GiftStatus.GENERATING) return // 이미 열렸거나 메시지를 두 번 받은 경우
        val today = LocalDate.now(kst)
        val made = runCatching { generate(box.memberId, boxId, today) }
            .onFailure { log.warn("선물 그림 실패, 기본 캐릭터로: 상자 {} {}", boxId, it.toString()) }
            .getOrNull()
        val characterId = tx.execute {
            if (made != null) gifts.finishOpening(boxId, made.key, made.prompt, made.model, false)
            else gifts.finishOpening(boxId, GiftRepository.presetKey(boxId.toInt()), null, "preset", true)
        }
        log.info("선물 상자 {} 열림: 캐릭터 {} ({})", boxId, characterId, if (made != null) made.model else "기본")
    }

    private data class Made(val key: String, val prompt: String, val model: String)

    /** 예산 안이면 그림을 만들어 S3에 올린다. 예산을 넘으면 null (기본 캐릭터) */
    private fun generate(memberId: Long, boxId: Long, today: LocalDate): Made? {
        if (gifts.imagesToday(today) >= props.gift.dailyImageLimit) {
            log.info("선물 그림 하루 예산({}장) 초과: 상자 {} 기본 캐릭터", props.gift.dailyImageLimit, boxId)
            return null
        }
        val p = prompter.describe(gifts.genreMix(memberId))
        if (p.model != "fake") llmCalls.record("GIFT_PROMPT", LlmUsage(p.model, 1, p.inputTokens, p.outputTokens, p.latencyMs))
        val img = images.generate(p.text, boxId)
        // 실패한 호출도 예산에 세지 않도록 성공한 그림만 기록한다 (가짜는 세지 않음)
        if (img.model != "fake") llmCalls.record("GIFT_IMAGE", LlmUsage(img.model, 1, 0, 0, img.latencyMs))
        val ext = if (img.contentType == "image/svg+xml") "svg" else "png"
        val key = "characters/$memberId/$boxId.$ext"
        s3.putObject({ it.bucket(props.cover.bucket).key(key).contentType(img.contentType) }, RequestBody.fromBytes(img.bytes))
        return Made(key, p.text, img.model)
    }

    /** 실패 큐: 상자를 FAILED로 (화면이 "만드는 중"에서 멈추지 않게) */
    fun fail(boxId: Long) {
        gifts.markFailed(boxId)
        log.warn("선물 상자 {} 실패 큐 → FAILED", boxId)
    }
}

/** gift-image 큐 (WORKER_ROLE에 room이 있을 때만) */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('room')")
class GiftListener(private val service: GiftService, private val json: JsonMapper) {
    @SqsListener("\${gyeol.worker.gift.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String) = service.open(json.readValue(body, GiftMessage::class.java).boxId)

    @SqsListener("\${gyeol.worker.gift.dlq}", maxConcurrentMessages = "1", maxMessagesPerPoll = "1")
    fun onDeadLetter(body: String) = service.fail(json.readValue(body, GiftMessage::class.java).boxId)
}
