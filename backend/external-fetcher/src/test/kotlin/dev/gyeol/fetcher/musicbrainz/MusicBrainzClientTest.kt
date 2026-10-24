package dev.gyeol.fetcher.musicbrainz

import com.sun.net.httpserver.HttpServer
import dev.gyeol.fetcher.FetcherProperties
import dev.gyeol.fetcher.UpstreamException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 진짜 MusicBrainz 대신 가짜 서버를 세워 요청 모양·응답 해석·캐시를 확인한다 */
class MusicBrainzClientTest {

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Pair<String, String>>() // (User-Agent, 쿼리 문자열)
    private var status = 200

    private val body = """
        {"recordings": [
          {"id": "11111111-1111-1111-1111-111111111111", "title": "Rain", "score": 100,
           "first-release-date": "2016-02-03",
           "artist-credit": [{"name": "태연", "joinphrase": ""}]},
          {"id": "22222222-2222-2222-2222-222222222222", "title": "Rain", "score": 90,
           "artist-credit": [{"name": "태연", "joinphrase": ""}]},
          {"id": "33333333-3333-3333-3333-333333333333", "title": "Duet Song", "score": 80,
           "first-release-date": "2020",
           "artist-credit": [{"name": "가수 A", "joinphrase": " & "}, {"name": "가수 B", "joinphrase": ""}]}
        ]}
    """.trimIndent()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/ws/2/recording") { ex ->
                requests += ex.requestHeaders.getFirst("User-Agent") to URLDecoder.decode(ex.requestURI.rawQuery, Charsets.UTF_8)
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (status == 200) bytes.size.toLong() else -1)
                if (status == 200) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun client() = MusicBrainzClient(
        FetcherProperties(
            contact = "https://github.com/SANGWOO-JOO",
            musicbrainz = FetcherProperties.MusicBrainz(
                baseUrl = "http://localhost:${server.address.port}/ws/2",
                minInterval = Duration.ofMillis(10),
            ),
        ),
    )

    @Test
    fun `연락처가 담긴 User-Agent와 단순 검색 모드로 요청한다`() {
        client().search("태연 Rain", 5)
        val (ua, query) = requests.single()
        assertEquals("gyeol/0.1.0 ( https://github.com/SANGWOO-JOO )", ua)
        assertTrue("query=태연 Rain" in query)
        assertTrue("dismax=true" in query)
        assertTrue("fmt=json" in query)
    }

    @Test
    fun `같은 제목·가수 녹음은 하나만 남기고 가수 이름을 이어 붙인다`() {
        val items = client().search("rain", 5).items
        assertEquals(2, items.size)
        assertEquals("11111111-1111-1111-1111-111111111111", items[0].mbid)
        assertEquals(2016, items[0].releaseYear)
        assertEquals("가수 A & 가수 B", items[1].artist)
        assertEquals(2020, items[1].releaseYear)
    }

    @Test
    fun `같은 검색은 기억해 두었다가 다시 부르지 않는다`() {
        val c = client()
        val first = c.search("Rain", 5)
        val second = c.search("  rain ", 5)
        assertEquals(1, requests.size)
        assertEquals(false, first.cached)
        assertEquals(true, second.cached)
    }

    @Test
    fun `검색 문법 문자는 이스케이프한다`() {
        client().search("AC/DC (Live)", 5)
        assertTrue("""AC\/DC \(Live\)""" in requests.single().second)
    }

    @Test
    fun `MusicBrainz 오류는 UpstreamException으로 바꾼다`() {
        status = 503
        assertFailsWith<UpstreamException> { client().search("rain", 5) }
    }
}
