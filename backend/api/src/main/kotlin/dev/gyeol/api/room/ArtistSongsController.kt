package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.search.TrackItem
import dev.gyeol.core.identify.TitleNoise
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
/** photo: Commons 사진과 저작자 표시 (I-44, 우리 가수이고 사진이 있을 때만) */
data class ArtistHeader(val artistId: Long?, val name: String, val aliases: List<String>, val photo: PhotoView? = null)

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
    private val faces: ArtistFaces,
    private val releases: dev.gyeol.core.identify.ArtistReleaseRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_PAGES = 5
        private val CACHE_TTL: Duration = Duration.ofMinutes(10)
        private val HANGUL = Regex("""[가-힣]""")
        private val LATIN = Regex("""[A-Za-z]""")
        /** 녹음 목록에 없는 곡은 청취자 수가 1위의 이 비율 이상일 때만 남긴다 (I-41 2차 거르기) */
        const val UNMATCHED_MIN_SHARE = 0.01
        /** 대조할 때 떼어 보는 꼬리: 언어판("-Japanese version-", "- English Version", "(Korean ver.)")과 (feat. …). 팬 리믹스는 떼지 않는다 */
        private val MATCH_SUFFIX = Regex(
            """\s*(?:[-–(\[]\s*)?(?:japanese|english|korean|chinese|spanish|jp|kr|en)\s*(?:ver\.?|version)\s*[-–)\]]?\s*$|\s*[(\[]\s*(?:feat|ft)\.?\s[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )

        /** 녹음 목록 대조용 표기: 제목 표기들 + 언어판·feat.를 뗀 원곡 표기 */
        fun matchForms(title: String): Set<String> = TrackMerger.titleForms(title) + TrackMerger.titleForms(title.replace(MATCH_SUFFIX, "").trim())
    }

    private val fetcher: RestClient = RestClient.builder().baseUrl(props.fetcherUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(Duration.ofSeconds(1)); setReadTimeout(Duration.ofSeconds(5)) })
        .build()

    private data class Popular(val title: String, val mbid: String?, val playcount: Long, val listeners: Long, val artist: String)
    private val cache = ConcurrentHashMap<String, Pair<Instant, List<TrackItem>>>()

    fun songs(ctx: RoomContext, artistId: Long?, name: String?, sort: String?, page: Int?): ArtistSongsPage {
        val p = (page ?: 1).coerceAtLeast(1)
        val header = header(artistId, name).let { h -> h.copy(photo = h.artistId?.let { faces.photo(ctx.room.publicId, it) }) }
        return when (sort ?: "popular") {
            "popular" -> {
                if (p > MAX_PAGES) return ArtistSongsPage(header, "popular", p, PAGE_SIZE, false, emptyList())
                val all = popular(header).take(PAGE_SIZE * MAX_PAGES)
                val slice = all.drop((p - 1) * PAGE_SIZE).take(PAGE_SIZE)
                ArtistSongsPage(header, "popular", p, PAGE_SIZE, p * PAGE_SIZE < all.size && p < MAX_PAGES, decorate(ctx, slice, rankFrom = (p - 1) * PAGE_SIZE + 1))
            }
            "latest" -> {
                // 최신순은 가수 id 주소로만 연다 (I-39, 설계 4-3). 이름 주소는 우리 가수와 맞아도 400
                if (artistId == null) throw ApiException.validation("우리 DB에 없는 가수는 인기순만 볼 수 있어요")
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

    /**
     * 인기순 (I-30 ②, I-36·I-41 거르기). 화면 요청 때 MusicBrainz는 부르지 않는다:
     * 1차 이름 규칙(TitleNoise, 다른 가수 이름) → 2차 저장된 녹음 목록(V27 artist_recording) 대조.
     * 녹음 목록이 없으면 수집만 요청하고 1차만, 수집이 끝나면(캐시 키에 끝난 시각) 다음 요청부터 2차까지
     */
    private fun popular(h: ArtistHeader): List<TrackItem> {
        val scan = h.artistId?.let(releases::scan)
        if (h.artistId != null && (scan == null || scan.status == "FAILED")) latest.requestScanIfNeeded(h.artistId)
        val key = (h.artistId?.let { "id:$it" } ?: "n:${SearchText.norm(h.name)}") + "@" + (scan?.finishedAt ?: "none")
        cache[key]?.takeIf { it.first.isAfter(Instant.now().minus(CACHE_TTL)) }?.let { return it.second }
        if (cache.size > 500) cache.entries.removeIf { it.value.first.isBefore(Instant.now().minus(CACHE_TTL)) }

        // 이름마다 50곡씩 2쪽(최대 100곡)을 받아 같은 곡은 재생 수를 더해 합친다
        val raw = mutableListOf<Popular>()
        // 이 가수의 이름들 (Last.fm이 곡마다 돌려주는 가수 이름이 이 중 하나가 아니면 남의 곡)
        val ownNames = (listOf(h.name) + h.aliases + lastFmNames(h)).map(SearchText::norm).toSet()
        var dropped = 0
        for (n in lastFmNames(h)) {
            for (pg in 1..2) {
                val got = fetch(n, pg)
                // 1차: 직캠·커버·반주·팬 편집·스킷·예능·뉴스 제목, 다른 가수 이름 (I-36·I-41). 쪽 넘김 판단은 거르기 전 개수로
                val kept = got.filter {
                    !TitleNoise.isNoise(it.title) &&
                        (it.artist.isBlank() || SearchText.norm(it.artist) in ownNames || SearchText.norm(it.artist) == SearchText.norm(n))
                }
                dropped += got.size - kept.size
                raw += kept
                if (got.size < 50) break
            }
        }
        val groups = mutableListOf<MutableList<Popular>>()
        for (r in raw) {
            val f = TrackMerger.titleForms(r.title)
            val g = groups.firstOrNull { grp -> grp.any { o -> TrackMerger.titleForms(o.title).any(f::contains) || (r.mbid != null && r.mbid == o.mbid) } }
            if (g == null) groups += mutableListOf(r) else g += r
        }
        var grouped = groups.map { g -> g to g.sumOf { it.listeners } }
        // 2차: 저장된 녹음 목록(판 표시 제외)과 제목 표기가 맞는 곡은 남기고, 안 맞는 곡은 청취자 수가 1위의 2% 이상일 때만
        // 수집이 다 끝났을 때만 대조한다 (모으는 중이면 목록이 일부라 정상 곡을 잘못 뺄 수 있음)
        val recordingForms = h.artistId?.takeIf { scan?.status == "DONE" }?.let { releases.titles(it, withVersions = true) }.orEmpty()
            .flatMap { matchForms(it) }.toSet()
        var unmatchedDropped = 0
        // 제목에 가수 이름이 든 영상식 제목("NewJeans (뉴진스) 'Zero' Official")은 녹음 목록에 없으면 뺀다 ("BTS Cypher"처럼 목록에 있는 곡은 남김)
        val names = listOf(h.name) + h.aliases + lastFmNames(h)
        grouped = grouped.filterNot { (g, _) ->
            g.all { TitleNoise.mentionsArtist(it.title, names) } && g.none { r -> matchForms(r.title).any(recordingForms::contains) }
        }
        if (recordingForms.isNotEmpty()) {
            val top = grouped.maxOfOrNull { it.second } ?: 0L
            val before = grouped.size
            grouped = grouped.filter { (g, listeners) ->
                g.any { r -> matchForms(r.title).any(recordingForms::contains) } || listeners >= top * UNMATCHED_MIN_SHARE
            }
            unmatchedDropped = before - grouped.size
        }
        val merged = grouped.map { (g, _) -> Triple(g.maxBy { it.playcount }.title, g.firstNotNullOfOrNull { it.mbid }, g.sumOf { it.playcount }) }
            .sortedByDescending { it.third }

        // 우리 곡과 맞춘다: 녹음 MBID → 식별 캐시 → 같은 가수 곡의 제목 표기
        val mine = h.artistId?.let(db::hitsOfArtist).orEmpty()
        val forms = mine.map { it to (TrackMerger.titleForms(it.title) + TrackMerger.titleForms(it.mbTitle)) }
        val byMbid = db.trackIdsByMbids(merged.mapNotNull { it.second?.let { m -> runCatching { UUID.fromString(m) }.getOrNull() } })
        val lfNames = lastFmNames(h)
        val keys = merged.associate { (t, _, _) -> t to lfNames.map { AliasKey.of(t, "$it - Topic") } }
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
        log.info(
            "가수 인기곡: {} 이름 {}개 → {}곡 (우리 곡 {}개, 이름 규칙 제외 {}, 녹음 목록 {} 제외 {})",
            h.name, lfNames.size, out.size, out.count { it.trackId != null }, dropped, if (recordingForms.isEmpty()) "없음" else "있음", unmatchedDropped,
        )
        return out
    }

    private fun fetch(artist: String, page: Int): List<Popular> = try {
        val body = fetcher.get()
            .uri { it.path("/internal/lastfm/top-tracks").queryParam("artist", artist).queryParam("limit", 50).queryParam("page", page).build() }
            .retrieve().body(JsonNode::class.java)
        body?.path("items")?.toList().orEmpty().mapNotNull { t ->
            val title = t.path("title").asString("").trim().ifEmpty { return@mapNotNull null }
            Popular(title, t.path("mbid").asString("").ifEmpty { null }, t.path("playcount").asLong(0), t.path("listeners").asLong(0), t.path("artist").asString(""))
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
