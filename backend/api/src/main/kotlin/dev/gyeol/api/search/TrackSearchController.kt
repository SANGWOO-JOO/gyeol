package dev.gyeol.api.search

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.identify.SearchText
import dev.gyeol.core.identify.TrackSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * mbid는 Last.fm 결과에 없을 수 있다. [trackId]·[coverUrl]은 우리 DB에서 찾은 곡에만 있다 (L2-4).
 * trackId가 있는 곡을 고르면 식별 없이 바로 연결된다.
 */
data class TrackItem(
    val title: String,
    val artist: String,
    val mbid: String?,
    val listeners: Long?,
    val releaseYear: Int?,
    val trackId: Long? = null,
    val coverUrl: String? = null,
    /** 우리 가수 id (DB 곡이거나 Last.fm 가수 이름이 우리 가수 한 명과 맞을 때, I-30) */
    val artistId: Long? = null,
)
data class TrackSearchResponse(val items: List<TrackItem>, val source: String)

/**
 * A 입구 곡 검색 (L2-4).
 * 1. 우리 DB: 누군가 식별한 곡을 붙여쓰기·부분·초성·오타·별칭으로 찾는다
 * 2. DB 결과가 limit보다 적으면 external-fetcher의 Last.fm 검색으로 채운다 (같은 곡은 DB 쪽만)
 * Last.fm이 장애여도 DB 결과가 있으면 그것만 돌려준다.
 */
@RestController
class TrackSearchController(props: GyeolProperties, private val db: TrackSearchRepository, private val merger: TrackMerger) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val fetcher: RestClient = RestClient.builder()
        .baseUrl(props.fetcherUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(1))
                setReadTimeout(Duration.ofSeconds(3)) // 3초 안에 답이 없으면 503
            },
        )
        .build()

    @GetMapping("/api/tracks/search")
    fun search(@RequestParam q: String?, @RequestParam(defaultValue = "10") limit: Int): TrackSearchResponse {
        if (limit !in 1..20) throw ApiException.validation("limit은 1~20이어야 합니다")
        return find(q, limit)
    }

    /** 검색 본체. 방 검색 더보기(2~5쪽)는 [limit] 50으로 부른다 (fetcher Last.fm 상한도 50) */
    fun find(q: String?, limit: Int, allowSingleCjk: Boolean = false): TrackSearchResponse {
        val query = q?.trim().orEmpty()
        // 방 검색만 한글·한자·가나 한 글자를 허용한다("비" 같은 가수). 곡 고르기 입구(/api/tracks/search)는 2~100자 그대로
        val single = allowSingleCjk && SearchText.isSingleCjk(query)
        if (!single && query.length !in 2..100) throw ApiException.validation("검색어는 2~100자여야 합니다")
        require(limit in 1..MAX_LIMIT)

        // 확실한 DB 결과는 앞에, 조각 유사도로만 걸린 약한 결과는 Last.fm 뒤에 (DB에 없는 곡을 같은 가수의 다른 곡이 가로채지 않게)
        val hits = db.search(query, limit)
        val strongHits = hits.filter { it.score >= TrackSearchRepository.STRONG }
        val weakHits = hits.filter { it.score < TrackSearchRepository.STRONG }
        val strong = strongHits.map(TrackMerger::item)
        val local = strong + weakHits.map(TrackMerger::item)
        if (strong.size >= limit) return TrackSearchResponse(merger.unifyArtistNames(strong), "db")

        val remote = try {
            fetcher.get()
                .uri { it.path("/internal/lastfm/search").queryParam("q", query).queryParam("limit", limit).build() }
                .retrieve()
                .body(TrackSearchResponse::class.java)
                ?.items ?: throw IllegalStateException("빈 응답")
        } catch (e: Exception) {
            log.warn("곡 검색 실패: {}", e.message)
            if (local.isNotEmpty()) return TrackSearchResponse(merger.unifyArtistNames(local), "db")
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "지금은 곡을 검색할 수 없습니다. 잠시 뒤 다시 시도해 주세요")
        }.let(::cleanRemote)
        // 가수 묶기·같은 곡 합치기 (I-30 ①): 같은 곡은 DB 쪽만, Last.fm 가수 이름은 우리 가수 표기로
        val merged = merger.merge(strongHits, weakHits, remote, limit)
        return TrackSearchResponse(merged, if (local.isEmpty() && merged.none { it.trackId != null }) "lastfm" else "db+lastfm")
    }

    companion object {
        const val MAX_LIMIT = 50

        /** "Clairo • Lossless", "가수 • Recommended for you" 처럼 스트리밍 앱 문구가 붙은 가수명 (11-13 갈래 A 발견) */
        private val ARTIST_NOISE = Regex("""\s+•\s+.*$""")

        fun key(t: TrackItem) = "${SearchText.norm(t.title)}|${SearchText.norm(t.artist)}"

        /**
         * Last.fm 결과 정리: 가수명의 " • 잡음"을 잘라 내고, 정리 뒤 같은 (곡, 가수)는 하나로 합친다.
         * 남기는 쪽: mbid가 있는 것 → 청취자가 많은 것. 순서는 처음 나온 자리를 따른다.
         * (잡음이 붙은 가수명으로 담으면 식별이 실패했다. 곡 고르기 검색과 방 검색이 같은 경로를 쓴다)
         */
        fun cleanRemote(items: List<TrackItem>): List<TrackItem> {
            // 가수 이름이 영상 제목인 결과("성시경(Sungsikyung)의 킬링보이스를 라이브로!")는 뺀다 (I-41)
            val cleaned = items.filterNot { dev.gyeol.core.identify.TitleNoise.isVideoLikeArtist(it.artist) }
                .map { it.copy(artist = it.artist.replace(ARTIST_NOISE, "").trim().ifEmpty { it.artist }) }
            val best = linkedMapOf<String, TrackItem>()
            for (t in cleaned) {
                val k = key(t)
                val old = best[k]
                if (old == null || better(t, old)) best[k] = t
            }
            return best.values.toList()
        }

        private fun better(a: TrackItem, b: TrackItem): Boolean =
            if ((a.mbid != null) != (b.mbid != null)) a.mbid != null else (a.listeners ?: 0) > (b.listeners ?: 0)
    }
}
