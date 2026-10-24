package dev.gyeol.fetcher.lastfm

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 진짜 Last.fm 대신 가짜 서버로 요청 모양·응답 해석·오류 처리를 확인한다 */
class LastFmClientTest {

    private lateinit var server: HttpServer
    private val queries = mutableListOf<String>()
    private var body = """
        {"results": {"trackmatches": {"track": [
          {"name": "NewJeans (뉴진스) 'Hype Boy' Official MV", "artist": "HYBE LABELS", "listeners": "900", "mbid": ""},
          {"name": "Hype Boy", "artist": "NewJeans", "listeners": "500000", "mbid": "4b2c0c40-0000-4000-8000-000000000000"},
          {"name": "Hype Boy", "artist": "NewJeans (뉴진스)", "listeners": "1200", "mbid": ""}
        ]}}}
    """.trimIndent()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/2.0/") { ex ->
                queries += URLDecoder.decode(ex.requestURI.rawQuery, Charsets.UTF_8)
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun client(apiKey: String = "test-key") = LastFmClient(
        FetcherProperties(
            contact = "https://github.com/SANGWOO-JOO",
            lastfm = FetcherProperties.LastFm(
                apiKey = apiKey,
                baseUrl = "http://localhost:${server.address.port}/2.0/",
                minInterval = Duration.ofMillis(5),
            ),
        ),
    )

    @Test
    fun `track_search를 30개로 요청한다`() {
        client().search("뉴진스 Hype Boy", 5)
        val q = queries.single()
        listOf("method=track.search", "track=뉴진스 Hype Boy", "api_key=test-key", "format=json", "limit=30").forEach {
            assertTrue(it in q, "요청에 '$it'이(가) 있어야 합니다: $q")
        }
    }

    @Test
    fun `다시 정렬해 원곡을 1위로 올리고 같은 곡은 합친다`() {
        val items = client().search("뉴진스 Hype Boy", 5).items
        // 검색어의 한글 이름과 맞는 "NewJeans (뉴진스)" 표기가 1위, 같은 곡인 "NewJeans" 항목과 합쳐진다
        assertEquals("Hype Boy" to "NewJeans (뉴진스)", items[0].title to items[0].artist)
        assertEquals("4b2c0c40-0000-4000-8000-000000000000", items[0].mbid, "합쳐진 항목의 곡 ID를 넘겨받는다")
        assertEquals(500000L, items[0].listeners, "청취자 수는 가장 큰 값")
        assertEquals(2, items.size, "같은 곡은 하나로 합치고 MV만 따로 남는다")
        assertNull(items[1].mbid, "빈 mbid는 null로")
    }

    @Test
    fun `200으로 온 오류 응답도 실패로 처리한다`() {
        body = """{"error": 10, "message": "Invalid API key - You must be granted a valid key by last.fm"}"""
        assertFailsWith<UpstreamException> { client().search("rain", 5) }
    }

    @Test
    fun `API 키가 없으면 호출하지 않고 거절한다`() {
        assertFailsWith<UpstreamException> { client(apiKey = "").search("rain", 5) }
        assertEquals(0, queries.size)
    }

    @Test
    fun `같은 검색은 기억해 두었다가 다시 부르지 않는다`() {
        val c = client()
        c.search("Rain", 5)
        assertTrue(c.search(" rain ", 5).cached)
        assertEquals(1, queries.size)
    }
}
