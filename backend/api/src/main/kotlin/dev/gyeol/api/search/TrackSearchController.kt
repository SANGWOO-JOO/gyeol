package dev.gyeol.api.search

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.time.Duration

/** mbid는 Last.fm 결과에 없을 수 있다 (L2에서 MusicBrainz로 채움) */
data class TrackItem(val title: String, val artist: String, val mbid: String?, val listeners: Long?, val releaseYear: Int?)
data class TrackSearchResponse(val items: List<TrackItem>, val source: String)

/** A 입구 곡 검색. external-fetcher의 Last.fm 검색(다시 정렬 포함)을 부른다. 우리 DB 먼저 검색은 L2에서. */
@RestController
class TrackSearchController(props: GyeolProperties) {
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

        return try {
            fetcher.get()
                .uri { it.path("/internal/lastfm/search").queryParam("q", query).queryParam("limit", limit).build() }
                .retrieve()
                .body(TrackSearchResponse::class.java)
                ?: throw IllegalStateException("빈 응답")
        } catch (e: Exception) {
            log.warn("곡 검색 실패: {}", e.message)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_UNAVAILABLE", "지금은 곡을 검색할 수 없습니다. 잠시 뒤 다시 시도해 주세요")
        }
    }
}
