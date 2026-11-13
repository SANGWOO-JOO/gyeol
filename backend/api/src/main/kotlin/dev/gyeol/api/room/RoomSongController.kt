package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.search.TrackItem
import dev.gyeol.api.search.TrackSearchController
import dev.gyeol.api.web.ApiException
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

data class SongView(val id: Long, val trackId: Long, val title: String, val artist: String, val coverUrl: String?, val sharedCount: Int)

/** 201: song이 있음. 202: requestId로 식별 상태를 읽는다 */
data class AddSongResponse(val status: String, val song: SongView?, val requestId: Long?, val todayAdded: Int, val dailyLimit: Int)

data class RequestView(val id: Long, val status: String, val song: SongView?, val failReason: String?)

/** 검색 결과 + 방 안 표시: mine(내가 담음), sameSong(같은 노래를 담은 사람), sameArtist(같은 가수 다른 노래를 담은 사람) */
data class RoomSearchItem(val item: TrackItem, val mine: Boolean, val sameSong: List<String>, val sameArtist: List<String>)
data class RoomSearchResponse(val items: List<RoomSearchItem>, val source: String)

data class MySongView(val id: Long, val trackId: Long, val title: String, val artist: String, val coverUrl: String?, val addedAt: Instant, val sharedWith: Int)
data class MySongsPage(val items: List<MySongView>, val nextCursor: Long?)

/**
 * 지도. 좌표는 공용 지도 판(0~1). 활성 판이 없거나 아직 자리를 못 잡은 가수는 임시 자리(가수 mbid 해시)에 preparing=true.
 * galaxies: 이 방에 행성이 있는 구역만 (⑦). ghosts: 방에 없는 가까운 가수 2~3명 (⑧ 흐린 행성)
 */
data class MapResponse(
    val rev: Long, val placeholder: Boolean, val members: List<MemberView>, val planets: List<Planet>,
    val galaxies: List<GalaxyView> = emptyList(), val ghosts: List<Ghost> = emptyList(),
)
data class Planet(val artistId: Long, val name: String, val x: Double, val y: Double, val songs: List<MapSongView>, val galaxyId: Long? = null, val preparing: Boolean = false)
data class GalaxyView(val id: Long, val name: String?, val x: Double, val y: Double)
data class Ghost(val artistId: Long, val name: String, val x: Double, val y: Double, val near: Long)

/** 행성 자세히 (4′): 설명·근거, 방 안 노래, 가까운 가수 */
data class ArtistView(
    val artistId: Long, val name: String, val description: String?, val evidence: List<EvidenceView>,
    val songs: List<MapSongView>, val nearby: List<NearbyView>, val preparing: Boolean,
)
data class EvidenceView(val id: Long, val source: String, val url: String)
data class NearbyView(val artistId: Long, val name: String, val inRoom: Boolean)
data class MapSongView(val trackId: Long, val title: String, val coverUrl: String?, val memberIds: List<Long>)

@Service
class RoomSongService(
    private val rooms: RoomRepository,
    private val songs: RoomSongRepository,
    private val search: TrackSearchController,
    private val sender: AfterCommitSender,
    private val json: JsonMapper,
    private val props: GyeolProperties,
    private val maps: MapRepository,
) {
    private val p get() = props.room

    fun search(ctx: RoomContext, q: String?): RoomSearchResponse {
        val r = search.search(q, 10)
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
            return HttpStatus.CREATED to AddSongResponse("ADDED", songView(room.id, me, id), null, today, p.dailySongLimit)
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

    private fun songView(roomId: Long, memberId: Long, songId: Long): SongView {
        val s = songs.mySongs(memberId, songId + 1, 1).single()
        return SongView(s.id, s.trackId, s.title, s.artistName, s.coverUrl, songs.sharedCount(roomId, s.trackId))
    }

    fun request(ctx: RoomContext, id: Long): RequestView {
        val r = songs.request(id)?.takeIf { it.roomId == ctx.room.id && it.memberId == ctx.member.id }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "없는 요청이에요")
        val song = r.roomSongId?.let { sid -> runCatching { songView(ctx.room.id, ctx.member.id, sid) }.getOrNull() }
        return RequestView(r.id, r.status, song, r.failReason)
    }

    @Transactional
    fun remove(ctx: RoomContext, songId: Long) {
        songs.remove(songId, ctx.member.id) ?: throw ApiException(HttpStatus.NOT_FOUND, "SONG_NOT_FOUND", "내가 담은 노래가 아니에요")
        rooms.bumpMapRev(ctx.room.id)
    }

    fun mySongs(ctx: RoomContext, cursor: Long?, limit: Int): MySongsPage {
        if (limit !in 1..100) throw ApiException.validation("limit은 1~100이에요")
        val rows = songs.mySongs(ctx.member.id, cursor, limit + 1)
        val page = rows.take(limit).map { MySongView(it.id, it.trackId, it.title, it.artistName, it.coverUrl, it.addedAt, it.sharedWith) }
        return MySongsPage(page, if (rows.size > limit) page.last().id else null)
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
            Planet(a.artistId, a.artistName, x, y, list.map { MapSongView(it.trackId, it.title, it.coverUrl, it.memberIds) }, at?.galaxyId, at == null)
        }
        val galaxies = maps.galaxies(pos.values.mapNotNull { it.galaxyId }.toSet()).map { GalaxyView(it.id, it.name, it.cx, it.cy) }
        return MapResponse(ctx.room.mapRev, version == null, members, planets, galaxies, ghosts(version, byArtist.keys, byArtist))
    }

    /** 최근에 담긴 가수 몇 명 곁에서, 방에 없는 가장 가까운 가수 하나씩 (최대 3) */
    private fun ghosts(version: Long?, inRoom: Set<Long>, byArtist: Map<Long, List<MapSong>>): List<Ghost> {
        if (version == null || inRoom.isEmpty()) return emptyList()
        val out = linkedMapOf<Long, Ghost>()
        for (artistId in byArtist.keys.reversed().take(5)) {
            val (v, byRaw) = maps.placeVector(artistId) ?: continue
            val n = maps.nearestPlaced(version, v, 3, inRoom + out.keys, byRaw).firstOrNull() ?: continue
            out[n.artistId] = Ghost(n.artistId, n.name, n.x, n.y, artistId)
            if (out.size >= 3) break
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
        return ArtistView(d.artistId, d.name, d.description, evidence, roomSongs.map { MapSongView(it.trackId, it.title, it.coverUrl, it.memberIds) }, nearby, !placed)
    }

    /** 자리를 아직 못 잡은 가수의 임시 좌표: 가수 mbid로 0~1 사이 고정 위치 */
    private fun placeholderXY(s: MapSong): Pair<Double, Double> =
        ((s.artistMbid.mostSignificantBits ushr 11).toDouble() / (1L shl 53)) to ((s.artistMbid.leastSignificantBits ushr 11).toDouble() / (1L shl 53))
}

@RestController
class RoomSongController(private val service: RoomSongService) {

    @GetMapping("/api/rooms/{rid}/search")
    fun search(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestParam q: String?): RoomSearchResponse = service.search(ctx, q)

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
    ): MySongsPage = service.mySongs(ctx, cursor, limit)

    @GetMapping("/api/rooms/{rid}/artists/{artistId}")
    fun artist(@PathVariable rid: String, @PathVariable artistId: Long, @RoomMember ctx: RoomContext): ArtistView = service.artist(ctx, artistId)

    /** 몇 초마다 새로고침. 바뀐 게 없으면(map_rev 같음) 304 */
    @GetMapping("/api/rooms/{rid}/map")
    fun map(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) etag: String?): ResponseEntity<MapResponse> {
        val tag = "\"${ctx.room.mapRev}\""
        if (etag == tag) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(tag).build()
        return ResponseEntity.ok().eTag(tag).body(service.map(ctx))
    }
}
