package dev.gyeol.worker.gift

import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.GiftStatus
import dev.gyeol.core.room.Role
import dev.gyeol.core.room.RoomRepository
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
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ④ 선물 상자 열기 (가짜 생성): 그림을 S3에 올리고 캐릭터를 저장, 하루 예산을 넘으면 기본 캐릭터, 실패 큐면 FAILED.
 * 큐 리스너는 끄고(role=none) 서비스를 직접 부른다. 상자 규칙(50곡·하루 1개)도 여기서 본다
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=none",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.cover.bucket=gyeol-test-covers",
        "gyeol.worker.gift.daily-image-limit=1000",
    ],
)
class GiftIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3", "sqs")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.aws.endpoint") { localstack.endpoint.toString() }
            registry.add("spring.cloud.aws.region.static") { localstack.region }
            registry.add("spring.cloud.aws.credentials.access-key") { localstack.accessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { localstack.secretKey }
        }

        val s3: S3Client by lazy {
            S3Client.builder().endpointOverride(localstack.endpoint).region(Region.of(localstack.region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey))).build()
        }

        @BeforeAll
        @JvmStatic
        fun bucket() {
            s3.createBucket { it.bucket("gyeol-test-covers") }
        }
    }

    @Autowired lateinit var service: GiftService
    @Autowired lateinit var gifts: GiftRepository
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var llmCalls: LlmCallRepository

    private val today get() = LocalDate.now(ZoneId.of("Asia/Seoul"))

    private fun member(): Long {
        val roomId = rooms.create(UUID.randomUUID().toString().take(10), "선물 방")
        return rooms.addMember(roomId, "상우", Role.HOST, 0, "x")
    }

    /** 곡 수를 직접 맞춘다: room_song 대신 member_progress·상자만 보는 규칙이 아니라 실제 곡 수를 세므로 곡을 넣는다 */
    private fun addSongs(memberId: Long, n: Int, day: LocalDate = today) {
        val roomId = rooms.member(memberId)!!.roomId
        repeat(n) {
            val artist = jdbc.sql("INSERT INTO artist (mbid, name, sort_name, aliases) VALUES (:m, :n, :n, '{}') RETURNING id")
                .param("m", UUID.randomUUID()).param("n", "가수").query(Long::class.java).single()
            val track = jdbc.sql("INSERT INTO track (recording_mbid, title, artist_id) VALUES (:m, '노래', :a) RETURNING id")
                .param("m", UUID.randomUUID()).param("a", artist).query(Long::class.java).single()
            jdbc.sql("INSERT INTO room_song (room_id, member_id, track_id, artist_id) VALUES (:r, :m, :t, :a)")
                .param("r", roomId).param("m", memberId).param("t", track).param("a", artist).update()
            gifts.onSongAdded(memberId, day)
        }
    }

    private fun readyBox(memberId: Long): Long {
        addSongs(memberId, 50)
        val box = gifts.boxes(memberId).single { it.milestone == 50 }
        assertTrue(gifts.startOpening(box.id, memberId))
        return box.id
    }

    @Test
    fun `50곡마다 상자, 최고 기록 기준, 하루 1개만 READY, 다음 날 하나씩`() {
        val m = member()
        addSongs(m, 49)
        assertTrue(gifts.boxes(m).isEmpty())
        addSongs(m, 1)
        val first = gifts.boxes(m).single()
        assertEquals(GiftStatus.READY, first.status)

        // 하나 빼고 다시 담아 50곡을 또 넘어도 상자 없음
        jdbc.sql("DELETE FROM room_song WHERE id = (SELECT max(id) FROM room_song WHERE member_id = :m)").param("m", m).update()
        addSongs(m, 1)
        assertEquals(1, gifts.boxes(m).size)

        // 같은 날 100곡째: WAITING
        addSongs(m, 50)
        val second = gifts.boxes(m).single { it.milestone == 100 }
        assertEquals(GiftStatus.WAITING, second.status)
        val s = gifts.summary(m, today)
        assertEquals(1, s.waiting)
        assertTrue(s.receivedToday)
        assertEquals(150, s.nextMilestone)
        assertEquals(50, s.remaining)

        // 오늘·내일 모두, 안 연 READY 상자가 있으면 올리지 않는다
        assertFalse(gifts.promoteWaiting(m, today))
        assertFalse(gifts.promoteWaiting(m, today.plusDays(1)))
        // 첫 상자를 열면 내일 하나 올라간다, 같은 날 두 번은 안 된다
        assertTrue(gifts.startOpening(first.id, m))
        gifts.finishOpening(first.id, "preset:1", null, "preset", true)
        assertFalse(gifts.promoteWaiting(m, today), "오늘은 이미 받았다")
        assertTrue(gifts.promoteWaiting(m, today.plusDays(1)))
        assertEquals(GiftStatus.READY, gifts.box(second.id)!!.status)
        assertFalse(gifts.promoteWaiting(m, today.plusDays(1)))
    }

    @Test
    fun `상자를 열면 그림을 S3에 올리고 캐릭터를 저장한다, 두 번 받아도 한 번만`() {
        val m = member()
        val boxId = readyBox(m)

        service.open(boxId)

        val box = gifts.box(boxId)!!
        assertEquals(GiftStatus.OPENED, box.status)
        val c = gifts.character(box.characterId!!)!!
        assertFalse(c.isFallback)
        assertEquals("characters/$m/$boxId.svg", c.s3Key)
        assertTrue(s3.getObjectAsBytes { it.bucket("gyeol-test-covers").key(c.s3Key) }.asUtf8String().startsWith("<svg"))

        service.open(boxId)
        assertEquals(1, gifts.characters(m).size)
    }

    @Test
    fun `하루 예산을 넘으면 기본 캐릭터로 연다`() {
        val m = member()
        val boxId = readyBox(m)
        // 오늘 생성 수를 한도까지 채운다
        val limit = 1000
        repeat(limit - gifts.imagesToday(today)) { llmCalls.record("GIFT_IMAGE", LlmUsage("test", 1, 0, 0, 0)) }

        service.open(boxId)

        val c = gifts.character(gifts.box(boxId)!!.characterId!!)!!
        assertTrue(c.isFallback)
        assertTrue(c.s3Key.startsWith("preset:"))
        jdbc.sql("DELETE FROM llm_call WHERE purpose = 'GIFT_IMAGE' AND model = 'test'").update()
    }

    @Test
    fun `실패 큐로 간 상자는 FAILED, 이미 열린 상자는 그대로`() {
        val m = member()
        val boxId = readyBox(m)
        service.fail(boxId)
        assertEquals(GiftStatus.FAILED, gifts.box(boxId)!!.status)
        assertNull(gifts.box(boxId)!!.characterId)
    }
}
