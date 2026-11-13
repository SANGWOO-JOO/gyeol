package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.search.TrackItem
import dev.gyeol.api.search.TrackMerger
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.DbTrackHit
import dev.gyeol.core.identify.SearchText
import dev.gyeol.core.identify.TrackSearchRepository
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.RoomSongRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** artistId가 없으면 우리 DB에 아직 없는 가수(Last.fm 이름만) */
data class ArtistHeader(val artistId: Long?, val name: String, val aliases: List<String>)

/**
 * 가수 화면 한 줄. item·mine·sameSong은 방 검색과 같은 모양(웹이 담기·"담음"·친구 금빛을 그대로 쓴다).
 * rank: 인기순 순위(1부터). releaseDate: 최신순에서 처음 나온 날(YYYY, YYYY-MM, YYYY-MM-DD, 모르면 null)
 */
data class ArtistSongItem(val item: TrackItem, val mine: Boolean, val sameSong: List<String>, val rank: Int? = null, val releaseDate: String? = null)

/** scan: 최신순일 때 발매일 모으기 상태(RUNNING·DONE·FAILED), 인기순은 null */
data class ArtistSongsPage(
    val artist: ArtistHeader, val sort: String, val page: Int, val pageSize: Int, val hasMore: Boolean,
    val items: List<ArtistSongItem>, val scan: String? = null,
)

/**
 * I-30 가수 화면 (docs/todo/artist-search.md 3·4절). 인기순은 Last.fm artist.getTopTracks를 이름 후보(대표 이름 + 한글·로마자 별칭 하나씩)로 받아 합친다.
 * "다비치"와 "DAVICHI"는 Last.fm 가수 페이지가 달라 인기곡이 다르다(11-14 표본). 합친 목록은 10분 메모리에 둬 페이지를 넘길 때 다시 부르지 않는다
 */
@Service
class ArtistSongsService(
    props: GyeolProperties,
    private val db: TrackSearchRepository,
    private val merger: TrackMerger,
    private val songs: RoomSongRepository,
    private val rooms: RoomRepository,
    private val latest: ArtistLatestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGES = 5
        private val CACHE_TTL: Duration = Duration.ofMinutes(10)
        private val HANGUL = Regex("""[가-힣]""")
        private val LATIN = Regex("""[A-Za-z]""")
    }

    private val fetcher: RestClient = RestClient.builder().baseUrl(props.fetcherUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(Duration.ofSeconds(1)); setReadTimeout(Duration.ofSeconds(5)) })
        .build()

    private data class Popular(val title: String, val mbid: String?, val playcount: Long)
    private val cache = ConcurrentHashMap<String, Pair<Instant, List<TrackItem>>>()

    fun songs(ctx: RoomContext, artistId: Long?, name: String?, sort: String?, page: Int?): ArtistSongsPage {
        val p = (page ?: 1).coerceAtLeast(1)
        val header = header(artistId, name)
        return when (sort ?: "popular") {
            "popular" -> {
                if (p > MAX_PAGES) return ArtistSongsPage(header, "popular", p, PAGE_SIZE, false, emptyList())
                val all = popular(header).take(PAGE_SIZE * MAX_PAGES)
                val slice = all.drop((p - 1) * PAGE_SIZE).take(PAGE_SIZE)
                ArtistSongsPage(header, "popular", p, PAGE_SIZE, p * PAGE_SIZE < all.size && p < MAX_PAGES, decorate(ctx, slice, rankFrom = (p - 1) * PAGE_SIZE + 1))
            }
            "latest" -> {
                val id = header.artistId ?: throw ApiException.validation("우리 DB에 없는 가수는 인기순만 볼 수 있어요")
                val (rows, scan, hasMore) = latest.page(id, header.name, p, PAGE_SIZE)
                ArtistSongsPage(header, "latest", p, PAGE_SIZE, hasMore, decorate(ctx, rows.map { it.first }, null).zip(rows) { s, r -> s.copy(releaseDate = r.second) }, scan)
            }
            else -> throw ApiException.validation("정렬은 popular 또는 latest예요")
        }
    }

    /** 표시 이름: 우리 곡들의 가장 흔한 표기(검색 결과와 같게), 없으면 MusicBrainz 이름 */
    fun header(artistId: Long?, name: String?): ArtistHeader {
        if (artistId != null) {
            val a = db.artistNames(artistId) ?: throw ApiException(HttpStatus.NOT_FOUND, "ARTIST_NOT_FOUND", "없는 가수예요")
            val shown = db.hitsOfArtist(artistId).groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: a.name
            return ArtistHeader(a.id, shown, (listOf(a.name) + a.aliases).filter { it != shown }.distinct())
        }
        val n = name?.trim()?.takeIf { it.length in 1..200 } ?: throw ApiException.validation("가수를 골라 주세요")
        // 이름으로 왔어도 우리 가수 한 명과 맞으면 그 가수로
        merger.resolveArtists(listOf(n))[SearchText.norm(n)]?.let { return header(it.id, null) }
        return ArtistHeader(null, n, emptyList())
    }

    /** Last.fm에 물어볼 이름: 대표 표기 + 한글 별칭 하나 + 로마자 별칭 하나 (정규화가 다른 것만, 최대 3) */
    private fun lastFmNames(h: ArtistHeader): List<String> {
        val all = listOf(h.name) + h.aliases
        val picked = linkedMapOf(SearchText.norm(h.name) to h.name)
        all.firstOrNull { HANGUL.containsMatchIn(it) }?.let { picked.putIfAbsent(SearchText.norm(it), it) }
        all.firstOrNull { LATIN.containsMatchIn(it) && !HANGUL.containsMatchIn(it) }?.let { picked.putIfAbsent(SearchText.norm(it), it) }
        return picked.values.take(3)
    }

    private fun popular(h: ArtistHeader): List<TrackItem> {
        val key = h.artistId?.let { "id:$it" } ?: "n:${SearchText.norm(h.name)}"
        cache[key]?.takeIf { it.first.isAfter(Instant.now().minus(CACHE_TTL)) }?.let { return it.second }
        if (cache.size > 500) cache.entries.removeIf { it.value.first.isBefore(Instant.now().minus(CACHE_TTL)) }

        // 이름마다 50곡씩 2쪽(최대 100곡)을 받아 같은 곡은 재생 수를 더해 합친다
        val raw = mutableListOf<Popular>()
        for (n in lastFmNames(h)) {
            for (pg in 1..2) {
                val got = fetch(n, pg)
                raw += got
                if (got.size < 50) break
            }
        }
        val groups = mutableListOf<MutableList<Popular>>()
        for (r in raw) {
            val f = TrackMerger.titleForms(r.title)
            val g = groups.firstOrNull { grp -> grp.any { o -> TrackMerger.titleForms(o.title).any(f::contains) || (r.mbid != null && r.mbid == o.mbid) } }
            if (g == null) groups += mutableListOf(r) else g += r
        }
        val merged = groups.map { g -> Triple(g.maxBy { it.playcount }.title, g.firstNotNullOfOrNull { it.mbid }, g.sumOf { it.playcount }) }
            .sortedByDescending { it.third }

        // 우리 곡과 맞춘다: 녹음 MBID → 식별 캐시 → 같은 가수 곡의 제목 표기
        val mine = h.artistId?.let(db::hitsOfArtist).orEmpty()
        val forms = mine.map { it to (TrackMerger.titleForms(it.title) + TrackMerger.titleForms(it.mbTitle)) }
        val byMbid = db.trackIdsByMbids(merged.mapNotNull { it.second?.let { m -> runCatching { UUID.fromString(m) }.getOrNull() } })
        val names = lastFmNames(h)
        val keys = merged.associate { (t, _, _) -> t to names.map { AliasKey.of(t, "$it - Topic") } }
        val byAlias = db.aliasTracks(keys.values.flatten().toSet())
        val ids = merged.map { (t, m, _) ->
            m?.let { runCatching { UUID.fromString(it) }.getOrNull()?.let(byMbid::get) }
                ?: keys[t]!!.firstNotNullOfOrNull(byAlias::get)
                ?: TrackMerger.titleForms(t).let { f -> forms.firstOrNull { (_, hf) -> hf.any(f::contains) }?.first?.trackId }
        }
        val extra = db.hits(ids.filterNotNull().toSet() - mine.map { it.trackId }.toSet()).associateBy { it.trackId }
        val byId: Map<Long, DbTrackHit> = mine.associateBy { it.trackId } + extra
        val items = merged.zip(ids).map { (m, id) ->
            id?.let { byId[it] }?.let { TrackMerger.item(it).copy(artist = h.name, artistId = h.artistId) }
                ?: TrackItem(m.first, h.name, m.second, null, null, null, null, h.artistId)
        }
        val out = merger.dedupe(items)
        cache[key] = Instant.now() to out
        log.info("가수 인기곡: {} 이름 {}개 → {}곡 (우리 곡 {}개)", h.name, names.size, out.size, out.count { it.trackId != null })
        return out
    }

    private fun fetch(artist: String, page: Int): List<Popular> = try {
        val body = fetcher.get()
            .uri { it.path("/internal/lastfm/top-tracks").queryParam("artist", artist).queryParam("limit", 50).queryParam("page", page).build() }
            .retrieve().body(JsonNode::class.java)
        body?.path("items")?.toList().orEmpty().mapNotNull { t ->
            val title = t.path("title").asString("").trim().ifEmpty { return@mapNotNull null }
            Popular(title, t.path("mbid").asString("").ifEmpty { null }, t.path("playcount").asLong(0))
        }
    } catch (e: Exception) {
        log.warn("가수 인기곡 실패: {} {}쪽 {}", artist, page, e.message)
        if (page == 1) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ARTIST_SONGS_UNAVAILABLE", "지금은 인기곡을 불러올 수 없어요. 잠시 뒤 다시 시도해 주세요")
        emptyList()
    }

    /** 방 표시: 내가 담음, 친구가 담음(이름) */
    private fun decorate(ctx: RoomContext, items: List<TrackItem>, rankFrom: Int?): List<ArtistSongItem> {
        val owners = songs.whoHasTracks(ctx.room.id, items.mapNotNull { it.trackId })
        val names = rooms.members(ctx.room.id).associate { it.id to it.nickname }
        return items.mapIndexed { i, t ->
            val who = t.trackId?.let { owners[it] }.orEmpty()
            ArtistSongItem(t, ctx.member.id in who, who.filter { it != ctx.member.id }.mapNotNull(names::get), rankFrom?.let { it + i })
        }
    }
}

@RestController
class ArtistSongsController(private val service: ArtistSongsService) {
    /** 우리 가수 (검색 카드 artistId, 지도 행성) */
    @GetMapping("/api/rooms/{rid}/artists/{artistId}/songs")
    fun byId(
        @PathVariable rid: String, @PathVariable artistId: Long, @RoomMember ctx: RoomContext,
        @RequestParam sort: String?, @RequestParam page: Int?,
    ): ArtistSongsPage = service.songs(ctx, artistId, null, sort, page)

    /** 우리 DB에 없는 가수 (카드 artistId가 null): 이름으로, 인기순만 */
    @GetMapping("/api/rooms/{rid}/artist-songs")
    fun byName(
        @PathVariable rid: String, @RoomMember ctx: RoomContext,
        @RequestParam name: String?, @RequestParam sort: String?, @RequestParam page: Int?,
    ): ArtistSongsPage = service.songs(ctx, null, name, sort, page)
}
