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
data class JobState(val name: String, val running: Boolean, val progress: String, val startedAt: Instant, val endedAt: Instant? = null, val error: String? = null)

/**
 * 음악 지도 데이터를 만드는 내부 작업 (로컬·운영자만, gyeol.worker.map.admin-endpoint=true일 때만 켠다).
 * 오래 걸리므로 한 번에 하나만 뒤에서 돌리고 GET /internal/map/job으로 진행을 본다.
 * describe·name-galaxies는 LLM_MODE=bedrock이면 비용이 든다.
 */
@RestController
@ConditionalOnProperty("gyeol.worker.map.admin-endpoint", havingValue = "true")
class MapAdminController(private val service: MapService) {
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
