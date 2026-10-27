package dev.gyeol.fetcher.knowledge

import dev.gyeol.fetcher.FetcherProperties
import dev.gyeol.fetcher.RateGate
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.URLEncoder
import java.time.Duration

data class WikiSummary(val lang: String, val title: String, val text: String, val url: String)

/**
 * Wikidata에서 위키백과 문서 제목을 찾고, Wikipedia REST로 첫 요약 단락만 받는다 (CC BY-SA 4.0, 출처 표시).
 * Wikimedia 정책대로 앱을 알리는 User-Agent를 보내고, 여유 있게 초당 2회로 제한한다 (값은 추정).
 */
@Component
class WikiClient(props: FetcherProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val gate = RateGate(Duration.ofMillis(500), Duration.ofSeconds(60))
    private val http = RestClient.builder()
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(Duration.ofSeconds(5)); setReadTimeout(Duration.ofSeconds(10)) })
        .defaultHeader("User-Agent", props.userAgent)
        .defaultHeader("Accept", "application/json")
        .build()

    /** 위키데이터 항목(Q…)의 위키백과 문서 제목: 언어 → 제목 */
    fun sitelinks(qid: String, langs: List<String>): Map<String, String> {
        val body = get("https://www.wikidata.org/wiki/Special:EntityData/$qid.json") ?: return emptyMap()
        val links = body.path("entities").path(qid).path("sitelinks")
        return langs.mapNotNull { l -> links.path("${l}wiki").path("title").asString("").ifEmpty { null }?.let { l to it } }.toMap()
    }

    fun summary(lang: String, title: String): WikiSummary? {
        val t = URLEncoder.encode(title.replace(' ', '_'), Charsets.UTF_8).replace("+", "%20")
        val body = get("https://$lang.wikipedia.org/api/rest_v1/page/summary/$t") ?: return null
        if (body.path("type").asString("") == "disambiguation") return null
        val text = Chunks.limit(Chunks.clean(body.path("extract").asString("")))
        if (text.length < 20) return null
        val url = body.path("content_urls").path("desktop").path("page").asString("").ifEmpty { "https://$lang.wikipedia.org/wiki/$t" }
        return WikiSummary(lang, body.path("title").asString(title), text, url)
    }

    private fun get(url: String): JsonNode? {
        gate.acquire()
        return try {
            http.get().uri(java.net.URI(url)).retrieve().body(JsonNode::class.java)
        } catch (e: HttpClientErrorException.NotFound) {
            null
        } catch (e: Exception) {
            log.debug("위키 조회 실패 {}: {}", url, e.message)
            throw e
        }
    }
}
