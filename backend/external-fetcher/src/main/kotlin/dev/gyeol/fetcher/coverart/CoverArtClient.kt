package dev.gyeol.fetcher.coverart

import dev.gyeol.fetcher.FetcherProperties
import dev.gyeol.fetcher.RateGate
import dev.gyeol.fetcher.UpstreamException
import dev.gyeol.fetcher.musicbrainz.MusicBrainzClient
import dev.gyeol.fetcher.musicbrainz.TitleMatch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class CoverRequest(val releaseMbid: String?, val releaseGroupMbid: String?, val releaseTitle: String?)
data class CoverResult(val url: String?, val from: String?, val ms: Long)

/**
 * 원곡 음반의 앞표지 주소 (docs/l1-design.md 4-3).
 * 1. 그 음반의 앞표지 → 2. 같은 묶음 안에서 제목이 같은 다른 판 → 3. 없음
 * 다른 음반의 표지로 대신하지 않는다 (묶음 표지로 대신했더니 봄날에 WINGS 표지가 나왔음).
 * 이미지는 받지 않고 주소만 돌려준다. 브라우저가 Cover Art Archive에서 직접 불러온다.
 */
@Component
class CoverArtClient(private val props: FetcherProperties, private val musicBrainz: MusicBrainzClient) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val gate = RateGate(props.coverart.minInterval, props.coverart.maxWait)
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(props.coverart.timeout).build()

    private val finder = CoverFinder(::hasFront, { group -> musicBrainz.fetch("/release", mapOf("release-group" to group, "limit" to 50)) }, props.coverart.baseUrl)

    fun front(req: CoverRequest): CoverResult = finder.find(req)

    /**
     * 앞표지가 실제로 열리는지. Cover Art Archive는 이미지 주소(Internet Archive)로 넘겨 주는데(3xx),
     * 목록에는 있어도 넘겨받은 주소가 500을 주는 경우가 있어(봄날 YNWA 2026-10-26) 끝까지 따라가 이미지인지 본다.
     * 404는 표지 없음, 500 등은 그 판을 쓰지 않음 (같은 이름의 다른 판으로 넘어감)
     */
    private fun hasFront(releaseMbid: String): Boolean {
        repeat(3) { attempt ->
            gate.acquire()
            val res = try {
                http.send(HttpRequest.newBuilder(URI(urlOf(releaseMbid))).method("HEAD", HttpRequest.BodyPublishers.noBody()).header("User-Agent", props.userAgent).timeout(props.coverart.timeout).build(), HttpResponse.BodyHandlers.discarding())
            } catch (e: Exception) {
                log.debug("Cover Art Archive 호출 실패: {}", e.message)
                null
            }
            val code = res?.statusCode()
            when {
                code in 200..299 -> return res!!.headers().firstValue("Content-Type").orElse("").startsWith("image/")
                code == 404 -> return false
                code != null && code >= 500 && res.uri().host != URI(props.coverart.baseUrl).host -> return false // 이미지 저장소 쪽 오류: 이 판은 건너뜀
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        throw UpstreamException("Cover Art Archive에 연결하지 못했습니다")
    }

    private fun urlOf(releaseMbid: String) = CoverFinder.urlOf(props.coverart.baseUrl, releaseMbid)

    /** 표지 이미지를 내려받는다 (worker가 S3에 저장). Cover Art Archive 주소만 받는다. 없으면 null, 일시 장애면 예외 */
    fun image(url: String): CoverImage? {
        require(url.startsWith(props.coverart.baseUrl + "/")) { "Cover Art Archive 주소가 아닙니다" }
        gate.acquire()
        val res = try {
            http.send(HttpRequest.newBuilder(URI(url)).GET().header("User-Agent", props.userAgent).timeout(IMAGE_TIMEOUT).build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            throw UpstreamException("표지 이미지를 받지 못했습니다", e)
        }
        val type = res.headers().firstValue("Content-Type").orElse("")
        return when {
            res.statusCode() in 200..299 && type.startsWith("image/") -> CoverImage(res.body(), type)
            res.statusCode() == 404 -> null
            else -> throw UpstreamException("표지 이미지 응답 ${res.statusCode()}")
        }
    }

    private companion object {
        val IMAGE_TIMEOUT: Duration = Duration.ofSeconds(90)
    }
}

class CoverImage(val bytes: ByteArray, val contentType: String)

/** 표지 규칙만 (외부 호출은 함수로 받아 시험에서 바꿔 끼운다) */
class CoverFinder(
    private val hasFront: (releaseMbid: String) -> Boolean,
    private val browseGroup: (releaseGroupMbid: String) -> JsonNode,
    private val baseUrl: String = "https://coverartarchive.org",
) {
    fun find(req: CoverRequest): CoverResult {
        val started = System.nanoTime()
        fun done(url: String?, from: String?) = CoverResult(url, from, (System.nanoTime() - started) / 1_000_000)
        val release = req.releaseMbid?.takeIf { it.isNotBlank() } ?: return done(null, null)
        if (hasFront(release)) return done(urlOf(baseUrl, release), "RELEASE")

        val group = req.releaseGroupMbid?.takeIf { it.isNotBlank() } ?: return done(null, null)
        // 같은 묶음 안에서는 표기 차이("Hug (포옹)"과 "HUG", 문장부호·공백)를 같은 음반으로 본다
        val title = req.releaseTitle.orEmpty()
        val others = browseGroup(group).path("releases").toList()
            .filter { it.path("id").asString() != release && TitleMatch.sameTitle(it.path("title").asString(""), title) && it.path("cover-art-archive").path("front").asBoolean(false) }
            .take(5)
        for (o in others) {
            val id = o.path("id").asString()
            if (hasFront(id)) return done(urlOf(baseUrl, id), "SAME_TITLE_RELEASE")
        }
        return done(null, null)
    }

    companion object {
        fun urlOf(baseUrl: String, releaseMbid: String) = "$baseUrl/release/$releaseMbid/front-250"
    }
}
