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
}
