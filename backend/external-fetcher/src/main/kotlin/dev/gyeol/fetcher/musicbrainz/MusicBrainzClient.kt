package dev.gyeol.fetcher.musicbrainz

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

/**
 * MusicBrainz 곡(recording) 검색. 응답이 0.8~12초로 들쭉날쭉해 즉시 검색에는 쓰지 않고,
 * L2의 백그라운드 곡 식별에 쓴다 (docs/l1-design.md 4-2장).
 * - 모든 호출은 [RateGate]를 거친다 (초당 1회)
 * - 최근 검색 결과는 메모리에 잠깐 기억한다
 * - 같은 제목·가수의 여러 녹음(라이브·재발매)은 점수가 높은 하나만 남긴다
 */
@Component
class MusicBrainzClient(private val props: FetcherProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val gate = RateGate(props.musicbrainz.minInterval, props.musicbrainz.maxWait)
    private val cache = TtlCache<String, SearchResult>(props.cache.ttl, props.cache.maxEntries)

    private val http: RestClient = RestClient.builder()
        .baseUrl(props.musicbrainz.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(props.musicbrainz.timeout)
                setReadTimeout(props.musicbrainz.timeout)
            },
        )
        .defaultHeader("User-Agent", props.userAgent)
        .defaultHeader("Accept", "application/json")
        .build()

    private val slowHttp: RestClient = RestClient.builder()
        .baseUrl(props.musicbrainz.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(props.musicbrainz.timeout)
                setReadTimeout(props.musicbrainz.identifyTimeout)
            },
        )
        .defaultHeader("User-Agent", props.userAgent)
        .defaultHeader("Accept", "application/json")
        .build()

    /**
     * 곡 식별용 원시 조회 (`/artist`, `/recording`, `/release` …). 즉시 검색과 같은 속도 제한을 함께 쓰되 오래 기다릴 수 있다.
     * MusicBrainz가 바쁘다고(503) 하면 늦춰 가며 두 번 더 시도한다.
     */
    fun fetch(path: String, params: Map<String, Any>): JsonNode {
        var last: Exception? = null
        repeat(3) { attempt ->
            gate.acquire(props.musicbrainz.identifyMaxWait)
            try {
                return slowHttp.get()
                    .uri { b -> b.path(path).apply { params.forEach { (k, v) -> queryParam(k, v) } }.queryParam("fmt", "json").build() }
                    .retrieve()
                    .body(JsonNode::class.java) ?: throw UpstreamException("MusicBrainz 응답이 비어 있습니다")
            } catch (e: RestClientResponseException) {
                if (e.statusCode.value() != 503) throw UpstreamException("MusicBrainz가 ${e.statusCode.value()}을(를) 돌려주었습니다", e)
                last = e
            } catch (e: UpstreamException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        log.warn("MusicBrainz 식별 조회 실패 {}: {}", path, last?.message)
        throw UpstreamException("MusicBrainz에 연결하지 못했습니다", last)
    }

    fun search(query: String, limit: Int): SearchResult {
        val key = "${query.trim().lowercase()}|$limit"
        cache.get(key)?.let { return it.copy(cached = true) }

        gate.acquire()
        val body = try {
            http.get()
                .uri { b ->
                    b.path("/recording")
                        .queryParam("query", escape(query.trim()))
                        .queryParam("dismax", "true")
                        .queryParam("limit", limit * 2)
                        .queryParam("fmt", "json")
                        .build()
                }
                .retrieve()
                .body(JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            log.warn("MusicBrainz 응답 오류 {}", e.statusCode.value())
            throw UpstreamException("MusicBrainz가 ${e.statusCode.value()}을(를) 돌려주었습니다", e)
        } catch (e: Exception) {
            log.warn("MusicBrainz 호출 실패: {}", e.message)
            throw UpstreamException("MusicBrainz에 연결하지 못했습니다", e)
        } ?: throw UpstreamException("MusicBrainz 응답이 비어 있습니다")

        val result = SearchResult(parse(body).take(limit), "musicbrainz", cached = false)
        cache.put(key, result)
        return result
    }

    private fun parse(body: JsonNode): List<TrackHit> {
        val seen = HashSet<String>()
        return body.path("recordings").mapNotNull { r ->
            val mbid = r.path("id").asString("")
            val title = r.path("title").asString("")
            val artist = r.path("artist-credit").joinToString("") { c -> c.path("name").asString("") + c.path("joinphrase").asString("") }.trim()
            if (mbid.isBlank() || title.isBlank()) return@mapNotNull null
            if (!seen.add("${title.lowercase()}|${artist.lowercase()}")) return@mapNotNull null
            val year = r.path("first-release-date").asString("").take(4).toIntOrNull()
            TrackHit(title = title, artist = artist, mbid = mbid, releaseYear = year)
        }
    }

    /** Lucene 특수 문자를 이스케이프해 사용자가 입력한 글자가 검색 문법으로 해석되지 않게 한다 */
    fun escape(q: String): String = q.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)"""), """\\$1""")
}
