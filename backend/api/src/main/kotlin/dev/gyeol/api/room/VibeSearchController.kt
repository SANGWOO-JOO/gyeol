package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.identify.ArtistImageRepository
import dev.gyeol.core.map.MapRepository
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.VibePick
import dev.gyeol.core.room.VibeQuery
import dev.gyeol.core.room.VibeSearchRepository
import dev.gyeol.core.room.VibeSearchRow
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

data class VibeSearchRequest(val q: String?)

/** imageUrl: Commons 사진이 있으면 방 열쇠로 받는 우리 주소, 없으면 null. inRoomBy: 이 방에서 그 가수 노래를 담은 사람 */
data class VibePickView(val artistId: Long, val name: String, val imageUrl: String?, val reason: String?, val inRoomBy: List<String>)

/**
 * status: PENDING · DONE · FAILED. DONE이면 picks 3~5개, 가까운 가수가 없으면 picks가 비고 notFound=true (D8).
 * 이유가 검증에서 빠지면 reason은 null이고 카드만 보인다
 */
data class VibeSearchView(
    val id: Long, val query: String, val status: String, val createdAt: Instant,
    val picks: List<VibePickView>, val evidence: List<EvidenceView>, val notFound: Boolean,
)

/**
 * 분위기로 가수 찾기 · Naive RAG (docs/todo/vibe-search-rag.md 4-2). 요청만 저장하고 검색·생성은 worker(vibe-search 큐).
 * 같은 방·같은 질문(정규화 해시)·같은 지도 판이면 저장 결과를 다시 쓴다 (하루 횟수에 안 셈)
 */
@Service
class VibeSearchService(
    private val rooms: RoomRepository,
    private val searches: VibeSearchRepository,
    private val maps: MapRepository,
    private val images: ArtistImageRepository,
    private val faces: ArtistFaces,
    private val sender: AfterCommitSender,
    private val json: JsonMapper,
    private val props: GyeolProperties,
) {
    private val p get() = props.room.vibeSearch

    @Transactional
    fun search(ctx: RoomContext, body: VibeSearchRequest): Pair<HttpStatus, Any> {
        val q = body.q?.trim().orEmpty().replace(Regex("\\s+"), " ")
        val len = VibeQuery.length(q)
        if (len < p.minLength || len > p.maxLength) {
            throw ApiException(HttpStatus.BAD_REQUEST, "QUERY_LENGTH", "${p.minLength}~${p.maxLength}자로 적어 주세요")
        }
        val hash = VibeQuery.hash(q)
        val version = maps.activeVersion() ?: 0L
        // 하루 횟수를 정확히 세려고 방 행을 잠근다 (코드 맞추기와 같은 잠금)
        rooms.lock(ctx.room.id)
        searches.findReusable(ctx.room.id, hash, version)?.let { return HttpStatus.OK to view(ctx, it) }
        if (searches.countToday(ctx.member.id, todayKst()) >= p.dailyLimit) {
            throw ApiException.tooMany("DAILY_LIMIT", "오늘은 분위기 찾기를 ${p.dailyLimit}번 했어요. 내일 또 해 주세요")
        }
        val id = searches.insert(ctx.room.id, ctx.member.id, q, hash, version)
        sender.send(props.room.vibeSearchQueue, json.writeValueAsString(mapOf("searchId" to id)))
        return HttpStatus.ACCEPTED to mapOf("id" to id, "status" to "PENDING")
    }

    fun get(ctx: RoomContext, id: Long): VibeSearchView {
        val row = searches.get(id)?.takeIf { it.roomId == ctx.room.id }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "VIBE_SEARCH_NOT_FOUND", "찾기 결과가 없어요")
        return view(ctx, row)
    }

    private fun view(ctx: RoomContext, row: VibeSearchRow): VibeSearchView {
        val picks: List<VibePick> = row.picksJson?.let { json.readValue(it, Array<VibePick>::class.java).toList() } ?: emptyList()
        val ids = picks.map { it.artistId }
        val names = searches.artistNames(ids)
        val photos = images.commonsPhotos(ids)
        val inRoom = searches.inRoomBy(ctx.room.id, ids)
        return VibeSearchView(
            row.id, row.query, row.status, row.createdAt,
            picks.filter { it.artistId in names }.map {
                VibePickView(it.artistId, names[it.artistId]!!, photos[it.artistId]?.let { _ -> faces.imagePath(ctx.room.publicId, it.artistId) }, it.reason, inRoom[it.artistId].orEmpty())
            },
            maps.chunksByIds(row.evidence).map { EvidenceView(it.id, it.source, it.url) },
            row.status == "DONE" && picks.isEmpty(),
        )
    }
}

@RestController
class VibeSearchController(private val service: VibeSearchService) {
    /** 새 요청이면 202 {id, status: PENDING}, 같은 질문 재사용이면 200 VibeSearchView */
    @PostMapping("/api/rooms/{rid}/vibe-search")
    fun search(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestBody body: VibeSearchRequest): ResponseEntity<Any> {
        val (status, view) = service.search(ctx, body)
        return ResponseEntity.status(status).body(view)
    }

    @GetMapping("/api/rooms/{rid}/vibe-search/{id}")
    fun get(@PathVariable rid: String, @RoomMember ctx: RoomContext, @PathVariable id: Long): VibeSearchView = service.get(ctx, id)
}
