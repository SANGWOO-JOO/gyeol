package dev.gyeol.worker.vibe

import dev.gyeol.core.room.ArtistEvidence
import dev.gyeol.core.room.Role
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.VibePick
import dev.gyeol.core.room.VibeQuery
import dev.gyeol.core.room.VibeSearchRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 분위기로 가수 찾기 worker (가짜 임베딩·가짜 LLM, docs/todo/vibe-search-rag.md 8절).
 * 질문 "hot:N"은 N번 자리만 큰 벡터로 바뀐다. 가수 벡터도 같은 방식이라 같은 N이면 거리 ≈ 0, 다르면 ≈ 0.97
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=none",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.vibe-search.max-distance=0.5",
    ],
)
class VibeSearchIntegrationTest {

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

        fun vec(hot: Int) = FloatArray(384) { if (it == hot) 1f else 0.01f }
    }

    /** LLM 자리: 테스트마다 [next]를 바꾼다 (기본은 가짜와 같이 아무도 안 고름) */
    class StubLlm : VibeSearchLlm {
        var calls = 0
        var seen: List<ArtistEvidence> = emptyList()
        var next: (List<ArtistEvidence>) -> VibeLlmResult = { VibeLlmResult(emptyList(), "fake", 0, 0, 0) }
        override fun pick(query: String, candidates: List<ArtistEvidence>): VibeLlmResult { calls++; seen = candidates; return next(candidates) }
    }

    @TestConfiguration
    class Stubs {
        @Bean @Primary fun stubLlm() = StubLlm()
        @Bean @Primary fun stubEmbedder(): QueryEmbedder = QueryEmbedder { text, prefix ->
            assertEquals("query", prefix, "D2 기본 접두어")
            vec(text.removePrefix("hot:").toInt())
        }
    }

    @Autowired lateinit var service: VibeSearchService
    @Autowired lateinit var searches: VibeSearchRepository
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var llm: StubLlm
    @Autowired lateinit var json: JsonMapper

    @AfterEach
    fun reset() { llm.calls = 0; llm.next = { VibeLlmResult(emptyList(), "fake", 0, 0, 0) } }

    /** 가수 + (있으면) 설명·근거 + descr 벡터 [hot] */
    private fun artist(name: String, hot: Int, evidence: List<Long>? = listOf(1L), description: String? = "몽환적인 드림팝을 들려줘요.", descr: Boolean = true): Long {
        val id = jdbc.sql("INSERT INTO artist (mbid, name, sort_name, aliases) VALUES (:m, :n, :n, '{}') RETURNING id")
            .param("m", UUID.randomUUID()).param("n", name).query(Long::class.java).single()
        if (evidence != null) {
            jdbc.sql("INSERT INTO artist_profile (artist_id, description, evidence, described_at) VALUES (:a, :d, :e, now())")
                .param("a", id).param("d", description).param("e", evidence.toTypedArray()).update()
        }
        val v = vec(hot).joinToString(",", "[", "]")
        val far = vec(383).joinToString(",", "[", "]")
        jdbc.sql("INSERT INTO artist_vector (artist_id, model, raw, descr, mixed, text_hash) VALUES (:a, 'test', CAST(:f AS vector), CAST(:d AS vector), CAST(:f AS vector), 'h')")
            .param("a", id).param("f", far).param("d", if (descr) v else null).update()
        return id
    }

    private fun request(q: String, mapVersion: Long = 0): Long {
        val roomId = rooms.create(UUID.randomUUID().toString().take(10), "분위기 방")
        val m = rooms.addMember(roomId, "상우", Role.HOST, 0, "x")
        return searches.insert(roomId, m, q, VibeQuery.hash(q), mapVersion)
    }

    private fun picks(id: Long): List<VibePick> = json.readValue(searches.get(id)!!.picksJson!!, Array<VibePick>::class.java).toList()

    @Test
    fun `검색 - 설명·근거 없는 가수와 descr 없는 가수는 빼고 거리순, 지도 판이 있으면 그 판의 가수만`() {
        val near = artist("가까운 가수", 10)
        val noEvidence = artist("근거 없는 가수", 10, evidence = emptyList())
        val noProfile = artist("설명 없는 가수", 10, evidence = null)
        val noDescrVec = artist("설명 벡터 없는 가수", 10, descr = false)
        val nullDescription = artist("설명 비어 있는 가수", 10, description = null)
        val far = artist("먼 가수", 11)

        val hits = searches.search(vec(10), "descr", 10, 0)
        val ids = hits.map { it.artistId }
        assertEquals(near, ids.first())
        assertTrue(hits.first().d < 0.01)
        assertTrue(far in ids)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.d <= b.d }, "거리순")
        listOf(noEvidence, noProfile, noDescrVec, nullDescription).forEach { assertFalse(it in ids) }
        assertTrue(searches.search(vec(10), "mixed", 10, 0).isNotEmpty(), "열은 설정으로 바꾼다")

        val version = jdbc.sql("INSERT INTO map_version (params, artist_count) VALUES ('{}', 1) RETURNING id").query(Long::class.java).single()
        jdbc.sql("INSERT INTO artist_position (map_version, artist_id, x, y, placed_by) VALUES (:v, :a, 0.5, 0.5, 'UMAP')").param("v", version).param("a", far).update()
        assertEquals(listOf(far), searches.search(vec(10), "descr", 10, version).map { it.artistId })
    }

    @Test
    fun `가짜 LLM이면 검색 상위 가수를 이유 없이 카드로, 두 번 받아도 그대로`() {
        val a = artist("드림팝 A", 20); artist("드림팝 B", 20); artist("드림팝 C", 20)
        val id = request("hot:20")
        service.process(id)
        val row = searches.get(id)!!
        assertEquals("DONE", row.status)
        val p = picks(id)
        assertEquals(3, p.size)
        assertTrue(p.all { it.reason == null })
        assertTrue(a in p.map { it.artistId })
        assertTrue(row.evidence.isEmpty())
        assertTrue(json.readTree(row.hitsJson!!).size() in 3..10)

        service.process(id)
        assertEquals(1, llm.calls, "PENDING이 아니면 다시 부르지 않는다")
    }

    @Test
    fun `LLM 결과는 검증 뒤 저장 - top-10 밖 제거, 준 적 없는 청크 제거, 근거 0개면 이유 없음`() {
        val a = artist("슈게이즈 A", 30, evidence = listOf(301L, 302L))
        val b = artist("슈게이즈 B", 30, evidence = listOf(303L))
        val c = artist("슈게이즈 C", 30, evidence = listOf(304L))
        llm.next = { cands ->
            assertTrue(cands.any { it.artistId == a }, "후보에 설명·근거가 들어간다")
            VibeLlmResult(
                listOf(
                    RawVibePick(999_999, "지도에 없는 가수", listOf(301)),
                    RawVibePick(a, "비 오는 밤의 몽환적인 기타와 닿아요.", listOf(301, 12345)),
                    RawVibePick(b, "근거를 지어냈어요.", listOf(54321)),
                    RawVibePick(c, "느린 템포가 밤과 어울려요.", listOf(304)),
                ),
                "fake", 0, 0, 0,
            )
        }
        val id = request("hot:30")
        service.process(id)
        val p = picks(id)
        assertEquals(listOf(a, b, c), p.map { it.artistId })
        assertEquals(VibePick(a, "비 오는 밤의 몽환적인 기타와 닿아요.", listOf(301L)), p[0])
        assertNull(p[1].reason)
        assertEquals(listOf(301L, 304L), searches.get(id)!!.evidence)
    }

    @Test
    fun `LLM이 실패해도 이유 없이 카드만 DONE`() {
        artist("재즈 A", 40); artist("재즈 B", 40); artist("재즈 C", 40)
        llm.next = { error("Bedrock 503") }
        val id = request("hot:40")
        service.process(id)
        assertEquals("DONE", searches.get(id)!!.status)
        assertEquals(3, picks(id).size)
    }

    @Test
    fun `top-1 거리가 max-distance보다 멀면 LLM 없이 picks 비움(notFound)`() {
        artist("다른 가수", 50)
        val id = request("hot:51")
        service.process(id)
        val row = searches.get(id)!!
        assertEquals("DONE", row.status)
        assertEquals("[]", row.picksJson!!.replace(" ", ""))
        assertEquals(0, llm.calls)
    }

    @Test
    fun `실제 LLM이 아무도 고르지 않으면 카드로 채우지 않고 notFound`() {
        artist("록 A", 70); artist("록 B", 70); artist("록 C", 70)
        llm.next = { VibeLlmResult(emptyList(), "haiku", 10, 5, 100) }
        val id = request("hot:70")
        service.process(id)
        val row = searches.get(id)!!
        assertEquals("DONE", row.status)
        assertEquals("[]", row.picksJson!!.replace(" ", ""))
        assertEquals(1, llm.calls)
    }

    @Test
    fun `실패 큐는 FAILED`() {
        val id = request("hot:60")
        service.fail(id)
        assertEquals("FAILED", searches.get(id)!!.status)
    }
}
