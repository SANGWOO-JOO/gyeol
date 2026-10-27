package dev.gyeol.fetcher.lastfm

import dev.gyeol.fetcher.FetcherProperties
import dev.gyeol.fetcher.RateGate
import dev.gyeol.fetcher.SearchResult
import dev.gyeol.fetcher.TrackHit
import dev.gyeol.fetcher.TtlCache
import dev.gyeol.fetcher.UpstreamException
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * Last.fm 곡 검색 (track.search). A 입구의 즉시 검색에 쓴다.
 * - 속도 제한: Last.fm 이용 조건(IP당 평균 초당 5회 이하)보다 여유 있게 초당 4회
 * - 30개를 받아 [Reranker]로 다시 정렬한 뒤 요청한 개수만 돌려준다
 * - API 키가 없으면 검색만 503으로 거절한다 (fetcher 자체는 MusicBrainz용으로 계속 돈다)
 */
@Component
class LastFmClient(private val props: FetcherProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val gate = RateGate(props.lastfm.minInterval, props.lastfm.maxWait)
    private val cache = TtlCache<String, SearchResult>(props.cache.ttl, props.cache.maxEntries)

    private val http: RestClient = RestClient.builder()
        .baseUrl(props.lastfm.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(props.lastfm.timeout)
                setReadTimeout(props.lastfm.timeout)
            },
        )
        .defaultHeader("User-Agent", props.userAgent)
        .defaultHeader("Accept", "application/json")
        .build()

    fun search(query: String, limit: Int): SearchResult {
        if (props.lastfm.apiKey.isBlank()) throw UpstreamException("Last.fm API 키(LASTFM_API_KEY)가 설정되지 않았습니다")

        val key = "${query.trim().lowercase()}|$limit"
        cache.get(key)?.let { return it.copy(cached = true) }

        gate.acquire()
        val body = try {
            http.get()
                .uri { b ->
                    b.queryParam("method", "track.search")
                        .queryParam("track", query.trim())
                        .queryParam("api_key", props.lastfm.apiKey)
                        .queryParam("format", "json")
                        .queryParam("limit", FETCH_SIZE)
                        .build()
                }
                .retrieve()
                .body(JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            log.warn("Last.fm 응답 오류 {}", e.statusCode.value())
            throw UpstreamException("Last.fm이 ${e.statusCode.value()}을(를) 돌려주었습니다", e)
        } catch (e: Exception) {
            log.warn("Last.fm 호출 실패: {}", e.message)
            throw UpstreamException("Last.fm에 연결하지 못했습니다", e)
        } ?: throw UpstreamException("Last.fm 응답이 비어 있습니다")

        // Last.fm은 오류도 200으로 줄 때가 있다: {"error": 10, "message": "Invalid API key"}
        if (body.has("error")) {
            log.warn("Last.fm 오류 응답 {}: {}", body.path("error").asInt(), body.path("message").asString(""))
            throw UpstreamException("Last.fm 오류 ${body.path("error").asInt()}")
        }

        val hits = parse(body)
        val result = SearchResult(Reranker.rerank(query, hits, limit), "lastfm", cached = false)
        cache.put(key, result)
        return result
    }

    /**
     * 가수·곡으로 검색해 Last.fm이 아는 곡명 표기들을 돌려준다 (예: "좋은날" → "좋은 날").
     * 곡 식별에서 MusicBrainz 제목과 띄어쓰기가 다를 때 쓴다. 키가 없거나 실패하면 빈 목록
     */
    fun spellings(artist: String, title: String): List<String> {
        if (props.lastfm.apiKey.isBlank()) return emptyList()
        return try {
            gate.acquire(Duration.ofSeconds(30))
            val body = http.get()
                .uri { b ->
                    b.queryParam("method", "track.search").queryParam("artist", artist).queryParam("track", title)
                        .queryParam("api_key", props.lastfm.apiKey).queryParam("format", "json").queryParam("limit", 10).build()
                }
                .retrieve().body(JsonNode::class.java)
            body?.path("results")?.path("trackmatches")?.path("track")?.mapNotNull { it.path("name").asString("").trim().ifEmpty { null } }.orEmpty()
        } catch (e: Exception) {
            log.debug("Last.fm 표기 조회 실패: {}", e.message)
            emptyList()
        }
    }

    /**
     * track.getInfo · album.getInfo · artist.getInfo (L3-1 지식 인입). 위키 요약과 태그를 쓴다.
     * 키가 없거나 결과가 없으면 null. 오류 응답이 200으로 올 때도 null
     */
    fun info(method: String, params: Map<String, String>): JsonNode? {
        if (props.lastfm.apiKey.isBlank()) return null
        gate.acquire(Duration.ofSeconds(30))
        val body = try {
            http.get()
                .uri { b -> b.queryParam("method", method).apply { params.forEach { (k, v) -> queryParam(k, v) } }
                    .queryParam("autocorrect", 1).queryParam("api_key", props.lastfm.apiKey).queryParam("format", "json").build() }
                .retrieve().body(JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            throw UpstreamException("Last.fm이 ${e.statusCode.value()}을(를) 돌려주었습니다", e)
        }
        return body?.takeIf { !it.has("error") }
    }

    private fun parse(body: JsonNode): List<TrackHit> =
        body.path("results").path("trackmatches").path("track").mapNotNull { t ->
            val title = t.path("name").asString("").trim()
            val artist = t.path("artist").asString("").trim()
            if (title.isEmpty() || artist.isEmpty()) return@mapNotNull null
            TrackHit(
                title = title,
                artist = artist,
                mbid = t.path("mbid").asString("").takeIf { it.isNotBlank() },
                listeners = t.path("listeners").asString("").toLongOrNull(),
            )
        }

    companion object {
        /** 다시 정렬할 몫까지 넉넉히 받는다 */
        const val FETCH_SIZE = 30
    }
}
