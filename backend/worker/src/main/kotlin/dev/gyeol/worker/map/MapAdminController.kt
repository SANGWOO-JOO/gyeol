package dev.gyeol.worker.map

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

data class SeedRequest(val countries: List<SeedCountry>, val perCountry: Int)
data class LimitRequest(val limit: Int = 2000)
data class HaeyoRequest(val apply: Boolean = false)
data class RedescribeRequest(val artistIds: List<Long> = emptyList(), val maxCalls: Int = 0)
private const val REDESCRIBE_MAX = 30
data class JobState(val name: String, val running: Boolean, val progress: String, val startedAt: Instant, val endedAt: Instant? = null, val error: String? = null)

/**
 * 음악 지도 데이터를 만드는 내부 작업 (로컬·운영자만, gyeol.worker.map.admin-endpoint=true일 때만 켠다).
 * 오래 걸리므로 한 번에 하나만 뒤에서 돌리고 GET /internal/map/job으로 진행을 본다.
 * describe·name-galaxies는 LLM_MODE=bedrock이면 비용이 든다.
 */
@RestController
@ConditionalOnProperty("gyeol.worker.map.admin-endpoint", havingValue = "true")
class MapAdminController(
    private val service: MapService,
    private val map: dev.gyeol.core.map.MapRepository,
    private val props: dev.gyeol.worker.WorkerProperties,
    private val json: tools.jackson.databind.json.JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor()
    private val state = AtomicReference<JobState?>(null)

    private fun start(name: String, job: ((String) -> Unit) -> Any): ResponseEntity<JobState> {
        val now = JobState(name, true, "시작", Instant.now())
        val prev = state.get()
        if (prev?.running == true || !state.compareAndSet(prev, now)) return ResponseEntity.status(HttpStatus.CONFLICT).body(state.get())
        executor.submit {
            try {
                val r = job { p -> state.updateAndGet { it?.copy(progress = p) } }
                state.updateAndGet { it?.copy(running = false, progress = "끝: $r", endedAt = Instant.now()) }
            } catch (e: Exception) {
                log.error("지도 작업 실패: {}", name, e)
                state.updateAndGet { it?.copy(running = false, error = e.message, endedAt = Instant.now()) }
            }
        }
        return ResponseEntity.accepted().body(now)
    }

    @PostMapping("/internal/map/seed")
    fun seed(@RequestBody req: SeedRequest) = start("seed") { p -> service.seed(req.countries, req.perCountry, p) }

    @PostMapping("/internal/map/describe")
    fun describe(@RequestBody req: LimitRequest) = start("describe") { p -> service.describeAll(req.limit, p) }

    /**
     * 11-14 일회성: 지정한 가수(최대 30명)만 Bedrock으로 설명을 다시 만든다. LLM_MODE가 fake여도 이 요청만 Bedrock을 쓴다(비용 발생).
     * 다른 가수로 번지지 않는다(일괄 describeAll을 쓰지 않음). 호출 상한 maxCalls(최대 30)
     */
    @PostMapping("/internal/map/redescribe")
    fun redescribe(@RequestBody req: RedescribeRequest): ResponseEntity<JobState> {
        require(req.artistIds.isNotEmpty() && req.artistIds.size <= REDESCRIBE_MAX) { "artistIds는 1~${REDESCRIBE_MAX}명이어야 합니다" }
        require(req.maxCalls in 1..REDESCRIBE_MAX) { "maxCalls는 1~${REDESCRIBE_MAX}이어야 합니다" }
        val bedrock = BedrockMapLlm(props.llm, json)
        return start("redescribe") { service.redescribe(req.artistIds, bedrock, req.maxCalls) }
    }

    /**
     * 11-14 일회성: Bedrock 설명 문체를 해요체로 ([HaeyoStyle], 무료). apply=false면 바뀔 수와 못 바꾼 문장만 돌려준다.
     * described_at을 바꾸지 않아 벡터는 그대로다. 실행 전에 원문을 따로 백업할 것
     */
    @PostMapping("/internal/map/haeyo")
    fun haeyo(@RequestBody req: HaeyoRequest): Map<String, Any> {
        val rows = map.bedrockDescriptions()
        var sentences = 0; var converted = 0; var changedRows = 0
        val unchanged = mutableListOf<Map<String, Any>>()
        val samples = mutableListOf<Map<String, Any>>()
        for ((id, text) in rows) {
            val r = HaeyoStyle.convert(text)
            sentences += r.sentences; converted += r.converted
            r.unchanged.forEach { unchanged += mapOf("artistId" to id, "sentence" to it) }
            if (r.text != text) {
                changedRows++
                if (samples.size < 2000) samples += mapOf("artistId" to id, "before" to text, "after" to r.text)
                if (req.apply) map.replaceDescriptionText(id, r.text)
            }
        }
        log.info("해요체 변환(apply={}): 설명 {}개, 문장 {}개 중 {}개 변환, 못 바꾼 문장 {}개, 바뀐 설명 {}개", req.apply, rows.size, sentences, converted, unchanged.size, changedRows)
        return mapOf("descriptions" to rows.size, "sentences" to sentences, "converted" to converted, "changedRows" to changedRows, "unchanged" to unchanged, "samples" to samples, "applied" to req.apply)
    }

    @PostMapping("/internal/map/clean-descriptions")
    fun cleanDescriptions() = start("clean-descriptions") { service.cleanDescriptions() }

    @PostMapping("/internal/map/embed")
    fun embed() = start("embed") { p -> service.embedAll(p) }

    @PostMapping("/internal/map/name-galaxies")
    fun nameGalaxies() = start("name-galaxies") { p -> service.nameGalaxies(p) }

    @PostMapping("/internal/map/prepare/{artistId}")
    fun prepare(@PathVariable artistId: Long) = start("prepare") { service.prepare(artistId) }

    @GetMapping("/internal/map/job")
    fun job(): JobState? = state.get()
}
