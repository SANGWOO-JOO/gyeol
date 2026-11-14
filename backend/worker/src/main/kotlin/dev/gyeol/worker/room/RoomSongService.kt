package dev.gyeol.worker.room

import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.Target
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.core.listen.Source
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.RoomSongRepository
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.enrich.EnrichService
import dev.gyeol.worker.identify.IdentifyService
import dev.gyeol.worker.identify.retrySoonIfBusy
import dev.gyeol.worker.map.MapService
import dev.gyeol.core.map.MapRepository
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.listener.Visibility
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId

/**
 * IDENTIFY: 곡 요청 하나 식별 → 방에 담기. ENRICH: 담은 곡의 근거·곡 설명·벡터 (뒤에서, 지도 표시와 무관).
 * PREPARE_ARTIST: 지도에 없는 가수를 올린다 (4-g 준비 중). SCAN_ARTIST_RELEASES: 가수 화면 최신순용 녹음 발매일 모으기 (I-30 ③)
 */
data class RoomSongMessage(val type: String, val requestId: Long? = null, val trackIds: List<Long> = emptyList(), val artistId: Long? = null)

/**
 * 음악 방 담기 (docs/todo/room-api-db.md 4절). 식별 규칙은 세션 묶음과 같다(IdentifyService.identifyTargets).
 * 식별되면 바로 room_song에 넣어 위성을 띄우고(측정상 2~6초), 곡 설명·벡터는 따로 보낸다.
 * 찾지 못하면 요청을 FAILED로 두고 하루 한도에서 뺀다.
 */
@Service
class RoomSongService(
    private val identify: IdentifyService,
    private val enrich: EnrichService,
    private val songs: RoomSongRepository,
    private val rooms: RoomRepository,
    private val tx: TransactionTemplate,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
    private val props: WorkerProperties,
    private val map: MapService,
    private val maps: MapRepository,
    private val gifts: GiftRepository,
    private val releaseScanner: dev.gyeol.worker.artist.ArtistReleaseScanner,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")

    fun handle(m: RoomSongMessage) {
        when (m.type) {
            "IDENTIFY" -> identifyRequest(m.requestId ?: return)
            "ENRICH" -> if (m.trackIds.isNotEmpty()) enrich.enrichTracks(m.trackIds)
            "PREPARE_ARTIST" -> m.artistId?.let(map::prepare)
            // I-30 ③ 가수 화면 최신순: 녹음 발매일 모으기
            "SCAN_ARTIST_RELEASES" -> m.artistId?.let(releaseScanner::scan)
            // I-31 방에 바로 담은 DB 곡의 표지 확인 (확인한 적 없는 곡만 cover 큐로)
            "COVER" -> if (m.trackIds.isNotEmpty()) identify.requestCovers(m.trackIds)
        }
    }

    fun identifyRequest(requestId: Long) {
        val started = System.nanoTime()
        val req = songs.request(requestId) ?: return
        if (req.status != "IDENTIFYING") return // 이미 끝난 요청 (메시지를 두 번 받은 경우)
        // 고른 곡과 같은 키("가수 - Topic" 채널)를 써서 전역 식별 캐시를 함께 쓴다
        val channel = "${req.artist} - Topic"
        val target = Target(0, AliasKey.of(req.title, channel), req.title, channel, Source.YOUTUBE, 1, TargetStatus.PENDING)
        var result: Long? = null
        var rejected = false
        identify.identifyTargets(listOf(target), object : IdentifyService.TargetSink {
            override fun finish(t: Target, trackId: Long?) { result = trackId }
            override fun fail(t: Target) { rejected = true }
        })
        val trackId = result
        if (trackId == null) {
            fail(requestId, if (rejected) "REJECTED" else "NOT_FOUND")
            log.info("방 담기: 요청 {} 찾지 못함, {}ms", requestId, (System.nanoTime() - started) / 1_000_000)
            return
        }
        val added = tx.execute {
            val r = songs.request(requestId)!!
            if (r.status != "IDENTIFYING") return@execute false
            rooms.lock(r.roomId)
            val member = rooms.member(r.memberId)
            if (member == null || member.removedAt != null) {
                // 그사이 내보내진 사람: 담지 않는다
                songs.finishRequest(requestId, "FAILED", trackId, null, "MEMBER_REMOVED"); return@execute false
            }
            val artistId = songs.trackArtist(trackId)!!
            // 이미 담은 곡이면 새로 넣지 않고 그 곡을 돌려준다 (하루 한도에서도 뺀다)
            val songId = songs.add(r.roomId, r.memberId, trackId, artistId)
            if (songId == null) {
                songs.decrementDaily(r.memberId, r.createdDay())
                songs.finishRequest(requestId, "DONE", trackId, existingSongId(r.memberId, trackId), "ALREADY_ADDED")
            } else {
                rooms.bumpMapRev(r.roomId)
                songs.finishRequest(requestId, "DONE", trackId, songId, null)
                // 50곡 단위 상자 (API의 바로 담기와 같은 규칙, 방 행 잠금 안).
                // I-51: 요청에 상자를 남겨 요청 결과 응답(gift)으로 화면이 WAITING 알림을 띄울 수 있게 한다
                gifts.onSongAdded(r.memberId, java.time.LocalDate.now(kst))?.let { songs.setRequestGift(requestId, it.id, it.status.name) }
            }
            true
        } ?: false
        if (added) {
            identify.requestCovers(listOf(trackId))
            songs.trackArtist(trackId)?.let(::requestPrepareIfNeeded)
            sqs.send { it.queue(props.room.songQueue).payload(json.writeValueAsString(RoomSongMessage("ENRICH", trackIds = listOf(trackId)))) }
        }
        log.info("방 담기: 요청 {} → 곡 {}, {}ms", requestId, trackId, (System.nanoTime() - started) / 1_000_000)
    }

    /** 활성 지도 판에 자리가 없는 가수면 준비를 요청한다 (한 번만) */
    fun requestPrepareIfNeeded(artistId: Long) {
        val version = maps.activeVersion()
        if (version != null && maps.positions(version, listOf(artistId)).isNotEmpty()) return
        if (maps.requestPrepare(artistId)) {
            sqs.send { it.queue(props.room.songQueue).payload(json.writeValueAsString(RoomSongMessage("PREPARE_ARTIST", artistId = artistId))) }
        }
    }

    private fun dev.gyeol.core.room.SongRequest.createdDay() = songs.requestDay(id).atZone(kst).toLocalDate()

    private fun existingSongId(memberId: Long, trackId: Long): Long? =
        songs.mySongs(memberId, null, 10_000).firstOrNull { it.trackId == trackId }?.id

    /** 찾지 못했거나 실패 큐로 간 요청: FAILED + 하루 한도에서 뺀다 */
    fun fail(requestId: Long, reason: String) {
        tx.executeWithoutResult {
            val r = songs.request(requestId) ?: return@executeWithoutResult
            if (songs.finishRequest(requestId, "FAILED", null, null, reason)) songs.decrementDaily(r.memberId, r.createdDay())
        }
    }
}

/** room-song 큐 (WORKER_ROLE에 room이 있을 때만). 3번 실패하면 실패 큐 → 요청을 FAILED로 (화면이 "찾는 중"에서 멈추지 않게) */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('room')")
class RoomSongListener(private val service: RoomSongService, private val json: JsonMapper, private val props: WorkerProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${gyeol.worker.room.song-queue}", maxConcurrentMessages = "4", maxMessagesPerPoll = "4")
    fun onMessage(body: String, visibility: Visibility) {
        retrySoonIfBusy(visibility, props.busyRetry.toSeconds().toInt()) { service.handle(json.readValue(body, RoomSongMessage::class.java)) }
    }

    @SqsListener("\${gyeol.worker.room.song-dlq}")
    fun onDeadLetter(body: String) {
        val m = json.readValue(body, RoomSongMessage::class.java)
        if (m.type == "IDENTIFY" && m.requestId != null) {
            service.fail(m.requestId, "ERROR")
            log.error("room-song 실패 큐: 요청 {}를 FAILED로 표시", m.requestId)
        }
    }
}
