package dev.gyeol.worker.room

import com.sun.net.httpserver.HttpServer
import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.Role
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.RoomSongRepository
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
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 음악 방 담기 식별 (docs/todo/room-api-db.md 4절): 세션 묶음과 같은 식별 규칙으로 찾고, 찾으면 바로 방에 담는다.
 * 큐 리스너는 끄고(role에 room 없음) 서비스를 직접 부른다. 가짜 fetcher는 "가수A - 곡1"만 찾는다.
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=none",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.cover.queue=gyeol-test-cover",
        "gyeol.worker.room.song-queue=gyeol-test-room-song",
    ],
)
class RoomSongIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3", "sqs")

        private val fetcher = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/internal/musicbrainz/identify") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                val res = if (body.contains("가수A") && body.contains("곡1")) """
                    {"status":"MATCHED","recording":{"mbid":"11111111-1111-1111-1111-111111111111","title":"곡1"},
                     "artist":{"mbid":"22222222-2222-2222-2222-222222222222","name":"Singer A","sortName":"Singer A","aliases":["가수A"]},
                     "release":{"mbid":"33333333-3333-3333-3333-333333333333","groupMbid":"44444444-4444-4444-4444-444444444444","title":"첫 앨범","date":"2020-01-01","type":"Album"},
                     "calls":2,"ms":10}""".trimIndent()
                else """{"status":"NOT_FOUND","reason":"RECORDING","calls":2,"ms":10}"""
                val bytes = res.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
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
        lateinit var songQueueUrl: String

        @BeforeAll
        @JvmStatic
        fun queues() {
            sqs.createQueue { it.queueName("gyeol-test-cover") }
            songQueueUrl = sqs.createQueue { it.queueName("gyeol-test-room-song") }.queueUrl()
        }
    }

    @Autowired lateinit var service: RoomSongService
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var songs: RoomSongRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var identifyRepo: IdentifyRepository
    @Autowired lateinit var gifts: GiftRepository

    private val today get() = LocalDate.now(ZoneId.of("Asia/Seoul"))

    data class Setup(val roomId: Long, val memberId: Long)

    /** 방 하나 + 참가자 하나, 오늘 1곡을 센 상태 (API가 요청을 만들 때 센 것과 같게) */
    private fun setup(): Setup {
        val roomId = rooms.create(UUID.randomUUID().toString().take(10), "시험 방")
        val memberId = rooms.addMember(roomId, "상우", Role.HOST, 0, "x")
        return Setup(roomId, memberId)
    }

    private fun request(s: Setup, title: String, artist: String): Long {
        songs.incrementDaily(s.memberId, today, 100)
        return songs.createRequest(s.roomId, s.memberId, title, artist, null)
    }

    private fun mapRev(roomId: Long) = rooms.findById(roomId)!!.mapRev

    @Test
    fun `찾으면 바로 방에 담고 지도 rev를 올리며, 곡 설명은 뒤로 보낸다`() {
        val s = setup()
        val id = request(s, "곡1", "가수A")
        val rev = mapRev(s.roomId)
        service.identifyRequest(id)

        val r = songs.request(id)!!
        assertEquals("DONE", r.status)
        assertTrue(songs.has(s.roomId, s.memberId, r.trackId!!))
        assertEquals(r.roomSongId, songs.mySongs(s.memberId, null, 10).single().id)
        assertEquals(rev + 1, mapRev(s.roomId))
        assertEquals(1, songs.daily(s.memberId, today))
        // 다른 시험도 같은 큐에 보내므로 이 곡의 메시지가 있는지만 본다
        val enrich = sqs.receiveMessage { it.queueUrl(songQueueUrl).maxNumberOfMessages(10).waitTimeSeconds(5) }.messages()
            .map { json.readTree(it.body()) }.filter { it["type"].asString() == "ENRICH" }
        assertTrue(enrich.any { m -> m["trackIds"].toList().map { it.asLong() } == listOf(r.trackId) })

        // 같은 메시지를 다시 받아도 한 번만 처리
        service.identifyRequest(id)
        assertEquals(1, songs.countOf(s.memberId))
    }

    @Test
    fun `못 찾으면 FAILED, 하루 한도에서 뺀다`() {
        val s = setup()
        val id = request(s, "없는 노래", "없는 가수")
        service.identifyRequest(id)
        val r = songs.request(id)!!
        assertEquals("FAILED", r.status)
        assertEquals("NOT_FOUND", r.failReason)
        assertNull(r.roomSongId)
        assertEquals(0, songs.daily(s.memberId, today))
        assertEquals(0, songs.countOf(s.memberId))
    }

    @Test
    fun `이미 담은 곡이면 새로 넣지 않고 그 곡을 돌려주며 한도에서 뺀다`() {
        val s = setup()
        service.identifyRequest(request(s, "곡1", "가수A"))
        val again = request(s, "곡1", "가수A")
        service.identifyRequest(again)
        val r = songs.request(again)!!
        assertEquals("DONE", r.status)
        assertEquals("ALREADY_ADDED", r.failReason)
        assertEquals(songs.mySongs(s.memberId, null, 10).single().id, r.roomSongId)
        assertEquals(1, songs.daily(s.memberId, today))
    }

    @Test
    fun `식별하는 사이 내보내진 사람의 곡은 담지 않는다`() {
        val s = setup()
        val id = request(s, "곡1", "가수A")
        rooms.remove(s.memberId)
        service.identifyRequest(id)
        assertEquals("MEMBER_REMOVED", songs.request(id)!!.failReason)
        assertEquals(0, songs.countOf(s.memberId))
    }

    @Test
    fun `실패 큐로 간 요청은 FAILED로 끝나 화면이 찾는 중에서 멈추지 않는다`() {
        val s = setup()
        val id = request(s, "곡1", "가수A")
        service.fail(id, "ERROR")
        assertEquals("FAILED", songs.request(id)!!.status)
        assertEquals(0, songs.daily(s.memberId, today))
        service.fail(id, "ERROR") // 두 번 와도 한도를 두 번 빼지 않는다
        assertEquals(0, songs.daily(s.memberId, today))
    }

    @Test
    fun `식별로 담은 곡이 50곡째면 선물 상자가 생긴다 (API 바로 담기와 같은 규칙)`() {
        val s = setup()
        repeat(49) { i ->
            val a = ArtistInfo(UUID.randomUUID(), "가수 $i", "가수 $i", emptyList())
            val t = identifyRepo.upsertTrack(TrackInfo(UUID.randomUUID(), "노래 $i", a, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single"))
            songs.add(s.roomId, s.memberId, t, songs.trackArtist(t)!!)
            gifts.onSongAdded(s.memberId, today)
        }
        assertTrue(gifts.boxes(s.memberId).isEmpty())

        service.identifyRequest(request(s, "곡1", "가수A"))

        val box = gifts.boxes(s.memberId).single()
        assertEquals(50, box.milestone)
        assertEquals("READY", box.status.name)
        assertEquals(today, box.readyOn)
    }
}
