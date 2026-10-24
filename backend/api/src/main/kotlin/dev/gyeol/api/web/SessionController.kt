package dev.gyeol.api.web

import dev.gyeol.api.session.SeedInput
import dev.gyeol.api.session.SessionService
import dev.gyeol.api.session.UploadCounts
import dev.gyeol.api.session.UploadTicket
import dev.gyeol.api.stats.Stats
import dev.gyeol.api.stats.StatsService
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.Session
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CreateSessionRequest(val entry: String?)

data class CreateSessionResponse(
    val token: String,
    val entry: Entry,
    val status: String,
    val resultUrl: String,
    val expiresAt: Instant,
)

data class SeedsRequest(val tracks: List<SeedInput>?)
data class SeedsResponse(val status: String, val count: Int)

data class Counts(val total: Int?, val music: Int?, val unclassified: Int?, val saved: Int?)
data class PeriodDates(val start: LocalDate?, val end: LocalDate?)
data class SeedView(val mbid: String, val title: String, val artist: String)

data class SessionView(
    val entry: Entry,
    val status: String,
    val counts: Counts,
    val period: PeriodDates?,
    val seeds: List<SeedView>?,
    val error: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

@RestController
@RequestMapping("/api/sessions")
class SessionController(
    private val service: SessionService,
    private val stats: StatsService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: CreateSessionRequest): CreateSessionResponse {
        val entry = Entry.entries.firstOrNull { it.name == body.entry }
            ?: throw ApiException.validation("entry는 SEEDS 또는 TAKEOUT이어야 합니다")
        val created = service.create(entry)
        return CreateSessionResponse(
            token = created.token,
            entry = created.session.entry,
            status = created.session.status.name,
            resultUrl = "/r/${created.token}",
            expiresAt = created.session.expiresAt,
        )
    }

    @GetMapping("/{token}")
    fun get(@PathVariable token: String): SessionView = view(service.find(token))

    @PostMapping("/{token}/seeds")
    fun seeds(@PathVariable token: String, @RequestBody body: SeedsRequest): SeedsResponse =
        SeedsResponse("DONE", service.saveSeeds(token, body.tracks))

    @PostMapping("/{token}/upload-url")
    fun uploadUrl(@PathVariable token: String, @RequestBody body: UploadCounts): UploadTicket =
        service.issueUploadUrl(token, body)

    @GetMapping("/{token}/stats")
    fun stats(@PathVariable token: String): Stats = stats.of(service.find(token))

    @DeleteMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable token: String) = service.delete(token)

    private fun view(s: Session): SessionView {
        val zone = ZoneId.of("Asia/Seoul")
        val period = if (s.periodStart != null || s.periodEnd != null) {
            PeriodDates(s.periodStart?.atZone(zone)?.toLocalDate(), s.periodEnd?.atZone(zone)?.toLocalDate())
        } else {
            null
        }
        return SessionView(
            entry = s.entry,
            status = s.status.name,
            counts = Counts(s.totalCount, s.musicCount, s.unclassifiedCount, s.savedCount),
            period = period,
            seeds = service.seedsOf(s)?.map { SeedView(it.mbid.toString(), it.title, it.artist) },
            error = s.errorMessage,
            createdAt = s.createdAt,
            expiresAt = s.expiresAt,
        )
    }
}
