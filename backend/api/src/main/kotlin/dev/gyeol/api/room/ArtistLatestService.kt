package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.search.TrackItem
import dev.gyeol.core.identify.ArtistReleaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * I-30 ③ 가수 화면 최신순 (V27). 처음 열 때(또는 끝난 지 7일 지나면) worker에 발매일 모으기를 맡기고,
 * 모으는 동안(RUNNING)에는 그때까지 저장된 곡만 보여 준다. 날짜 없는 곡은 맨 뒤, 판 표시 곡(Live·Inst.·Remix)은 숨긴다(결정 A7)
 */
@Service
class ArtistLatestService(
    private val releases: ArtistReleaseRepository,
    private val sender: AfterCommitSender,
    private val json: JsonMapper,
    private val props: GyeolProperties,
) {
    /** (곡과 발매일, 모으기 상태 RUNNING·DONE·FAILED, 다음 쪽 있음) */
    @Transactional
    fun page(artistId: Long, artistName: String, page: Int, size: Int): Triple<List<Pair<TrackItem, String?>>, String, Boolean> {
        if (releases.requestScan(artistId, Instant.now().minus(props.artist.scanRefresh))) {
            sender.send(props.room.songQueue, json.writeValueAsString(mapOf("type" to "SCAN_ARTIST_RELEASES", "artistId" to artistId)))
        }
        val status = when (releases.scan(artistId)?.status) {
            "DONE" -> "DONE"
            "FAILED" -> "FAILED"
            else -> "RUNNING" // QUEUED·RUNNING
        }
        val rows = releases.latest(artistId, withVersions = false, limit = size + 1, offset = (page - 1) * size)
        val items = rows.take(size).map { r ->
            TrackItem(
                r.title, artistName, r.recordingMbid.toString(), null, r.firstReleaseDate?.take(4)?.toIntOrNull(),
                r.trackId, r.coverUrl, artistId,
            ) to r.firstReleaseDate
        }
        return Triple(items, status, rows.size > size)
    }
}
