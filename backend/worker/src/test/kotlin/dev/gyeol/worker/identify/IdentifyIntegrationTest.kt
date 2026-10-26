package dev.gyeol.worker.identify

import com.sun.net.httpserver.HttpServer
import dev.gyeol.core.identify.IdentifyProgress
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.listen.ListenEvent
import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.listen.Source
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.SessionStatus
import dev.gyeol.core.session.Tokens
import dev.gyeol.core.upload.UploadRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** L2-1 식별 파이프라인: 계획 → identify 큐 → 가짜 fetcher → 저장·연결, 전역 캐시, 실패 큐 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=identify,cover",
        "gyeol.worker.cover.queue=gyeol-test-cover",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.parse-dlq=gyeol-test-parse-dlq",
        "gyeol.worker.identify.queue=gyeol-test-identify",
        "gyeol.worker.identify.dlq=gyeol-test-identify-dlq",
        "gyeol.worker.identify.top-n=3",
        "gyeol.worker.identify.batch=2",
        "gyeol.worker.busy-retry=1s",
    ],
)
class IdentifyIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3", "sqs")

        /** 가짜 fetcher: "가수A - 곡1"만 찾고 나머지는 못 찾음. down이면 503 */
        val fetcherCalls = CopyOnWriteArrayList<String>()
        val coverCalls = CopyOnWriteArrayList<String>()
        @Volatile var down = false
        private val fetcher = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/internal/musicbrainz/identify") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                fetcherCalls += body
                val (code, res) = when {
                    down -> 503 to """{"code":"UPSTREAM_UNAVAILABLE","message":"down"}"""
                    body.contains("가수A") && body.contains("곡1") -> 200 to """
                        {"status":"MATCHED","recording":{"mbid":"11111111-1111-1111-1111-111111111111","title":"곡1"},
                         "artist":{"mbid":"22222222-2222-2222-2222-222222222222","name":"Singer A","sortName":"Singer A","aliases":["가수A"]},
                         "release":{"mbid":"33333333-3333-3333-3333-333333333333","groupMbid":"44444444-4444-4444-4444-444444444444","title":"첫 앨범","date":"2020-01-01","type":"Album"},
                         "calls":2,"ms":10}""".trimIndent()
                    else -> 200 to """{"status":"NOT_FOUND","reason":"RECORDING","calls":2,"ms":10}"""
                }
                val bytes = res.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            createContext("/internal/coverart/front") { ex ->
                coverCalls += ex.requestBody.readAllBytes().decodeToString()
                val bytes = """{"url":"https://coverartarchive.org/release/33333333-3333-3333-3333-333333333333/front-250","from":"RELEASE","ms":5}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
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
        }

        val sqs: SqsClient by lazy {
            SqsClient.builder().endpointOverride(localstack.endpoint).region(Region.of(localstack.region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey))).build()
        }
        lateinit var parseDlqUrl: String

        /** 실제 구성처럼 identify 큐 + 실패 큐(2번 실패하면, 시험이라 짧게) */
        @BeforeAll
        @JvmStatic
        fun queues() {
            parseDlqUrl = sqs.createQueue { it.queueName("gyeol-test-parse-dlq") }.queueUrl()
            sqs.createQueue { it.queueName("gyeol-test-cover") }
            val dlqUrl = sqs.createQueue { it.queueName("gyeol-test-identify-dlq") }.queueUrl()
            val dlqArn = sqs.getQueueAttributes { it.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN) }.attributes()[QueueAttributeName.QUEUE_ARN]
            sqs.createQueue {
                it.queueName("gyeol-test-identify").attributes(
                    mapOf(
                        QueueAttributeName.VISIBILITY_TIMEOUT to "2",
                        QueueAttributeName.REDRIVE_POLICY to """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"2"}""",
                    ),
                )
            }
        }
    }

    @Autowired lateinit var planner: IdentifyPlanner
    @Autowired lateinit var repo: IdentifyRepository
    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var uploads: UploadRepository
    @Autowired lateinit var listens: ListenEventRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun reset() {
        down = false
        fetcherCalls.clear()
        coverCalls.clear()
        jdbc.sql("DELETE FROM title_alias").update() // 전역 캐시를 비워 테스트 순서와 무관하게
    }

    /** 곡1(Topic) 5회, 곡2(Topic) 3회, 브이로그(일반 영상) 2회, 곡3 1회 → 상위 3곡이 대상 */
    private fun session(): Long {
        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, Instant.now().plus(30, ChronoUnit.DAYS))
        val uploadId = UUID.randomUUID()
        uploads.create(uploadId, sid, "uploads/$uploadId.json")
        val t0 = Instant.parse("2026-10-01T00:00:00Z")
        fun ev(title: String, channel: String, source: Source, n: Int) = (0 until n).map { ListenEvent("v", title, channel, t0.plusSeconds(it * 60L), source) }
        val events = ev("곡1", "가수A - Topic", Source.YOUTUBE, 5) + ev("곡2", "가수B - Topic", Source.YOUTUBE, 3) +
            ev("브이로그", "어떤 채널", Source.YOUTUBE, 2) + ev("곡3", "가수C - Topic", Source.MUSIC_APP, 1)
        listens.insertBatch(sid, uploadId, events, Instant.now().plus(7, ChronoUnit.DAYS))
        return sid
    }

    private fun waitDone(sid: Long, timeout: Duration = Duration.ofSeconds(30)): IdentifyProgress {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val p = repo.progress(sid)
            if (p.done + p.failed == p.target) return p
            Thread.sleep(200)
        }
        error("시간 안에 끝나지 않았습니다: ${repo.progress(sid)}")
    }

    private fun linked(sid: Long, title: String) =
        jdbc.sql("SELECT count(*) FROM listen_event WHERE session_id = :sid AND title = :t AND track_id IS NOT NULL")
            .param("sid", sid).param("t", title).query(Long::class.java).single()

    @Test
    fun `상위 N곡을 식별해 곡에 연결하고, 같은 곡은 두 번째 세션에서 캐시로 끝낸다`() {
        val first = session()
        val plan = planner.plan(first)
        assertEquals(3, plan.targets) // 곡3(1회)은 상위 3곡 밖
        assertEquals(0, plan.cacheHits)
        assertEquals(2, plan.messages) // 2곡씩 묶음

        assertEquals(IdentifyProgress(target = 3, done = 3, matched = 1, notFound = 2, failed = 0), waitDone(first))
        assertEquals(5L, linked(first, "곡1"))
        assertEquals(0L, linked(first, "곡2"))
        // 일반 영상은 규칙으로 못 풀어 fetcher를 부르지 않는다 (L2-3 LLM 몫)
        assertEquals(2, fetcherCalls.size)
        assertEquals("Singer A", jdbc.sql("SELECT a.name FROM track t JOIN artist a ON a.id = t.artist_id").query(String::class.java).single())

        // 찾은 곡은 표지 큐를 거쳐 원곡 음반 표지 주소가 붙는다 (L2-2)
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        fun cover() = jdbc.sql("SELECT cover_url FROM track").query { rs, _ -> rs.getString(1) }.single()
        while (cover() == null && System.nanoTime() < deadline) Thread.sleep(200)
        assertEquals("https://coverartarchive.org/release/33333333-3333-3333-3333-333333333333/front-250", cover())
        assertEquals(1, coverCalls.size)
        assert(coverCalls.single().contains("첫 앨범"))

        // 두 번째 세션: 세 곡 모두 캐시 적중 (못 찾음도 30일 동안은 다시 묻지 않음)
        fetcherCalls.clear()
        val second = session()
        val plan2 = planner.plan(second)
        assertEquals(3, plan2.cacheHits)
        assertEquals(0, plan2.messages)
        assertEquals(IdentifyProgress(3, 3, 1, 2, 0), repo.progress(second))
        assertEquals(5L, linked(second, "곡1"))
        assertEquals(0, fetcherCalls.size)
        assertEquals(1, coverCalls.size) // 표지도 곡마다 한 번만 확인

        // 계획을 다시 돌려도 같은 결과
        assertEquals(0, planner.plan(second).messages)
    }

    @Test
    fun `일반 영상 제목은 LLM(가짜)으로 정리해 같은 곡에 연결하고, 곡이 아닌 영상은 이유를 남긴다`() {
        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, Instant.now().plus(30, ChronoUnit.DAYS))
        val uploadId = UUID.randomUUID()
        uploads.create(uploadId, sid, "uploads/$uploadId.json")
        val t0 = Instant.parse("2026-10-01T00:00:00Z")
        fun ev(title: String, channel: String, n: Int) = (0 until n).map { ListenEvent("v", title, channel, t0.plusSeconds(it * 60L), Source.YOUTUBE) }
        listens.insertBatch(sid, uploadId, ev("[MV] 가수A _ 곡1", "1theK", 4) + ev("잠잘 때 듣는 노래 모음 playlist", "누군가", 3), Instant.now().plus(7, ChronoUnit.DAYS))

        planner.plan(sid)
        assertEquals(IdentifyProgress(2, 2, 1, 1, 0), waitDone(sid))
        assertEquals(4L, linked(sid, "[MV] 가수A _ 곡1"))
        assertEquals(1, fetcherCalls.size) // 플레이리스트는 MusicBrainz를 부르지 않는다
        val reasons = jdbc.sql("SELECT method || ':' || coalesce(reason, '-') FROM title_alias ORDER BY alias_key").query { rs, _ -> rs.getString(1) }.list()
        assertEquals(setOf("LLM:-", "LLM:NOT_SONG"), reasons.toSet())
    }

    @Test
    fun `fetcher가 계속 실패하면 재시도 후 실패 큐로 가고, 곡은 FAILED로 표시된다`() {
        down = true
        val sid = session()
        planner.plan(sid)
        val p = waitDone(sid, Duration.ofSeconds(60))
        // 묶음 단위로 다시 시도하므로, fetcher가 필요 없는 일반 영상(LLM 몫)도 같은 묶음이면 함께 실패로 끝난다
        assertEquals(0, p.done)
        assertEquals(3, p.failed)
        assert(fetcherCalls.size >= 2) { "실패 큐로 가기 전에 다시 시도해야 합니다" }
    }

    @Test
    fun `parse 실패 큐에 온 업로드는 세션을 FAILED로 바꾼다`() {
        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, Instant.now().plus(30, ChronoUnit.DAYS))
        sessions.markUploading(sid, 10, 5, 5)
        val id = UUID.randomUUID()
        uploads.create(id, sid, "uploads/$id.json")
        sqs.sendMessage { it.queueUrl(parseDlqUrl).messageBody("""{"Records":[{"s3":{"bucket":{"name":"b"},"object":{"key":"uploads/$id.json"}}}]}""") }

        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (sessions.findById(sid)!!.status != SessionStatus.FAILED && System.nanoTime() < deadline) Thread.sleep(200)
        val s = sessions.findById(sid)!!
        assertEquals(SessionStatus.FAILED, s.status)
        assert(s.errorMessage!!.contains("반복"))
        assertNull(uploads.findByS3Key("uploads/없음.json"))
    }
}
