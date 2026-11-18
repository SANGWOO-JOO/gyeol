package dev.gyeol.worker.codematch

import dev.gyeol.core.room.CodeMatchRepository
import dev.gyeol.core.room.Role
import dev.gyeol.core.room.RoomRepository
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ⑤ 코드 맞추기 이유 (가짜 LLM): 근거 있는 설명이 있으면 DONE + 근거 번호, 없으면 NO_REASON, 실패 큐도 NO_REASON */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=none",
        "gyeol.worker.cleanup.enabled=false",
    ],
)
class CodeMatchReasonIntegrationTest {

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
    }

    @Autowired lateinit var service: CodeMatchReasonService
    @Autowired lateinit var matches: CodeMatchRepository
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var jdbc: JdbcClient

    private fun artist(name: String, describedWith: List<Long>?): Long {
        val id = jdbc.sql("INSERT INTO artist (mbid, name, sort_name, aliases) VALUES (:m, :n, :n, '{}') RETURNING id")
            .param("m", UUID.randomUUID()).param("n", name).query(Long::class.java).single()
        if (describedWith != null) {
            jdbc.sql("INSERT INTO artist_profile (artist_id, description, evidence, described_at) VALUES (:a, '몽환적인 기타 인디 록을 한다.', :e, now())")
                .param("a", id).param("e", describedWith.toTypedArray()).update()
        }
        return id
    }

    private fun song(roomId: Long, memberId: Long, artistId: Long) {
        val t = jdbc.sql("INSERT INTO track (recording_mbid, title, artist_id) VALUES (:m, '노래', :a) RETURNING id")
            .param("m", UUID.randomUUID()).param("a", artistId).query(Long::class.java).single()
        jdbc.sql("INSERT INTO room_song (room_id, member_id, track_id, artist_id) VALUES (:r, :m, :t, :a)")
            .param("r", roomId).param("m", memberId).param("t", t).param("a", artistId).update()
    }

    /** 두 사람이 같은 가수를 한 명 담은 방 + 저장된 점수 (이유 쓰는 중) */
    private fun setup(shared: Long): Long {
        val roomId = rooms.create(UUID.randomUUID().toString().take(10), "코드 방")
        val a = rooms.addMember(roomId, "상우", Role.HOST, 0, "x")
        val b = rooms.addMember(roomId, "민지", Role.MEMBER, 1, "x")
        song(roomId, a, shared); song(roomId, b, shared)
        val parts = """{"genre":0.5,"distance":0.5,"artist":1.0,"song":0.0,"sharedArtists":["가수"],"sharedGalaxies":["몽환 기타 인디"],"sharedSongs":0}"""
        return matches.insert(roomId, minOf(a, b), maxOf(a, b), UUID.randomUUID().toString().replace("-", "").repeat(2).take(64), 60, parts)!!
    }

    @Test
    fun `겹친 가수 설명에 근거가 있으면 이유를 쓰고 근거 번호를 남긴다, 두 번 받아도 그대로`() {
        val id = setup(artist("인디 가수", listOf(77L, 78L)))
        service.write(id)
        val row = matches.get(id)!!
        assertEquals("DONE", row.status)
        assertNotNull(row.reason)
        assertEquals("상우님과 민지님은 몽환 기타 인디 쪽에서 취향이 닿아요.", row.reason, "받침과 무관한 조사")
        assertEquals(listOf(77L), row.evidence)
        service.write(id)
        assertEquals(row.reason, matches.get(id)!!.reason)
    }

    @Test
    fun `근거 있는 설명이 없으면 NO_REASON, 실패 큐도 NO_REASON`() {
        val id = setup(artist("설명 없는 가수", null))
        service.write(id)
        assertEquals("NO_REASON", matches.get(id)!!.status)
        assertNull(matches.get(id)!!.reason)

        val id2 = setup(artist("또 다른 가수", listOf(5L)))
        service.fail(id2)
        assertEquals("NO_REASON", matches.get(id2)!!.status)
    }

    /** 384차원 벡터: [hot] 자리만 크고 나머지는 작게 (같은 hot이면 코사인 거리가 0에 가깝다) */
    private fun vector(artistId: Long, hot: Int) {
        val v = (0 until 384).joinToString(",", "[", "]") { if (it == hot) "1" else "0.01" }
        jdbc.sql("INSERT INTO artist_vector (artist_id, model, raw, mixed, text_hash) VALUES (:a, 'test', CAST(:v AS vector), CAST(:v AS vector), 'h')")
            .param("a", artistId).param("v", v).update()
    }

    /** 겹친 가수 없는 두 사람 + 저장된 점수 */
    private fun setupApart(aArtists: List<Long>, bArtists: List<Long>): Long {
        val roomId = rooms.create(UUID.randomUUID().toString().take(10), "코드 방")
        val a = rooms.addMember(roomId, "상우", Role.HOST, 0, "x")
        val b = rooms.addMember(roomId, "민지", Role.MEMBER, 1, "x")
        aArtists.forEach { song(roomId, a, it) }; bArtists.forEach { song(roomId, b, it) }
        val parts = """{"genre":0.3,"distance":0.4,"artist":0.0,"song":0.0,"sharedArtists":[],"sharedGalaxies":[],"sharedSongs":0}"""
        return matches.insert(roomId, minOf(a, b), maxOf(a, b), UUID.randomUUID().toString().replace("-", "").repeat(2).take(64), 30, parts)!!
    }

    @Test
    fun `겹친 가수가 없으면 벡터가 가까운 짝을 찾는다`() {
        val x = artist("A의 신스팝", listOf(11L)); vector(x, 0)
        val z = artist("A의 메탈", listOf(12L)); vector(z, 1)
        val y = artist("B의 신스팝", listOf(21L)); vector(y, 0)
        val w = artist("B의 재즈", listOf(22L)); vector(w, 2)

        val pairs = matches.nearestPairs(listOf(x, z), listOf(y, w), 3)
        assertEquals(x to y, pairs.first().aId to pairs.first().bId, "가장 가까운 짝이 먼저")
        assertTrue(pairs.first().distance < 0.01)

        val id = setupApart(listOf(x, z), listOf(y, w))
        service.write(id)
        val row = matches.get(id)!!
        assertEquals("DONE", row.status)
        assertTrue(row.evidence.isNotEmpty() && row.evidence.all { it in setOf(11L, 12L, 21L, 22L) })
    }

    @Test
    fun `벡터가 없으면 각자 많이 담은 가수로 돌아간다`() {
        val x = artist("벡터 없는 A", listOf(31L))
        val y = artist("벡터 없는 B", listOf(41L))
        assertTrue(matches.nearestPairs(listOf(x), listOf(y), 3).isEmpty())
        val id = setupApart(listOf(x), listOf(y))
        service.write(id)
        assertEquals("DONE", matches.get(id)!!.status)
    }

    @Test
    fun `겹친 가수가 있으면 벡터 짝을 쓰지 않고 겹친 가수만 근거로 쓴다`() {
        val shared = artist("둘 다 담은 가수", listOf(51L)); vector(shared, 5)
        val x = artist("A의 신스팝2", listOf(61L)); vector(x, 6)
        val y = artist("B의 신스팝2", listOf(71L)); vector(y, 6)
        val id = setupApart(listOf(shared, x), listOf(shared, y))
        service.write(id)
        val row = matches.get(id)!!
        assertEquals("DONE", row.status)
        assertEquals(listOf(51L), row.evidence)
    }
}
