package dev.gyeol.api

import dev.gyeol.core.listen.ListenEvent
import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.listen.Source
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.Tokens
import dev.gyeol.core.upload.UploadRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
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
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PostgreSQL + LocalStack을 컨테이너로 띄워 API 전체 흐름을 확인한다 (parse 워커는 ⑦에서, 여기서는 DB에 직접 넣어 흉내 냄) */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionApiIntegrationTest {

    companion object {
        private const val BUCKET = "gyeol-test-uploads"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.aws.endpoint") { localstack.endpoint.toString() }
            registry.add("spring.cloud.aws.region.static") { localstack.region }
            registry.add("spring.cloud.aws.credentials.access-key") { localstack.accessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { localstack.secretKey }
            registry.add("spring.cloud.aws.s3.path-style-access-enabled") { "true" }
            registry.add("gyeol.upload.bucket") { BUCKET }
        }

        fun s3(): S3Client = S3Client.builder()
            .endpointOverride(localstack.endpoint)
            .region(Region.of(localstack.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)))
            .forcePathStyle(true)
            .build()

        @BeforeAll
        @JvmStatic
        fun createBucket() {
            s3().use { it.createBucket { b -> b.bucket(BUCKET) } }
        }
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var uploads: UploadRepository
    @Autowired lateinit var listens: ListenEventRepository

    private val http = HttpClient.newHttpClient()

    private fun call(method: String, path: String, body: Any? = null): Pair<Int, JsonNode?> {
        val req = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body().takeIf { it.isNotBlank() }?.let { json.readTree(it) }
    }

    @Test
    fun `청취 기록 업로드 세션 - 업로드 주소 발급부터 통계와 삭제까지`() {
        val (created, session) = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT"))
        assertEquals(201, created)
        val token = session!!["token"].asString()
        assertEquals(43, token.length)
        assertEquals("CREATED", session["status"].asString())

        // 브라우저가 필터를 거쳐 올릴 파일
        val file = json.writeValueAsBytes(
            mapOf(
                "schemaVersion" to 1,
                "items" to listOf(mapOf("videoId" to "abc", "title" to "노래", "channel" to "가수 - Topic", "watchedAt" to "2026-10-24T09:00:00Z", "source" to "MUSIC_APP")),
            ),
        )
        val (issued, ticket) = call(
            "POST", "/api/sessions/$token/upload-url",
            mapOf("totalCount" to 10, "musicCount" to 3, "unclassifiedCount" to 5, "byteSize" to file.size),
        )
        assertEquals(200, issued)
        val uploadId = UUID.fromString(ticket!!["uploadId"].asString())

        // 서명 URL로 S3에 직접 올린다 (브라우저와 같은 방식)
        val put = HttpRequest.newBuilder(URI(ticket["url"].asString()))
            .header("Content-Type", ticket["headers"]["content-type"]?.asString() ?: ticket["headers"]["Content-Type"].asString())
            .PUT(HttpRequest.BodyPublishers.ofByteArray(file))
            .build()
        assertEquals(200, http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode())

        val (_, uploading) = call("GET", "/api/sessions/$token")
        assertEquals("UPLOADING", uploading!!["status"].asString())
        assertEquals(3, uploading["counts"]["music"].asInt())

        // 같은 세션에 두 번째 업로드 주소는 안 된다
        assertEquals(409, call("POST", "/api/sessions/$token/upload-url", mapOf("totalCount" to 1, "musicCount" to 0, "unclassifiedCount" to 0, "byteSize" to 1)).first)
        // 처리 전에는 통계를 줄 수 없다
        assertEquals("NOT_READY", call("GET", "/api/sessions/$token/stats").second!!["code"].asString())

        // parse 워커 흉내: 청취 기록 저장 후 DONE
        val sid = sessions.findActiveByTokenHash(Tokens.hash(token))!!.id
        val base = Instant.parse("2026-10-24T00:00:00Z")
        val events = (0 until 30).map { i ->
            ListenEvent("v${i % 4}", "노래 ${i % 4}", if (i % 2 == 0) "가수 A - Topic" else "가수 A", base.plus(i.toLong(), ChronoUnit.HOURS), if (i % 3 == 0) Source.MUSIC_APP else Source.YOUTUBE)
        }
        listens.insertBatch(sid, uploadId, events, Instant.now().plus(7, ChronoUnit.DAYS))
        uploads.markProcessed(uploadId, events.size)
        sessions.markDone(sid, events.size, events.first().watchedAt, events.last().watchedAt)

        val (ok, stats) = call("GET", "/api/sessions/$token/stats")
        assertEquals(200, ok)
        assertEquals(30, stats!!["totals"]["plays"].asInt())
        assertEquals(24, stats["byHour"].size())
        assertEquals(7, stats["byWeekday"].size())
        // " - Topic"을 떼고 같은 가수로 합친다
        assertEquals("가수 A", stats["topChannels"][0]["channel"].asString())
        assertEquals(30, stats["topChannels"][0]["plays"].asInt())
        assertEquals(10, stats["bySource"]["musicApp"].asInt())

        // 삭제하면 S3 파일과 세션이 함께 사라진다
        assertEquals(204, call("DELETE", "/api/sessions/$token").first)
        assertFailsWith<NoSuchKeyException> { s3().use { it.headObject { h -> h.bucket(BUCKET).key("uploads/$uploadId.json") } } }
        assertEquals(404, call("GET", "/api/sessions/$token").first)
    }

    @Test
    fun `곡 고르기 세션 - 3~5곡 저장`() {
        val token = call("POST", "/api/sessions", mapOf("entry" to "SEEDS")).second!!["token"].asString()
        val tracks = (1..3).map { mapOf("mbid" to UUID.randomUUID().toString(), "title" to "곡 $it", "artist" to "가수") }

        assertEquals("VALIDATION", call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to tracks.take(2))).second!!["code"].asString())
        assertEquals(200, call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to tracks)).first)

        val (_, view) = call("GET", "/api/sessions/$token")
        assertEquals("DONE", view!!["status"].asString())
        assertEquals(3, view["seeds"].size())
        assertEquals("INVALID_STATE", call("GET", "/api/sessions/$token/stats").second!!["code"].asString())
        // 같은 세션에 다시 저장할 수 없다
        assertEquals(409, call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to tracks)).first)
    }

    @Test
    fun `잘못된 요청과 없는 세션`() {
        assertEquals(400, call("POST", "/api/sessions", mapOf("entry" to "OTHER")).first)
        assertEquals(404, call("GET", "/api/sessions/짧은토큰").first)
        assertEquals(404, call("GET", "/api/sessions/${Tokens.newToken()}").first)

        val token = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT")).second!!["token"].asString()
        val tooBig = mapOf("totalCount" to 1, "musicCount" to 1, "unclassifiedCount" to 0, "byteSize" to 21L * 1024 * 1024)
        assertEquals("TOO_LARGE", call("POST", "/api/sessions/$token/upload-url", tooBig).second!!["code"].asString())
        val wrongSum = mapOf("totalCount" to 1, "musicCount" to 1, "unclassifiedCount" to 1, "byteSize" to 10)
        assertEquals("VALIDATION", call("POST", "/api/sessions/$token/upload-url", wrongSum).second!!["code"].asString())
        // 곡 고르기 세션이 아니면 seeds 불가
        assertEquals("INVALID_STATE", call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to emptyList<Any>())).second!!["code"].asString())
        assertTrue(true)
    }
}
