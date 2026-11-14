package dev.gyeol.worker.room

import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.ArtistImageTarget
import dev.gyeol.core.identify.ArtistPrepareRepository
import dev.gyeol.core.identify.Target
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.core.listen.Source
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.artistimage.ArtistImageMessage
import dev.gyeol.worker.identify.FetcherClient
import dev.gyeol.worker.identify.IdentifyService
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * 검색 때 가수 미리 준비 (11-14, B안). 방 검색 가수 카드가 우리 DB에 없는 가수(n: 키)거나 얼굴이 NONE(id: 키)이면 api가 맡긴다.
 * - n: Last.fm 인기곡 → find-artist(이름·별칭 + 곡 제목 검증, 하나로 줄지 않으면 포기) → 인기곡 3곡을 방 담기와 같은 키로 식별(LLM 없이)
 *      → 확정한 가수의 곡만 표지 큐로 → 가수 사진(Commons만)
 * - id: 식별 없이, 표지를 확인한 적 없는 그 가수 곡 3곡을 표지 큐로, 사진을 다시 볼 때가 됐으면 사진 큐로
 * 공용 지도(PREPARE_ARTIST)에는 올리지 않는다. 다음 검색 때 카드 얼굴은 PHOTO 또는 DB 저장 표지 COVER가 된다.
 */
@Service
class ArtistPrepareService(
    private val repo: ArtistPrepareRepository,
    private val fetcher: FetcherClient,
    private val identify: IdentifyService,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
    private val props: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun prepare(key: String, name: String?, artistId: Long?) {
        if (!repo.start(key)) return // 이미 맡았거나 끝남 (같은 메시지를 두 번 받은 경우)
        val started = System.nanoTime()
        val (status, aid, reason) = try {
            if (artistId != null) known(artistId) else unknown(name.orEmpty())
        } catch (e: RuntimeException) {
            log.warn("가수 미리 준비 실패: {} {}", key, e.message)
            Triple("FAILED", artistId, e.javaClass.simpleName)
        }
        repo.finish(key, status, aid, reason)
        log.info("가수 미리 준비: {} → {} (가수 {}, {}), {}ms", key, status, aid, reason ?: "-", (System.nanoTime() - started) / 1_000_000)
    }

    private fun known(artistId: Long): Triple<String, Long?, String?> {
        val covers = repo.uncheckedCoverTracks(artistId, SONGS)
        if (covers.isNotEmpty()) identify.requestCovers(covers)
        val photo = repo.needsPhoto(artistId, props.artistImage.retry.toDays())
        if (photo) repo.artistMbid(artistId)?.let { photoQueue(artistId, it) }
        return Triple("DONE", artistId, if (covers.isEmpty() && !photo) "NOTHING_TO_DO" else null)
    }

    private fun unknown(name: String): Triple<String, Long?, String?> {
        if (name.isBlank()) return Triple("SKIPPED", null, "NO_NAME")
        val top = fetcher.lastfm("top-tracks", mapOf("artist" to name, "limit" to TOP))
            .path("items").toList().mapNotNull { it.path("title").asString("").ifBlank { null } }.distinct()
        if (top.isEmpty()) return Triple("SKIPPED", null, "NO_TOP_TRACKS")
        val mbid = fetcher.findArtist(name, top.take(TITLES))?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Triple("SKIPPED", null, "AMBIGUOUS") // "Rain"처럼 이름 같은 가수가 여럿이면 준비하지 않는다
        val channel = "$name - Topic" // 방 담기와 같은 키: 검색 줄(TrackMerger)이 이 키로 우리 곡에 이어진다
        val found = mutableListOf<Long>()
        for ((i, title) in top.withIndex()) {
            if (found.size >= SONGS) break
            if (i > 0) Thread.sleep(props.artistImage.bulkPause.toMillis()) // MusicBrainz 줄을 방 담기 식별에 양보
            var trackId: Long? = null
            val target = Target(0, AliasKey.of(title, channel), title, channel, Source.YOUTUBE, 1, TargetStatus.PENDING)
            try {
                identify.identifyTargets(listOf(target), object : IdentifyService.TargetSink {
                    override fun finish(t: Target, trackId: Long?) { if (trackId != null) setTrack(trackId) }
                    override fun fail(t: Target) {}
                    private fun setTrack(id: Long) { trackId = id }
                }, allowLlm = false)
            } catch (e: HttpClientErrorException) {
                log.warn("가수 미리 준비 곡 식별 거절: {} {}", title, e.statusCode.value())
            }
            // 동명이인 곡이 섞이지 않게: 확정한 가수의 곡만 쓴다
            if (trackId != null && repo.trackArtistMbid(trackId) == mbid && trackId !in found) found += trackId
        }
        if (found.isEmpty()) return Triple("SKIPPED", null, "NO_TRACKS")
        identify.requestCovers(found)
        val artistId = repo.trackArtistId(found.first())!!
        if (repo.needsPhoto(artistId, props.artistImage.retry.toDays())) photoQueue(artistId, mbid)
        return Triple("DONE", artistId, null)
    }

    private fun photoQueue(artistId: Long, mbid: UUID) {
        sqs.send { it.queue(props.artistImage.queue).payload(json.writeValueAsString(ArtistImageMessage(items = listOf(ArtistImageTarget(artistId, mbid.toString(), null)), commonsOnly = true))) }
    }

    companion object {
        /** 대표곡 수 */
        const val SONGS = 3
        /** Last.fm 인기곡에서 볼 곡 수 (식별 실패·다른 가수 곡을 건너뛸 여유) */
        const val TOP = 6
        /** find-artist 검증에 쓰는 제목 수 (fetcher 상한 3) */
        const val TITLES = 3
    }
}
