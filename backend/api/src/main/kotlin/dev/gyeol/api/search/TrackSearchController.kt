package dev.gyeol.api.search

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.identify.DbTrackHit
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
)
data class TrackSearchResponse(val items: List<TrackItem>, val source: String)

/**
 * A 입구 곡 검색 (L2-4).
 * 1. 우리 DB: 누군가 식별한 곡을 붙여쓰기·부분·초성·오타·별칭으로 찾는다
 * 2. DB 결과가 limit보다 적으면 external-fetcher의 Last.fm 검색으로 채운다 (같은 곡은 DB 쪽만)
 * Last.fm이 장애여도 DB 결과가 있으면 그것만 돌려준다.
 */
@RestController
class TrackSearchController(props: GyeolProperties, private val db: TrackSearchRepository) {
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
        val query = q?.trim().orEmpty()
        if (query.length !in 2..100) throw ApiException.validation("검색어는 2~100자여야 합니다")
        if (limit !in 1..20) throw ApiException.validation("limit은 1~20이어야 합니다")

        // 확실한 DB 결과는 앞에, 조각 유사도로만 걸린 약한 결과는 Last.fm 뒤에 (DB에 없는 곡을 같은 가수의 다른 곡이 가로채지 않게)
        val hits = db.search(query, limit)
        fun item(h: DbTrackHit) = TrackItem(h.title, h.artist, h.recordingMbid.toString(), null, h.releaseYear, h.trackId, h.coverUrl)
        val strong = hits.filter { it.score >= TrackSearchRepository.STRONG }.map(::item)
        val weak = hits.filter { it.score < TrackSearchRepository.STRONG }.map(::item)
        val local = strong + weak
        if (strong.size >= limit) return TrackSearchResponse(strong, "db")

        val remote = try {
            fetcher.get()
                .uri { it.path("/internal/lastfm/search").queryParam("q", query).queryParam("limit", limit).build() }
                .retrieve()
                .body(TrackSearchResponse::class.java)
                ?.items ?: throw IllegalStateException("빈 응답")
        } catch (e: Exception) {
            log.warn("곡 검색 실패: {}", e.message)
            if (local.isNotEmpty()) return TrackSearchResponse(local, "db")
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "지금은 곡을 검색할 수 없습니다. 잠시 뒤 다시 시도해 주세요")
        }
        if (local.isEmpty()) return TrackSearchResponse(remote, "lastfm")

        // 같은 곡(정규화한 곡명+가수, 또는 같은 mbid)은 DB 쪽만 남긴다
        val seen = local.flatMap { listOf(key(it), it.mbid) }.filterNotNull().toSet()
        val rest = remote.filter { r -> listOfNotNull(key(r), r.mbid).none { it in seen } }
        return TrackSearchResponse((strong + rest + weak).take(limit), "db+lastfm")
    }

    private fun key(t: TrackItem) = "${SearchText.norm(t.title)}|${SearchText.norm(t.artist)}"
}
