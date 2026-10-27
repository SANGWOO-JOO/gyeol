package dev.gyeol.worker.enrich

import com.sun.net.httpserver.HttpServer
import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.core.knowledge.KnowledgeRepository
import dev.gyeol.core.listen.ListenEvent
import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.listen.Source
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/** L3-1 지식 인입: 계획 → enrich 큐 → 가짜 fetcher → 청크·크레딧 저장, 대상별 캐시 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=enrich",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.enrich.queue=gyeol-test-enrich",
        "gyeol.worker.enrich.dlq=gyeol-test-enrich-dlq",
        "gyeol.worker.parse-dlq=gyeol-test-parse-dlq",
        "gyeol.worker.identify.dlq=gyeol-test-identify-dlq",
    ],
)
class EnrichIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3", "sqs")

        val calls = CopyOnWriteArrayList<String>()
        private val WORK = "44444444-4444-4444-4444-444444444444"
        private val fetcher = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/internal/knowledge/") { ex ->
                val path = ex.requestURI.path.removePrefix("/internal/knowledge/")
                calls += path.substringBefore('/')
                val type = path.substringBefore('/')
                val body = when (type) {
                    "recording" -> """{"chunks":[{"source":"MUSICBRAINZ","lang":"ko","url":"https://musicbrainz.org/recording/x","license":"CC0","text":"녹음 (MusicBrainz): 편곡 편곡가."}],
                        "credits":[{"role":"ARRANGER","artistMbid":null,"name":"편곡가"}],"workIds":["$WORK"]}"""
                    "work" -> """{"chunks":[{"source":"MUSICBRAINZ","lang":"ko","url":"https://musicbrainz.org/work/x","license":"CC0","text":"곡 (MusicBrainz): 작곡 작곡가."}],
                        "credits":[{"role":"COMPOSER","artistMbid":"55555555-5555-5555-5555-555555555555","name":"작곡가"}],"workIds":[]}"""
                    "artist" -> """{"chunks":[{"source":"WIKIPEDIA","lang":"ko","url":"https://ko.wikipedia.org/wiki/x","license":"CC BY-SA 4.0","text":"가수 소개."},
                        {"source":"LASTFM","lang":"en","url":"https://www.last.fm/music/x","license":"CC BY-SA (Last.fm wiki)","text":"Last.fm 태그: k-pop."}],"credits":[],"workIds":[]}"""
                    else -> """{"chunks":[],"credits":[],"workIds":[]}""" // 음반은 지식이 없는 경우
                }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
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

        @BeforeAll
        @JvmStatic
        fun queues() {
            SqsClient.builder().endpointOverride(localstack.endpoint).region(Region.of(localstack.region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey))).build().use { sqs ->
                    listOf("gyeol-test-enrich", "gyeol-test-enrich-dlq", "gyeol-test-parse-dlq", "gyeol-test-identify-dlq").forEach { q -> sqs.createQueue { it.queueName(q) } }
                }
        }
    }

    @Autowired lateinit var service: EnrichService
    @Autowired lateinit var knowledge: KnowledgeRepository
    @Autowired lateinit var identify: IdentifyRepository
    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var uploads: UploadRepository
    @Autowired lateinit var listens: ListenEventRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val artist = ArtistInfo(UUID.randomUUID(), "가수", "가수", emptyList())
    private fun track(title: String) = identify.upsertTrack(TrackInfo(UUID.randomUUID(), title, artist, UUID.randomUUID(), UUID.randomUUID(), "$title 음반", "2020-01-01", "Single"))

    /** 두 곡을 들은 세션 (같은 가수) */
    private fun session(tracks: List<Long>): Long {
        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, Instant.now().plus(30, ChronoUnit.DAYS))
        val uploadId = UUID.randomUUID()
        uploads.create(uploadId, sid, "uploads/$uploadId.json")
        tracks.forEachIndexed { i, id ->
            listens.insertBatch(sid, uploadId, (0..i).map { ListenEvent("v", "곡$i", "가수 - Topic", Instant.now(), Source.YOUTUBE) }, Instant.now().plus(7, ChronoUnit.DAYS))
            jdbc.sql("UPDATE listen_event SET track_id = :t WHERE session_id = :s AND title = :ti").param("t", id).param("s", sid).param("ti", "곡$i").update()
        }
        return sid
    }

    private fun waitDone(sid: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val p = knowledge.progress(sid)
            if (p.target > 0 && p.done + p.failed == p.target) return
            Thread.sleep(200)
        }
        error("시간 안에 끝나지 않았습니다: ${knowledge.progress(sid)}")
    }

    private fun count(sql: String) = jdbc.sql(sql).query(Long::class.java).single()

    @Test
    fun `곡을 보강해 청크·크레딧을 저장하고, 같은 가수·작품은 한 번만, 두 번째 세션은 캐시로 끝난다`() {
        val a = track("곡A"); val b = track("곡B")
        val first = session(listOf(a, b))
        service.plan(first)
        waitDone(first)

        // 녹음 2, 작품 1(공유), 음반 2, 가수 1 → fetcher 6번
        assertEquals(mapOf("recording" to 2, "work" to 1, "release-group" to 2, "artist" to 1), calls.groupingBy { it }.eachCount())
        assertEquals(2L, count("SELECT count(*) FROM knowledge_chunk WHERE entity_type = 'TRACK'"))
        assertEquals(2L, count("SELECT count(*) FROM knowledge_chunk WHERE entity_type = 'ARTIST'")) // 위키·Last.fm
        assertEquals(0L, count("SELECT count(*) FROM knowledge_chunk WHERE entity_type = 'RELEASE_GROUP'"))
        assertEquals(2L, count("SELECT count(*) FROM knowledge_fetch WHERE entity_type = 'RELEASE_GROUP' AND status = 'EMPTY'")) // 없음도 기록
        // 곡마다 편곡(녹음) + 작곡(작품) 크레딧
        assertEquals(4L, count("SELECT count(*) FROM track_credit"))
        assertEquals(listOf(UUID.fromString(WORK)), knowledge.trackRefs(a)!!.workMbids)
        // 곡 설명 (가짜 LLM): 가수 Last.fm 태그를 장르로, 근거는 가수 단위라 신뢰도 낮음
        val genres = jdbc.sql("SELECT fields -> 'genres' ->> 'confidence' || ':' || (fields -> 'genres' -> 'value' ->> 0) FROM track_descriptor WHERE track_id = :t").param("t", a).query(String::class.java).single()
        assertEquals("LOW:k-pop", genres)
        assertEquals(2L, count("SELECT count(*) FROM track_descriptor"))

        calls.clear()
        val second = session(listOf(a, b))
        service.plan(second)
        waitDone(second)
        assertEquals(0, calls.size, "90일 안에 가져온 대상은 다시 묻지 않는다")
        assertEquals(2L, count("SELECT count(*) FROM track_descriptor")) // 지식이 그대로면 설명도 다시 만들지 않는다
    }
}
