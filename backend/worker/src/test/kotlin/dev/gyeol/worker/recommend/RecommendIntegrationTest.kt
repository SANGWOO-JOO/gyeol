package dev.gyeol.worker.recommend

import com.sun.net.httpserver.HttpServer
import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.core.knowledge.VectorRepository
import dev.gyeol.core.listen.ListenEvent
import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.listen.Source
import dev.gyeol.core.recommend.RecommendRepository
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.Tokens
import dev.gyeol.core.upload.UploadRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** L3-4 추천: PROFILE → 후보(Last.fm 곡 + DB 벡터 이웃) → 후보 식별·보강 → RANK → 저장 (가짜 fetcher·embed·LLM) */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=recommend",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.recommend.queue=gyeol-test-recommend",
        "gyeol.worker.recommend.dlq=gyeol-test-recommend-dlq",
        "gyeol.worker.parse-dlq=gyeol-test-parse-dlq",
        "gyeol.worker.identify.dlq=gyeol-test-identify-dlq",
        "gyeol.worker.enrich.dlq=gyeol-test-enrich-dlq",
    ],
)
class RecommendIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("sqs")

        private fun json(ex: com.sun.net.httpserver.HttpExchange, s: String) {
            val b = s.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }

        private val fetcher = HttpServer.create(InetSocketAddress(0), 0).apply {
            // 곡 단위 비슷한 곡: 새 곡 하나 + 이미 들은 곡(빠져야 함) + MusicBrainz에 없는 곡
            createContext("/internal/lastfm/similar") { ex ->
                if (ex.requestURI.path.endsWith("similar-artists")) return@createContext json(ex, """{"items":[]}""")
                json(ex, """{"items":[{"title":"새 곡","artist":"다른 가수","mbid":null,"match":0.9},{"title":"곡A","artist":"가수","mbid":null,"match":0.8},{"title":"없는 곡","artist":"누구","mbid":null,"match":0.7}]}""")
            }
            createContext("/internal/lastfm/top-tracks") { ex -> json(ex, """{"items":[]}""") }
            createContext("/internal/musicbrainz/identify") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                if (!body.contains("새 곡")) return@createContext json(ex, """{"status":"NOT_FOUND"}""")
                json(ex, """{"status":"MATCHED","recording":{"mbid":"${UUID.randomUUID()}","title":"새 곡"},
                    "artist":{"mbid":"66666666-6666-6666-6666-666666666666","name":"다른 가수","sortName":null,"aliases":[]},
                    "release":{"mbid":"${UUID.randomUUID()}","groupMbid":"${UUID.randomUUID()}","title":"새 음반","date":"2021-01-01","type":"Album"}}""")
            }
            createContext("/internal/knowledge/") { ex ->
                val type = ex.requestURI.path.removePrefix("/internal/knowledge/").substringBefore('/')
                json(ex, if (type == "artist") """{"chunks":[{"source":"WIKIPEDIA","lang":"ko","url":"https://ko.wikipedia.org/wiki/x","license":"CC BY-SA 4.0","text":"발라드 가수."},{"source":"LASTFM","lang":"en","url":"https://www.last.fm/music/x","license":"CC BY-SA (Last.fm wiki)","text":"Last.fm 태그: ballad."}],"credits":[],"workIds":[]}"""
                else """{"chunks":[],"credits":[],"workIds":[]}""")
            }
            createContext("/embed") { ex ->
                val n = Regex("\"texts\":\\[(.*?)\\]").find(ex.requestBody.readAllBytes().decodeToString())!!.groupValues[1].split("\",\"").size
                json(ex, """{"vectors":[${(0 until n).joinToString(",") { (0 until 384).joinToString(",", "[", "]") { d -> if (d == 0) "1.0" else "0.0" } }}],"dim":384,"model":"fake-e5"}""")
            }
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.aws.endpoint") { localstack.endpoint.toString() }
            registry.add("spring.cloud.aws.region.static") { localstack.region }
            registry.add("spring.cloud.aws.credentials.access-key") { localstack.accessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { localstack.secretKey }
            registry.add("gyeol.worker.identify.fetcher-url") { "http://localhost:${fetcher.address.port}" }
            registry.add("gyeol.worker.embed.url") { "http://localhost:${fetcher.address.port}" }
        }

        @BeforeAll
        @JvmStatic
        fun queues() {
            SqsClient.builder().endpointOverride(localstack.endpoint).region(Region.of(localstack.region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey))).build().use { sqs ->
                    listOf("gyeol-test-recommend", "gyeol-test-recommend-dlq", "gyeol-test-parse-dlq", "gyeol-test-identify-dlq", "gyeol-test-enrich-dlq")
                        .forEach { q -> sqs.createQueue { it.queueName(q) } }
                }
        }
    }

    @Autowired lateinit var trigger: RecommendTrigger
    @Autowired lateinit var repo: RecommendRepository
    @Autowired lateinit var identify: IdentifyRepository
    @Autowired lateinit var vectors: VectorRepository
    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var uploads: UploadRepository
    @Autowired lateinit var listens: ListenEventRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val artist = ArtistInfo(UUID.randomUUID(), "가수", "가수", emptyList())
    private fun track(title: String, a: ArtistInfo = artist) =
        identify.upsertTrack(TrackInfo(UUID.randomUUID(), title, a, UUID.randomUUID(), UUID.randomUUID(), "$title 음반", "2020-01-01", "Single"))
    private fun unit(d: Int) = FloatArray(384) { if (it == d) 1f else 0f }

    @Test
    fun `들은 곡은 빼고 Last fm 후보와 벡터 이웃으로 추천을 만든다`() {
        val a = track("곡A"); val b = track("곡B")
        val near = track("이웃 곡", ArtistInfo(UUID.randomUUID(), "이웃 가수", null, emptyList()))
        listOf(a, b, near).forEach { vectors.save(it, "fake-e5", unit(0), "h$it") }

        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, Instant.now().plus(30, ChronoUnit.DAYS))
        val uploadId = UUID.randomUUID()
        uploads.create(uploadId, sid, "uploads/$uploadId.json")
        listOf(a to "곡A", b to "곡B").forEach { (id, title) ->
            listens.insertBatch(sid, uploadId, listOf(ListenEvent("v", title, "가수 - Topic", Instant.now(), Source.YOUTUBE)), Instant.now().plus(7, ChronoUnit.DAYS))
            jdbc.sql("UPDATE listen_event SET track_id = :t WHERE session_id = :s AND title = :ti").param("t", id).param("s", sid).param("ti", title).update()
        }

        trigger.start(sid)
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (repo.recommendations(sid).isEmpty() && System.nanoTime() < deadline) Thread.sleep(300)

        val recs = repo.recommendations(sid)
        assertEquals(mapOf("READY" to 2, "FAILED" to 1), repo.candidates(sid).groupingBy { it.status }.eachCount(), repo.candidates(sid).toString())
        val names = repo.trackNames(recs.map { it.trackId })
        assertEquals(setOf("새 곡", "이웃 곡"), recs.map { names[it.trackId]!!.first }.toSet())
        assertTrue(recs.none { it.trackId == a || it.trackId == b }, "들은 곡은 추천하지 않는다")
        assertEquals(listOf(1, 2), recs.map { it.rank })
        // 새 곡: Last.fm 0.9 + 벡터 1.0 → 이웃 곡(벡터만)보다 위
        assertEquals("새 곡", names[recs[0].trackId]!!.first)
        // 가짜 LLM은 근거 청크가 있는 곡에만 이유를 쓰고, 근거는 그 곡에 준 청크
        assertTrue(recs[0].reason != null && recs[0].evidence.isNotEmpty())
    }
}
