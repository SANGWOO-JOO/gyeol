package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.search.TrackItem
import dev.gyeol.api.search.TrackSearchController
import dev.gyeol.api.search.TrackSearchResponse
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.MapSong
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.RoomSongRepository
import dev.gyeol.core.map.MapRepository
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** trackId(우리 DB 검색 결과) 또는 제목·가수(Last.fm 결과) */
data class AddSongRequest(val trackId: Long?, val title: String?, val artist: String?, val mbid: String?)

/** coverPath (I-14): 표지 S3 저장본이 있으면 우리 주소(GET /api/rooms/{rid}/covers/{trackId}), 없으면 null → coverUrl(Cover Art Archive)을 쓴다 */
data class SongView(val id: Long, val trackId: Long, val title: String, val artist: String, val coverUrl: String?, val sharedCount: Int, val coverPath: String? = null)

/** 201: song이 있음. 202: requestId로 식별 상태를 읽는다. gift: 이번 담기로 50곡 단위 상자가 생겼으면 (④) */
data class AddSongResponse(val status: String, val song: SongView?, val requestId: Long?, val todayAdded: Int, val dailyLimit: Int, val gift: GiftNotice? = null)

/** status: READY(바로 열 수 있음) 또는 WAITING(오늘 이미 받아 내일 열림) */
data class GiftNotice(val boxId: Long, val milestone: Int, val status: String)

/**
 * gift (I-51): 식별이 끝나 곡이 담기면서 50곡 단위 상자가 생겼을 때만 값이 있다. 201 응답의 gift와 같은 모양이고 status는 생긴 순간의 값이다.
 * 같은 결과를 여러 번 받아도 boxId가 같으므로 화면은 boxId로 알림을 한 번만 띄운다
 */
data class RequestView(val id: Long, val status: String, val song: SongView?, val failReason: String?, val gift: GiftNotice? = null)

/** 검색 결과 + 방 안 표시: mine(내가 담음), sameSong(같은 노래를 담은 사람), sameArtist(같은 가수 다른 노래를 담은 사람) */
data class RoomSearchItem(val item: TrackItem, val mine: Boolean, val sameSong: List<String>, val sameArtist: List<String>)
/**
 * 가수 카드 (I-30 ①): 검색어가 가수와 맞으면 결과 맨 위에 하나. artistId가 없으면 우리 DB에 아직 없는 가수(Last.fm 이름만, 인기순만 열 수 있음)
 */
data class ArtistCard(
    val artistId: Long?, val name: String, val aliases: List<String>, val songCountInRoom: Int, val onMap: Boolean,
    /** 지도 행성·가수 판과 같은 이름(우리 DB artist.name). DB에 없는 가수(artistId 없음)는 null. [name]은 검색 결과 표기라 그대로 둔다 */
    val displayName: String? = null,
    /** 지도 얼굴과 같은 규칙: PHOTO(사진 주소·출처) → COVER(방에 담긴 그 가수 곡 표지) → NONE. DB에 없는 가수는 NONE */
    val face: Face? = null,
)
/**
 * 더보기: [page] 1~5, 한 쪽 10곡. [hasMore]는 다음 쪽이 있을 수 있는지 (1쪽은 10곡이 꽉 찼으면 true, 2쪽 이후는 이어 붙인 목록 기준).
 * 가수 카드([artist])는 1쪽에만
 */
data class RoomSearchResponse(val items: List<RoomSearchItem>, val source: String, val artist: ArtistCard? = null, val page: Int = 1, val hasMore: Boolean = false)

/** 내 노래 정렬 묶음 (sort=genre|artist). key로 쪽 사이를 이어 붙인다. count는 쪽과 상관없는 그 묶음 전체 곡 수 */
data class SongGroup(
    val key: String, val kind: String, val name: String, val count: Int,
    /** GENRE: 지도 장르 구역 id (분류 중이면 null) */
    val galaxyId: Long? = null,
    /** ARTIST: 가수 id, 지도와 같은 이름, 얼굴(사진 → 방 곡 표지 → DB 저장 표지 → NONE) */
    val artistId: Long? = null, val displayName: String? = null, val face: Face? = null,
)
data class MySongView(
    val id: Long, val trackId: Long, val title: String, val artist: String, val coverUrl: String?, val addedAt: Instant, val sharedWith: Int,
    val coverPath: String? = null, val group: SongGroup? = null,
)
/**
 * [sort]: recent(기본, 지금과 같음)·genre·artist. genre·artist는 첫 쪽의 [snapshot](그때까지 담은 곡의 마지막 id)을 다음 쪽 요청에 함께 보내면
 * 그사이 곡을 더 담아도 묶음 순서가 흔들리지 않는다. recent는 snapshot이 null
 */
data class MySongsPage(val items: List<MySongView>, val nextCursor: Long?, val sort: String = "recent", val snapshot: Long? = null)

/**
 * 지도. 좌표는 공용 지도 판(0~1). 활성 판이 없거나 아직 자리를 못 잡은 가수는 임시 자리(가수 mbid 해시)에 preparing=true.
 * galaxies: 이 방에 행성이 있는 구역만 (⑦). ghosts: 방에 없는 가까운 가수, 행성 1개면 3명·여럿이면 행성마다 나눠 전체 6명까지 (⑧ 흐린 행성, I-40)
 */
data class MapResponse(
    val rev: Long, val placeholder: Boolean, val members: List<MemberView>, val planets: List<Planet>,
    val galaxies: List<GalaxyView> = emptyList(), val ghosts: List<Ghost> = emptyList(),
)
data class Planet(val artistId: Long, val name: String, val x: Double, val y: Double, val songs: List<MapSongView>, val galaxyId: Long? = null, val preparing: Boolean = false, val face: Face? = null)
data class GalaxyView(val id: Long, val name: String?, val x: Double, val y: Double)
data class Ghost(val artistId: Long, val name: String, val x: Double, val y: Double, val near: Long, val galaxyId: Long? = null, val face: Face? = null)

/** 행성 자세히 (4′): 설명·근거, 방 안 노래, 가까운 가수 */
data class ArtistView(
    val artistId: Long, val name: String, val description: String?, val evidence: List<EvidenceView>,
    val songs: List<MapSongView>, val nearby: List<NearbyView>, val preparing: Boolean,
    /** I-44: 지도와 같은 얼굴, 사진은 Commons일 때만(저작자 표시) */
    val face: Face? = null, val photo: PhotoView? = null,
)
data class EvidenceView(val id: Long, val source: String, val url: String)
data class NearbyView(val artistId: Long, val name: String, val inRoom: Boolean)
data class MapSongView(val trackId: Long, val title: String, val coverUrl: String?, val memberIds: List<Long>, val coverPath: String? = null)

/**
 * 지도 응답 형식 버전 (ETag 맨 앞). 지도 응답(MapResponse와 그 안의 Planet·MapSongView·Ghost·Face 등)의 필드를 더하거나 바꾸면 올린다.
 * 안 올리면 브라우저가 304로 옛 모양의 본문을 계속 쓴다. v2: I-14 coverPath (11-14)
 */
const val MAP_FORMAT = "v2"

/** 흐린 행성 수 (I-40): 전체 상한, 행성 하나당 상한 (행성 1개면 3명) */
private const val GHOSTS_TOTAL = 6
/** 방 검색 더보기: 한 쪽 10곡, 5쪽(50곡)까지. 50은 Last.fm track.search가 한 번에 주는 수 안 (11-14 확인) */
const val SEARCH_PAGE_SIZE = 10
const val SEARCH_PAGES = 5
/** 검색 때 가수 미리 준비: 방당 하루 맡기는 가수 수 */
const val PREPARE_PER_ROOM = 30
/** 내 노래 장르 묶음: 장르 구역이 아직 없는 곡 */
const val UNSORTED = "분류 중"
private const val GHOSTS_PER_PLANET_MAX = 3

@Service
class RoomSongService(
    private val rooms: RoomRepository,
    private val songs: RoomSongRepository,
    private val search: TrackSearchController,
    private val sender: AfterCommitSender,
    private val json: JsonMapper,
    private val props: GyeolProperties,
    private val maps: MapRepository,
    private val gifts: GiftRepository,
    private val merger: dev.gyeol.api.search.TrackMerger,
    private val trackSearch: dev.gyeol.core.identify.TrackSearchRepository,
    private val faces: ArtistFaces,
    private val covers: RoomCovers,
    private val prepares: dev.gyeol.core.identify.ArtistPrepareRepository,
) {
    private val p get() = props.room

    fun search(ctx: RoomContext, q: String?, page: Int = 1): RoomSearchResponse {
        if (page !in 1..SEARCH_PAGES) throw ApiException.validation("page는 1~${SEARCH_PAGES}이어야 해요")
        // 1쪽은 지금 검색 그대로. 2쪽 이후는 1쪽을 다시 만들고(Last.fm은 캐시) 50곡 목록을 뒤에 이어 붙인 뒤,
        // 같은 곡 합치기 규칙(dedupe)을 전체에 한 번 더 적용해 쪽 사이 중복을 막는다
        // 한 글자(한글·한자·가나) 검색 허용 (11-14). 결과가 넓으므로 이름·별칭이 정확히 그 글자인 우리 가수의 곡을 앞에 둔다
        val single = dev.gyeol.core.identify.SearchText.isSingleCjk(q.orEmpty())
        val boost = if (!single) null else merger.resolveArtists(listOf(q!!.trim()))[dev.gyeol.core.identify.SearchText.norm(q)]?.id
        fun boosted(items: List<TrackItem>) = if (boost == null) items else items.sortedBy { if (it.artistId == boost) 0 else 1 }
        val first = search.find(q, SEARCH_PAGE_SIZE, allowSingleCjk = true).let { it.copy(items = boosted(it.items)) }
        if (page == 1) {
            val res = decorate(ctx, first.items, first.source)
            val card = artistCard(ctx, q.orEmpty(), first.items)
            card?.let { prepareIfNeeded(ctx, it) }
            return res.copy(artist = card, page = 1, hasMore = first.items.size >= SEARCH_PAGE_SIZE)
        }
        val more = search.find(q, SEARCH_PAGE_SIZE * SEARCH_PAGES, allowSingleCjk = true)
        val all = merger.dedupe(first.items + boosted(more.items)).take(SEARCH_PAGE_SIZE * SEARCH_PAGES)
        val slice = all.drop((page - 1) * SEARCH_PAGE_SIZE).take(SEARCH_PAGE_SIZE)
        return decorate(ctx, slice, more.source).copy(page = page, hasMore = page < SEARCH_PAGES && all.size > page * SEARCH_PAGE_SIZE)
    }

    /**
     * 검색 때 가수 미리 준비 (11-14, B안): 카드가 우리 DB에 없는 가수거나 얼굴이 NONE이면 worker에 맡기고 기다리지 않는다.
     * 같은 이름(가수)은 하루 1번, 진행 중이면 다시 맡기지 않는다(1시간 넘게 멈추면 다시). 방당 하루 [PREPARE_PER_ROOM]명
     */
    private fun prepareIfNeeded(ctx: RoomContext, card: ArtistCard) {
        val key = when {
            card.artistId == null -> "n:" + dev.gyeol.core.identify.SearchText.norm(card.name)
            card.face?.kind == "NONE" -> "id:${card.artistId}"
            else -> return
        }
        if (key == "n:") return
        try {
            if (prepares.request(key, card.name, ctx.room.id, todayKst(), PREPARE_PER_ROOM)) {
                sender.send(p.songQueue, json.writeValueAsString(mapOf("type" to "PREPARE_SEARCH_ARTIST", "prepKey" to key, "name" to card.name, "artistId" to card.artistId)))
            }
        } catch (e: RuntimeException) {
            // 준비는 덤이다: 실패해도 검색 응답은 그대로
            org.slf4j.LoggerFactory.getLogger(javaClass).warn("가수 미리 준비를 맡기지 못함: {} {}", key, e.message)
        }
    }

    private fun decorate(ctx: RoomContext, found: List<TrackItem>, source: String): RoomSearchResponse {
        val r = TrackSearchResponse(found, source)
        val names = rooms.members(ctx.room.id).associate { it.id to it.nickname }
        val byTrack = songs.whoHasTracks(ctx.room.id, r.items.mapNotNull { it.trackId })
        val byArtist = songs.roomArtistNames(ctx.room.id)
        val me = ctx.member.id
        val items = r.items.map { item ->
            val songOwners = item.trackId?.let { byTrack[it] }.orEmpty()
            val artistOwners = byArtist[item.artist.lowercase()].orEmpty() - songOwners.toSet()
            RoomSearchItem(
                item, me in songOwners,
                songOwners.filter { it != me }.mapNotNull(names::get),
                artistOwners.filter { it != me }.mapNotNull(names::get),
            )
        }
        return RoomSearchResponse(items, r.source)
    }

    /**
     * 가수 카드 판정: ① 검색어가 우리 가수 한 명의 이름·별칭과 같음 ② 결과 절반 이상이 한 가수이고 검색어가 그 이름의 앞부분
     * ③ 우리 DB에 없는 가수: Last.fm 결과 절반 이상의 가수 이름이 검색어와 같음 (artistId 없음)
     */
    private fun artistCard(ctx: RoomContext, q: String, items: List<TrackItem>): ArtistCard? {
        val qn = dev.gyeol.core.identify.SearchText.norm(q)
        if ((qn.length < 2 && !dev.gyeol.core.identify.SearchText.isSingleCjk(q)) || items.isEmpty()) return null
        val half = (items.size + 1) / 2
        fun names(a: dev.gyeol.core.identify.ArtistNames) = listOf(a.name) + a.aliases
        val exact = merger.resolveArtists(listOf(q))[qn]
        val byCount = items.mapNotNull { it.artistId }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val prefix = byCount?.takeIf { it.value >= half }?.let { (id, _) ->
            trackSearch.artistNames(id)?.takeIf { a -> names(a).any { dev.gyeol.core.identify.SearchText.norm(it).startsWith(qn) } }
        }
        val artist = exact ?: prefix
        if (artist != null) {
            val shown = items.filter { it.artistId == artist.id }.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: artist.name
            val version = maps.activeVersion()
            val at = version?.let { maps.positions(it, listOf(artist.id))[artist.id] }
            val roomSongs = songs.mapSongs(ctx.room.id).filter { it.artistId == artist.id }
            // 얼굴은 가수 판(artist)과 같은 계산: 사진 → 방 곡 표지(먼저 담긴 곡부터) → 없음.
            // 카드만 한 단계 더: 방에 그 가수 곡이 없으면 DB에 표지 저장본이 있는 그 가수 곡(재생 수 많은 곡, 같으면 id 작은 곡). 외부 호출 없음
            val roomCover = roomSongs.map { it.view(ctx.room.publicId) }.firstNotNullOfOrNull { it.coverPath ?: it.coverUrl }
            val cover = roomCover ?: songs.artistStoredCoverTrack(artist.id)?.let { covers.path(ctx.room.publicId, it, true) }
            val face = faces.faces(ctx.room.publicId, mapOf(artist.id to (at?.galaxyId to cover)))[artist.id]
            return ArtistCard(
                artist.id, shown, names(artist).filter { it != shown }.distinct(), roomSongs.size, at != null,
                displayName = artist.name, face = face,
            )
        }
        val remote = items.filter { it.artistId == null && dev.gyeol.core.identify.SearchText.norm(it.artist) == qn }
        return remote.takeIf { it.size >= half }?.let { ArtistCard(null, it.first().artist, emptyList(), 0, false, face = Face("NONE", null, null)) }
    }

    /** DB 곡은 바로 담고(201), Last.fm 곡은 식별을 맡긴다(202). 하루 한도는 둘 다 여기서 센다 */
    @Transactional
    fun add(ctx: RoomContext, body: AddSongRequest): Pair<HttpStatus, AddSongResponse> {
        val room = rooms.lock(ctx.room.id)
        val me = ctx.member.id
        if (body.trackId != null) {
            val artistId = songs.trackArtist(body.trackId) ?: throw ApiException.validation("없는 곡이에요")
            if (songs.has(room.id, me, body.trackId)) throw ApiException.conflict("ALREADY_ADDED", "이미 담은 노래예요")
            val today = countToday(me)
            val id = songs.add(room.id, me, body.trackId, artistId)!!
            rooms.bumpMapRev(room.id)
            requestPrepareIfNeeded(artistId)
            // 표지를 한 번도 확인하지 않은 DB 곡이면 worker에 맡긴다 (I-31: 이 경로는 식별·cover 큐를 거치지 않았다)
            if (songs.coverUnchecked(body.trackId)) {
                sender.send(p.songQueue, json.writeValueAsString(mapOf("type" to "COVER", "trackIds" to listOf(body.trackId))))
            }
            // 방 행을 잠근 같은 트랜잭션에서 50곡 단위 상자 (식별 경로는 worker RoomSongService가 같은 함수를 부른다)
            val box = gifts.onSongAdded(me, todayKst())?.let { GiftNotice(it.id, it.milestone, it.status.name) }
            return HttpStatus.CREATED to AddSongResponse("ADDED", songView(room.publicId, room.id, me, id), null, today, p.dailySongLimit, box)
        }
        val title = body.title?.trim()?.takeIf { it.length in 1..300 } ?: throw ApiException.validation("제목은 1~300자예요")
        val artist = body.artist?.trim()?.takeIf { it.length in 1..300 } ?: throw ApiException.validation("가수는 1~300자예요")
        val mbid = body.mbid?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ApiException.validation("mbid 형식이 올바르지 않아요") }
        if (songs.pendingRequests(me) >= p.maxPendingRequests) throw ApiException.tooMany("TOO_MANY_PENDING", "찾는 중인 노래가 많아요. 잠시 뒤 다시 담아 주세요")
        val today = countToday(me)
        val requestId = songs.createRequest(room.id, me, title, artist, mbid)
        sender.send(p.songQueue, json.writeValueAsString(mapOf("type" to "IDENTIFY", "requestId" to requestId)))
        return HttpStatus.ACCEPTED to AddSongResponse("IDENTIFYING", null, requestId, today, p.dailySongLimit)
    }

    private fun countToday(memberId: Long): Int =
        songs.incrementDaily(memberId, todayKst(), p.dailySongLimit)
            ?: throw ApiException.tooMany("DAILY_LIMIT", "오늘은 ${p.dailySongLimit}곡까지 담을 수 있어요. 내일 또 담아 주세요")

    private fun songView(rid: String, roomId: Long, memberId: Long, songId: Long): SongView {
        val s = songs.mySongs(memberId, songId + 1, 1).single()
        return SongView(s.id, s.trackId, s.title, s.artistName, s.coverUrl, songs.sharedCount(roomId, s.trackId), covers.path(rid, s.trackId, s.coverStored))
    }

    fun request(ctx: RoomContext, id: Long): RequestView {
        val r = songs.request(id)?.takeIf { it.roomId == ctx.room.id && it.memberId == ctx.member.id }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "없는 요청이에요")
        val song = r.roomSongId?.let { sid -> runCatching { songView(ctx.room.publicId, ctx.room.id, ctx.member.id, sid) }.getOrNull() }
        val gift = r.giftBoxId?.let { b -> gifts.box(b)?.let { GiftNotice(it.id, it.milestone, r.giftStatus ?: it.status.name) } }
        return RequestView(r.id, r.status, song, r.failReason, gift)
    }

    @Transactional
    fun remove(ctx: RoomContext, songId: Long) {
        songs.remove(songId, ctx.member.id) ?: throw ApiException(HttpStatus.NOT_FOUND, "SONG_NOT_FOUND", "내가 담은 노래가 아니에요")
        rooms.bumpMapRev(ctx.room.id)
    }

    fun mySongs(ctx: RoomContext, cursor: Long?, limit: Int, sort: String = "recent", snapshot: Long? = null): MySongsPage {
        if (limit !in 1..100) throw ApiException.validation("limit은 1~100이에요")
        if (sort == "genre" || sort == "artist") return groupedSongs(ctx, cursor, limit, sort, snapshot)
        if (sort != "recent") throw ApiException.validation("sort는 recent·genre·artist예요")
        val rows = songs.mySongs(ctx.member.id, cursor, limit + 1)
        val page = rows.take(limit).map { MySongView(it.id, it.trackId, it.title, it.artistName, it.coverUrl, it.addedAt, it.sharedWith, covers.path(ctx.room.publicId, it.trackId, it.coverStored)) }
        return MySongsPage(page, if (rows.size > limit) page.last().id else null)
    }

    /**
     * 내 노래 묶어 보기 (11-14). 첫 쪽에서 snapshot(지금까지 담은 곡의 마지막 id)을 정하고, 그 이하 곡만으로 순서를 만든다.
     * 순서: 묶음은 곡 많은 순 → 이름 순, "분류 중"(장르 구역 없음)은 맨 뒤, 묶음 안은 최근 담은 순. 커서는 앞 쪽 마지막 곡 id.
     * 같은 snapshot이면 순서가 같아 쪽 사이 중복·누락이 없다. 커서 곡을 그사이 뺐으면 409 CURSOR_STALE(처음부터 다시)
     */
    private fun groupedSongs(ctx: RoomContext, cursor: Long?, limit: Int, sort: String, snapshot: Long?): MySongsPage {
        val rid = ctx.room.publicId
        val all = songs.mySongs(ctx.member.id, (snapshot ?: Long.MAX_VALUE - 1) + 1, 100_000) // id 내림차순 = 최근 담은 순
        val snap = snapshot ?: all.firstOrNull()?.id
        val version = maps.activeVersion()
        val galaxyOf = version?.let { v -> maps.positions(v, all.map { it.artistId }.toSet()).mapValues { it.value.galaxyId } }.orEmpty()
        data class G(val key: String, val name: String, val unsorted: Boolean, val galaxyId: Long? = null, val artistId: Long? = null)
        val groupOf: Map<Long, G> = if (sort == "genre") {
            val names = maps.galaxies(galaxyOf.values.filterNotNull().toSet()).associate { it.id to (it.name ?: "이름 없는 구역") }
            all.associate { s ->
                val g = galaxyOf[s.artistId]
                s.id to (if (g == null || g !in names) G("genre:none", UNSORTED, true) else G("genre:$g", names[g]!!, false, galaxyId = g))
            }
        } else {
            all.associate { s -> s.id to G("artist:${s.artistId}", s.artistName, false, artistId = s.artistId) }
        }
        val counts = all.groupingBy { groupOf[it.id]!!.key }.eachCount()
        val groupOrder = all.map { groupOf[it.id]!! }.distinctBy { it.key }
            .sortedWith(compareBy<G> { it.unsorted }.thenByDescending { counts[it.key]!! }.thenBy { it.name }.thenBy { it.key })
            .withIndex().associate { (i, g) -> g.key to i }
        // 묶음 안은 all의 순서(최근 담은 순)를 그대로 쓴다 (안정 정렬)
        val ordered = all.sortedBy { groupOrder[groupOf[it.id]!!.key]!! }
        val start = if (cursor == null) 0 else ordered.indexOfFirst { it.id == cursor }.let { i ->
            if (i < 0) throw ApiException(HttpStatus.CONFLICT, "CURSOR_STALE", "목록이 바뀌었어요. 처음부터 다시 불러 주세요")
            i + 1
        }
        val page = ordered.drop(start).take(limit)
        // 가수 묶음 얼굴: 이 쪽에 나온 가수만 (사진 → 방 곡 표지 → DB 저장 표지 → NONE)
        val faceOf = if (sort != "artist" || page.isEmpty()) emptyMap() else {
            val ids = page.map { it.artistId }.toSet()
            val roomCover = songs.mapSongs(ctx.room.id).filter { it.artistId in ids }.groupBy { it.artistId }
                .mapValues { (_, xs) -> xs.map { it.view(rid) }.firstNotNullOfOrNull { it.coverPath ?: it.coverUrl } }
            faces.faces(rid, ids.associateWith { a ->
                galaxyOf[a] to (roomCover[a] ?: songs.artistStoredCoverTrack(a)?.let { covers.path(rid, it, true) })
            })
        }
        val items = page.map {
            val g = groupOf[it.id]!!
            val group = SongGroup(
                g.key, if (sort == "genre") "GENRE" else "ARTIST", g.name, counts[g.key]!!, g.galaxyId,
                g.artistId, g.artistId?.let { _ -> g.name }, g.artistId?.let(faceOf::get),
            )
            MySongView(it.id, it.trackId, it.title, it.artistName, it.coverUrl, it.addedAt, it.sharedWith, covers.path(rid, it.trackId, it.coverStored), group)
        }
        val next = if (start + page.size < ordered.size) page.lastOrNull()?.id else null
        return MySongsPage(items, next, sort, snap)
    }

    /** 공용 지도에 자리가 없는 가수면 worker에 준비를 맡긴다 (한 번만, 커밋 뒤) */
    private fun requestPrepareIfNeeded(artistId: Long) {
        val version = maps.activeVersion()
        if (version != null && maps.positions(version, listOf(artistId)).isNotEmpty()) return
        if (maps.requestPrepare(artistId)) sender.send(p.songQueue, json.writeValueAsString(mapOf("type" to "PREPARE_ARTIST", "artistId" to artistId)))
    }

    fun map(ctx: RoomContext): MapResponse {
        val members = rooms.members(ctx.room.id).map { it.view() }
        val byArtist = songs.mapSongs(ctx.room.id).groupBy { it.artistId }
        val version = maps.activeVersion()
        val pos = version?.let { maps.positions(it, byArtist.keys) }.orEmpty()
        val planets = byArtist.values.map { list ->
            val a = list.first()
            val at = pos[a.artistId]
            val (x, y) = at?.let { it.x to it.y } ?: placeholderXY(a)
            Planet(a.artistId, a.artistName, x, y, list.map { it.view(ctx.room.publicId) }, at?.galaxyId, at == null)
        }
        val ghosts = ghosts(version, byArtist.keys, byArtist)
        // 얼굴 (I-44): 행성은 사진 → 방 곡 표지(가장 먼저 담긴 곡부터) → 없음, 흐린 행성은 사진 → 없음. 사진은 한 번에 읽는다
        val face = faces.faces(
            ctx.room.publicId,
            planets.associate { pl -> pl.artistId to (pl.galaxyId to pl.songs.firstNotNullOfOrNull { it.coverPath ?: it.coverUrl }) } +
                ghosts.associate { g -> g.artistId to (g.galaxyId to null) },
        )
        val galaxies = maps.galaxies(pos.values.mapNotNull { it.galaxyId }.toSet()).map { GalaxyView(it.id, it.name, it.cx, it.cy) }
        return MapResponse(
            ctx.room.mapRev, version == null, members, planets.map { it.copy(face = face[it.artistId]) }, galaxies,
            ghosts.map { it.copy(face = face[it.artistId]) },
        )
    }

    /**
     * 흐린 행성(방에 없는 가까운 가수, I-40 11-14 결정): 행성이 적을수록 많이.
     * 행성 1개면 그 가수의 가까운 가수 3명(가수 판의 "가까운 가수"와 같은 목록·순서에서 방에 있는 가수만 뺌),
     * 행성이 늘면 최근 담긴 행성부터 행성마다 ceil(6 ÷ 행성 수)명(최대 3)씩, 전체 6명까지. 방에 있는 가수·중복은 뺀다.
     * Ghost.artistId는 우리 가수 id라 웹이 가수 화면(I-30)으로 바로 연다
     */
    private fun ghosts(version: Long?, inRoom: Set<Long>, byArtist: Map<Long, List<MapSong>>): List<Ghost> {
        if (version == null || inRoom.isEmpty()) return emptyList()
        val planets = byArtist.keys.reversed()
        val per = minOf(GHOSTS_PER_PLANET_MAX, (GHOSTS_TOTAL + planets.size - 1) / planets.size)
        val out = linkedMapOf<Long, Ghost>()
        for (artistId in planets) {
            if (out.size >= GHOSTS_TOTAL) break
            val (v, byRaw) = maps.placeVector(artistId) ?: continue
            val take = minOf(per, GHOSTS_TOTAL - out.size)
            maps.nearestPlaced(version, v, take, inRoom + out.keys, byRaw).forEach { n ->
                out[n.artistId] = Ghost(n.artistId, n.name, n.x, n.y, artistId, n.galaxyId)
            }
        }
        return out.values.toList()
    }

    fun artist(ctx: RoomContext, artistId: Long): ArtistView {
        val d = maps.detail(artistId) ?: throw ApiException(HttpStatus.NOT_FOUND, "ARTIST_NOT_FOUND", "없는 가수예요")
        val roomSongs = songs.mapSongs(ctx.room.id).filter { it.artistId == artistId }
        val roomArtists = songs.mapSongs(ctx.room.id).map { it.artistId }.toSet()
        val evidence = maps.chunksByIds(d.evidence).map { EvidenceView(it.id, it.source, it.url) }
        val version = maps.activeVersion()
        val placed = version != null && maps.positions(version, listOf(artistId)).isNotEmpty()
        // 설명 없는 가수는 원문 벡터끼리 비교 (MapRepository.nearestPlaced)
        val nearby = if (!placed) emptyList() else maps.placeVector(artistId)?.let { (v, byRaw) ->
            maps.nearestPlaced(version!!, v, 5, listOf(artistId), byRaw).map { NearbyView(it.artistId, it.name, it.artistId in roomArtists) }
        }.orEmpty()
        val galaxy = version?.let { maps.positions(it, listOf(artistId))[artistId]?.galaxyId }
        val songViews = roomSongs.map { it.view(ctx.room.publicId) }
        // 가수 판도 검색 카드처럼 방 곡이 없으면 DB 저장 표지로 (지도 응답의 행성 얼굴은 그대로, 11-14)
        val cover = songViews.firstNotNullOfOrNull { it.coverPath ?: it.coverUrl }
            ?: songs.artistStoredCoverTrack(artistId)?.let { covers.path(ctx.room.publicId, it, true) }
        val face = faces.faces(ctx.room.publicId, mapOf(artistId to (galaxy to cover)))[artistId]
        return ArtistView(
            d.artistId, d.name, d.description, evidence, songViews, nearby, !placed,
            face, faces.photo(ctx.room.publicId, artistId),
        )
    }

    private fun MapSong.view(rid: String) = MapSongView(trackId, title, coverUrl, memberIds, covers.path(rid, trackId, coverStored))

    /** 자리를 아직 못 잡은 가수의 임시 좌표: 가수 mbid로 0~1 사이 고정 위치 */
    private fun placeholderXY(s: MapSong): Pair<Double, Double> =
        ((s.artistMbid.mostSignificantBits ushr 11).toDouble() / (1L shl 53)) to ((s.artistMbid.leastSignificantBits ushr 11).toDouble() / (1L shl 53))
}

@RestController
class RoomSongController(private val service: RoomSongService, private val faces: ArtistFaces, private val songs: dev.gyeol.core.room.RoomSongRepository) {

    @GetMapping("/api/rooms/{rid}/search")
    fun search(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestParam q: String?, @RequestParam(defaultValue = "1") page: Int): RoomSearchResponse =
        service.search(ctx, q, page)

    @PostMapping("/api/rooms/{rid}/songs")
    fun add(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestBody body: AddSongRequest): ResponseEntity<AddSongResponse> {
        val (status, res) = service.add(ctx, body)
        return ResponseEntity.status(status).body(res)
    }

    @GetMapping("/api/rooms/{rid}/song-requests/{id}")
    fun request(@PathVariable rid: String, @PathVariable id: Long, @RoomMember ctx: RoomContext): RequestView = service.request(ctx, id)

    @DeleteMapping("/api/rooms/{rid}/songs/{id}")
    fun remove(@PathVariable rid: String, @PathVariable id: Long, @RoomMember ctx: RoomContext): ResponseEntity<Void> {
        service.remove(ctx, id); return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/rooms/{rid}/me/songs")
    fun mySongs(
        @PathVariable rid: String, @RoomMember ctx: RoomContext,
        @RequestParam(required = false) cursor: Long?, @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(defaultValue = "recent") sort: String, @RequestParam(required = false) snapshot: Long?,
    ): MySongsPage = service.mySongs(ctx, cursor, limit, sort, snapshot)

    @GetMapping("/api/rooms/{rid}/artists/{artistId}")
    fun artist(@PathVariable rid: String, @PathVariable artistId: Long, @RoomMember ctx: RoomContext): ArtistView = service.artist(ctx, artistId)

    /**
     * 몇 초마다 새로고침. 바뀐 게 없으면 304. ETag = 응답 형식 버전 + map_rev + 가수 사진 상태(I-44) + 방 곡 표지 저장본 수(I-14).
     * 사진·표지 저장본은 map_rev를 올리지 않고 늘어나므로 따로 섞는다
     */
    @GetMapping("/api/rooms/{rid}/map")
    fun map(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) etag: String?): ResponseEntity<MapResponse> {
        val tag = "\"$MAP_FORMAT-${ctx.room.mapRev}-${faces.stamp()}-${songs.storedCoverCount(ctx.room.id)}\""
        if (etag == tag) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(tag).build()
        return ResponseEntity.ok().eTag(tag).body(service.map(ctx))
    }
}
