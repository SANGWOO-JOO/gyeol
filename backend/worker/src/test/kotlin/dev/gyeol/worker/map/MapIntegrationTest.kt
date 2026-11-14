package dev.gyeol.worker.map

import com.sun.net.httpserver.HttpServer
import dev.gyeol.core.map.MapRepository
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
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 음악 지도 데이터 (room-plan.md 8절 ②): 초기 가수 모으기 → 설명(가짜 LLM) → 벡터(가짜 embed) → 새 가수 자리 잡기·구역 이름.
 * UMAP은 tools/map-build(오프라인)라 여기서는 판·좌표를 직접 넣는다.
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=none",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.map.knowledge-retry-wait=0s",
    ],
)
class MapIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("sqs")

        val mbCalls = CopyOnWriteArrayList<String>()

        /** 가짜 Last.fm 나라별 순위 4명: 한국 가수 둘(하나는 mbid 없음), 일본 가수 하나(한국 순위에 섞임), 근거 없는 한국 가수 하나 */
        private val KR1 = "11111111-0000-0000-0000-000000000001"
        private val KR2 = "11111111-0000-0000-0000-000000000002"
        private val JP1 = "11111111-0000-0000-0000-000000000003"
        private val KR3 = "11111111-0000-0000-0000-000000000004"
        /** 근거 출처가 계속 503인 가수 (순위 맨 끝) */
        private val KR4 = "11111111-0000-0000-0000-000000000005"
        val knowledgeCalls = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            fun json(ex: com.sun.net.httpserver.HttpExchange, body: String) {
                val b = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            createContext("/internal/lastfm/geo-top") { ex ->
                val page = Regex("page=(\\d+)").find(ex.requestURI.rawQuery)!!.groupValues[1].toInt()
                json(ex, if (page > 1) """{"items":[]}""" else """{"items":[
                    {"name":"가수일","mbid":"$KR1","listeners":100},{"name":"일본가수","mbid":"$JP1","listeners":90},
                    {"name":"가수이","mbid":null,"listeners":80},{"name":"가수삼","mbid":"$KR3","listeners":70},{"name":"가수사","mbid":"$KR4","listeners":60}]}""")
            }
            createContext("/internal/musicbrainz/artist") { ex ->
                val q = java.net.URLDecoder.decode(ex.requestURI.rawQuery, Charsets.UTF_8)
                mbCalls += q
                val (mbid, name, country) = when {
                    q.contains(KR1) -> Triple(KR1, "Gasu One", "KR")
                    q.contains(JP1) -> Triple(JP1, "Japanese Singer", "JP")
                    q.contains("가수이") -> Triple(KR2, "Gasu Two", "KR")
                    q.contains(KR3) -> Triple(KR3, "Gasu Three", "KR")
                    q.contains(KR4) -> Triple(KR4, "Gasu Four", "KR")
                    else -> Triple("", "", "")
                }
                json(ex, if (mbid.isEmpty()) """{"found":false}""" else """{"found":true,"mbid":"$mbid","name":"$name","sortName":"$name","country":"$country","aliases":["$name 별칭"]}""")
            }
            createContext("/internal/knowledge/") { ex ->
                knowledgeCalls += ex.requestURI.path
                if (ex.requestURI.path.contains(KR4)) { ex.sendResponseHeaders(503, -1); ex.close(); return@createContext }
                // 가수삼은 근거가 없다
                json(ex, if (ex.requestURI.path.contains(KR3)) """{"chunks":[],"credits":[],"workIds":[]}""" else
                    """{"chunks":[{"source":"WIKIPEDIA","lang":"ko","url":"https://ko.wikipedia.org/wiki/x","license":"CC BY-SA 4.0","text":"대한민국의 발라드 가수. 서울 출신."}],"credits":[],"workIds":[]}""")
            }
            // 가짜 embed: 문장 길이로 방향을 정한 단위 벡터
            createContext("/embed") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                val texts = Regex("\"texts\":\\[(.*?)\\],\"kind\"").find(body)!!.groupValues[1].split("\",\"")
                val vectors = texts.joinToString(",") { t -> (0 until 384).joinToString(",", "[", "]") { d -> if (d == t.length % 384) "1.0" else "0.0" } }
                json(ex, """{"vectors":[$vectors],"dim":384,"model":"fake-e5"}""")
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
            registry.add("gyeol.worker.identify.fetcher-url") { "http://localhost:${server.address.port}" }
            registry.add("gyeol.worker.embed.url") { "http://localhost:${server.address.port}" }
        }
    }

    @Autowired lateinit var service: MapService
    @Autowired lateinit var map: MapRepository
    @Autowired lateinit var rooms: RoomRepository
    @Autowired lateinit var jdbc: JdbcClient

    private fun artistId(mbid: String) = jdbc.sql("SELECT id FROM artist WHERE mbid = :m").param("m", UUID.fromString(mbid)).query(Long::class.java).single()

    @Test
    fun `초기 가수 - 출신 국가로 거르고, 이름으로도 찾고, 다시 돌려도 늘지 않는다 → 설명·벡터`() {
        service.seed(listOf(SeedCountry("KR", "South Korea")), 3)
        assertEquals(3, map.seedCount("KR"), "일본 가수는 한국 순위에 있어도 빠진다")
        val ranks = jdbc.sql("SELECT a.mbid::text, p.seed_rank FROM artist_profile p JOIN artist a ON a.id = p.artist_id WHERE p.seed_country = 'KR' ORDER BY p.seed_rank")
            .query { rs, _ -> rs.getString(1) to rs.getInt(2) }.list()
        assertEquals(listOf(KR1 to 1, KR2 to 2, KR3 to 3), ranks)
        assertTrue(mbCalls.any { it.contains("name=가수이") }, "mbid가 없으면 이름으로 찾는다")
        val aliases = jdbc.sql("SELECT aliases FROM artist WHERE mbid = :m").param("m", UUID.fromString(KR2))
            .query { rs, _ -> @Suppress("UNCHECKED_CAST") (rs.getArray(1).array as Array<String>).toList() }.single()
        assertTrue("가수이" in aliases, "Last.fm 이름을 별칭으로 남긴다: $aliases")

        val before = mbCalls.size
        service.seed(listOf(SeedCountry("KR", "South Korea")), 3)
        assertEquals(3, map.seedCount("KR"))
        assertEquals(before, mbCalls.size, "이미 다 모았으면 다시 조회하지 않는다")

        // 설명: 근거가 있으면 가짜 LLM 설명 + 근거 번호, 없으면 LLM 없이 "none"
        assertEquals(3, service.describeAll(10))
        val kr1 = map.detail(artistId(KR1))!!
        assertNotNull(kr1.description)
        assertEquals(1, kr1.evidence.size)
        assertNull(map.detail(artistId(KR3))!!.description)
        assertEquals(0, service.describeAll(10), "한 번 설명한 가수는 다시 하지 않는다")

        // 벡터: 셋 모두 (근거 없는 가수도 이름으로)
        // 같은 DB를 쓰는 다른 테스트가 남긴 가수도 함께 처리될 수 있어 개수 대신 세 가수를 확인한다
        assertTrue(service.embedAll() >= 3)
        listOf(KR1, KR2, KR3).forEach { assertNotNull(map.mixedVector(artistId(it)), it) }
        assertEquals(0, service.embedAll())
    }

    @Test
    fun `원문 벡터에서 나라·도시 단어를 지우고, 두 벡터는 반씩 섞는다`() {
        val t = MapService.rawText("가수", listOf("대한민국의 발라드 가수. 서울 출신. Korean ballad singer from Seoul"))
        assertTrue(!t.contains("대한민국") && !t.contains("서울") && !t.contains("Korean") && !t.contains("Seoul"), t)
        assertTrue(t.contains("발라드") && t.contains("ballad"))
        val m = MapService.mix(floatArrayOf(1f, 0f), floatArrayOf(0f, 2f))
        assertTrue(abs(m[0] - m[1]) < 1e-6 && abs(m[0] * m[0] + m[1] * m[1] - 1) < 1e-6)
    }

    @Test
    fun `새 가수는 가까운 가수 5명 좌표 평균에, 구역은 이웃 다수결, 담긴 방은 다시 그린다 → 구역 이름`() {
        // 판 하나와 자리 잡은 가수 6명: 0~4는 구역 A 근처(벡터가 비슷), 5는 멀리 구역 B
        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 6, true) RETURNING id").query(Long::class.java).single()
        val gA = jdbc.sql("INSERT INTO galaxy (map_version, label, cx, cy) VALUES (:v, 0, 0.2, 0.2) RETURNING id").param("v", version).query(Long::class.java).single()
        val gB = jdbc.sql("INSERT INTO galaxy (map_version, label, cx, cy) VALUES (:v, 1, 0.9, 0.9) RETURNING id").param("v", version).query(Long::class.java).single()
        fun vec(i: Int) = FloatArray(384) { d -> if (d == i) 1f else 0f }
        val placed = (0..5).map { i ->
            val id = map.upsertArtist(UUID.randomUUID(), "자리 $i", null, emptyList())
            map.ensureProfile(id, "KR")
            map.saveDescription(id, "설명 $i", emptyList(), "fake")
            // 0~4는 0번 축 근처, 5는 다른 축
            val v = if (i < 5) MapService.normalize(FloatArray(384) { d -> if (d == 0) 1f else if (d == i + 10) 0.3f else 0f }) else vec(200)
            map.saveVector(id, "fake", v, null, v, "h$i")
            map.savePosition(version, id, 0.1 + i * 0.02, 0.1 + i * 0.02, if (i < 5) gA else gB, "UMAP")
            id
        }
        val newbie = map.upsertArtist(UUID.randomUUID(), "새 가수", null, emptyList())
        val newVec = MapService.normalize(FloatArray(384) { d -> if (d == 0) 1f else if (d == 50) 0.2f else 0f })
        map.ensureProfile(newbie, null)
        map.saveDescription(newbie, "새 설명", emptyList(), "fake")
        map.saveVector(newbie, "fake", newVec, null, newVec, "hn")
        val room = rooms.create("mapTest01", "방")
        val rev = rooms.findById(room)!!.mapRev

        service.place(version, newbie)
        val pos = map.positions(version, listOf(newbie))[newbie]!!
        val expected = (0..4).map { 0.1 + it * 0.02 }.average()
        assertTrue(abs(pos.x - expected) < 1e-9 && abs(pos.y - expected) < 1e-9, "이웃 5명 평균: $pos")
        assertEquals(gA, pos.galaxyId, "구역은 이웃 다수결")

        // prepare는 이미 자리가 있으면 바로 끝낸다
        map.requestPrepare(newbie)
        service.prepare(newbie)
        assertEquals("DONE", map.prepareStatus(listOf(newbie))[newbie])
        assertEquals(rev, rooms.findById(room)!!.mapRev, "자리가 이미 있었으므로 방을 다시 그릴 필요 없음")

        // 구역 이름 (가짜 LLM)
        assertEquals(2, service.nameGalaxies())
        val names = jdbc.sql("SELECT name FROM galaxy WHERE map_version = :v ORDER BY label").param("v", version).query(String::class.java).list()
        assertTrue(names.all { it?.startsWith("구역") == true }, names.toString())
        assertTrue(placed.size == 6)
    }

    @Test
    fun `자리가 없는 가수는 준비 단계를 모두 거쳐 자리를 잡고 담긴 방을 다시 그린다`() {
        val version = jdbc.sql("SELECT id FROM map_version WHERE active").query(Long::class.java).optional().orElse(null)
            ?: jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 0, true) RETURNING id").query(Long::class.java).single()
        // 이웃 후보 하나
        val anchor = map.upsertArtist(UUID.randomUUID(), "기준 가수", null, emptyList())
        val v = MapService.normalize(FloatArray(384) { d -> if (d == 7) 1f else 0f })
        map.saveVector(anchor, "fake", v, null, v, "ha")
        map.savePosition(version, anchor, 0.5, 0.5, null, "UMAP")

        val artist = map.upsertArtist(UUID.fromString("22222222-0000-0000-0000-000000000001"), "처음 가수", null, emptyList())
        val room = rooms.create("mapTest02", "방")
        val member = rooms.addMember(room, "상우", Role.HOST, 0, "x")
        val track = jdbc.sql(
            "INSERT INTO track (recording_mbid, title, artist_id) VALUES (gen_random_uuid(), '노래', :a) RETURNING id",
        ).param("a", artist).query(Long::class.java).single()
        jdbc.sql("INSERT INTO room_song (room_id, member_id, track_id, artist_id) VALUES (:r, :m, :t, :a)").param("r", room).param("m", member).param("t", track).param("a", artist).update()
        val rev = rooms.findById(room)!!.mapRev

        assertTrue(map.requestPrepare(artist))
        assertTrue(!map.requestPrepare(artist), "두 번 요청해도 한 번만")
        service.prepare(artist)
        assertEquals("DONE", map.prepareStatus(listOf(artist))[artist])
        assertNotNull(map.detail(artist)!!.description, "근거를 가져와 설명까지")
        assertNotNull(map.positions(version, listOf(artist))[artist], "이웃 평균 자리")
        assertEquals(rev + 1, rooms.findById(room)!!.mapRev)
    }

    @Test
    fun `근거 출처가 계속 실패하는 가수는 3번 시도 뒤 건너뛰고 나머지를 계속 모은다`() {
        service.seed(listOf(SeedCountry("KR", "South Korea")), 10)
        assertEquals(3, map.seedCount("KR"), "실패한 가수는 초기 가수로 표시하지 않는다")
        assertEquals(3, knowledgeCalls.count { it.contains(KR4) })
        assertEquals(0, jdbc.sql("SELECT count(*) FROM artist WHERE mbid = :m").param("m", UUID.fromString(KR4)).query(Int::class.java).single())
    }

    @Test
    fun `별칭 없는 가수를 다시 넣어도 별칭은 빈 배열로 남는다`() {
        val mbid = UUID.randomUUID()
        val id = map.upsertArtist(mbid, "별칭 없음", null, emptyList())
        assertEquals(id, map.upsertArtist(mbid, "별칭 없음", null, emptyList()))
        assertEquals(0, jdbc.sql("SELECT cardinality(aliases) FROM artist WHERE id = :id").param("id", id).query(Int::class.java).single())
    }

    @Test
    fun `V33 - 사람·캐릭터 줄의 활동 시작(생일)만 지우고 그룹 결성일은 두며, 두 번 돌려도 같다`() {
        val sql = javaClass.classLoader.getResource("db/migration/V33__strip_person_begin.sql")!!.readText()
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        fun add(text: String): Long = jdbc.sql(
            "INSERT INTO knowledge_chunk (entity_type, entity_mbid, source, lang, url, license, text) VALUES ('ARTIST', :m, 'MUSICBRAINZ', 'ko', 'u', 'CC0', :t) RETURNING id",
        ).param("m", UUID.randomUUID()).param("t", text).query(Long::class.java).single()
        fun text(id: Long) = jdbc.sql("SELECT text FROM knowledge_chunk WHERE id = :i").param("i", id).query(String::class.java).single()
        val person = add("가수 '윤하V33' (MusicBrainz): 유형 Person; 지역 South Korea; 활동 시작 1988-04-29; 장르·태그 k-pop.")
        val unknown = add("가수 '칸나V33' (MusicBrainz): 유형 Character; 지역 South Korea; 활동 시작 ????-12-02.")
        val group = add("가수 '다비치V33' (MusicBrainz): 유형 Group; 활동 시작 2008; 장르·태그 ballad.")
        jdbc.sql(sql).update()
        assertEquals("가수 '윤하V33' (MusicBrainz): 유형 Person; 지역 South Korea; 장르·태그 k-pop.", text(person))
        assertEquals("가수 '칸나V33' (MusicBrainz): 유형 Character; 지역 South Korea.", text(unknown))
        assertEquals("가수 '다비치V33' (MusicBrainz): 유형 Group; 활동 시작 2008; 장르·태그 ballad.", text(group))
        assertEquals(0, jdbc.sql(sql).update(), "두 번째는 바꿀 행이 없다")
    }

    @Test
    fun `설명 다시 만들기 - 지정한 가수만, 실패한 것만 한 번 더, 빈 답은 기존 설명 유지, 호출 상한에서 멈춤`() {
        val ids = (1..4).map { i ->
            val mbid = UUID.randomUUID()
            map.upsertArtist(mbid, "다시 $i", null, emptyList()).also { id ->
                map.ensureProfile(id, null)
                map.saveDescription(id, "옛 설명 $i", emptyList(), "m")
                jdbc.sql("INSERT INTO knowledge_chunk (entity_type, entity_mbid, source, lang, url, license, text) VALUES ('ARTIST', :m, 'MUSICBRAINZ', 'ko', 'u', 'CC0', :t)")
                    .param("m", mbid).param("t", "가수 '다시 $i' (MusicBrainz): 유형 Person; 장르·태그 pop.").update()
            }
        }
        val other = map.upsertArtist(UUID.randomUUID(), "번지면 안 되는 가수", null, emptyList()).also { map.ensureProfile(it, null) }
        var failedOnce = false
        val calls = mutableListOf<String>()
        val llm = object : MapLlm {
            override fun describeArtist(name: String, chunks: List<dev.gyeol.core.map.ArtistChunk>): ArtistDescription {
                calls += name
                if (name == "다시 2" && !failedOnce) { failedOnce = true; throw RuntimeException("throttled") }
                val text = if (name == "다시 3") null else "새 설명 $name: 잔잔한 팝 음악을 한다."
                return ArtistDescription(text, listOf(chunks.first().id), LlmUsageInfo("fake", 0, 0, 0))
            }
            override fun nameGalaxy(members: List<GalaxyMemberText>) = GalaxyName(null, null, emptyList(), LlmUsageInfo("fake", 0, 0, 0))
        }
        val r = service.redescribe(ids, llm, 5)
        assertEquals(listOf("OK", "OK", "EMPTY", "OK"), ids.map { r[it] })
        assertEquals(listOf("다시 1", "다시 2", "다시 3", "다시 4", "다시 2"), calls, "실패한 2번만 한 번 더")
        assertEquals("새 설명 다시 1: 잔잔한 팝 음악을 한다.", map.description(ids[0]))
        assertEquals("옛 설명 3", map.description(ids[2]), "빈 답이면 기존 설명을 지우지 않는다")
        assertNull(map.description(other), "지정하지 않은 가수는 건드리지 않는다")

        calls.clear()
        val capped = service.redescribe(ids, llm, 2)
        assertEquals(2, calls.size, "호출 상한")
        assertEquals(listOf("OK", "OK", "SKIPPED_CAP", "SKIPPED_CAP"), ids.map { capped[it] })
    }

    @Test
    fun `거절문·음악 내용 없는 설명은 지우고, 그 가수만 벡터를 다시 만든다`() {
        val ids = (1..3).map { i ->
            map.upsertArtist(UUID.randomUUID(), "정리 $i", null, emptyList()).also { map.ensureProfile(it, null) }
        }
        map.saveDescription(ids[0], "강렬한 비트의 힙합 음악을 한다.", listOf(1), "m")
        map.saveDescription(ids[1], "근거 자료에 음악 장르 정보가 없어 설명을 작성할 수 없습니다.", listOf(1), "m")
        map.saveDescription(ids[2], "2003년부터 활동을 시작한 가수이다.", listOf(1), "m")
        val v = FloatArray(384) { 0.1f }
        ids.forEach { map.saveVector(it, "fake", v, v, v, "h$it") }

        val cleaned = service.cleanDescriptions()

        assertTrue(cleaned >= 2)
        assertNotNull(map.description(ids[0]))
        assertNull(map.description(ids[1]))
        assertNull(map.description(ids[2]))
        val again = map.needVector(1000).map { it.artistId }
        assertTrue(ids[1] in again && ids[2] in again && ids[0] !in again, "지운 가수만 벡터를 다시 만든다")
        // 다른 테스트의 embedAll 개수에 섞이지 않게 정리
        ids.forEach { map.saveVector(it, "fake", v, null, v, "h$it") }
    }

    @Test
    fun `설명 없는 새 가수는 원문 벡터가 가까운 가수 곁에 자리 잡는다`() {
        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 10, true) RETURNING id").query(Long::class.java).single()
        val gA = jdbc.sql("INSERT INTO galaxy (map_version, label, cx, cy) VALUES (:v, 0, 0.2, 0.2) RETURNING id").param("v", version).query(Long::class.java).single()
        val gB = jdbc.sql("INSERT INTO galaxy (map_version, label, cx, cy) VALUES (:v, 1, 0.8, 0.8) RETURNING id").param("v", version).query(Long::class.java).single()
        fun axis(vararg w: Pair<Int, Float>) = MapService.normalize(FloatArray(384).also { f -> w.forEach { (d, x) -> f[d] = x } })
        // A: 원문은 100번 축, 섞은 벡터는 0번 축 / B: 원문은 0번 축, 섞은 벡터는 200번 축 + 100번 축 조금
        (0..9).forEach { i ->
            val id = map.upsertArtist(UUID.randomUUID(), "원문 $i", null, emptyList())
            map.ensureProfile(id, "JP")
            val a = i < 5
            val raw = if (a) axis(100 to 1f, 110 + i to 0.2f) else axis(0 to 1f, 110 + i to 0.2f)
            val mixed = if (a) axis(0 to 1f, 110 + i to 0.2f) else axis(200 to 1f, 100 to 0.6f)
            map.saveDescription(id, "설명 $i", emptyList(), "fake") // 다른 테스트의 describeAll·embedAll 개수에 섞이지 않게
            map.saveVector(id, "fake", raw, raw, mixed, "r$i")
            map.savePosition(version, id, if (a) 0.2 else 0.8, if (a) 0.2 else 0.8, if (a) gA else gB, "UMAP")
        }
        val newbie = map.upsertArtist(UUID.randomUUID(), "설명 없는 새 가수", null, emptyList())
        map.ensureProfile(newbie, null)
        val raw = axis(100 to 1f, 300 to 0.1f)
        map.saveDescription(newbie, null, emptyList(), "none")
        map.saveVector(newbie, "fake", raw, null, raw, "rn")

        service.place(version, newbie)

        val pos = map.positions(version, listOf(newbie))[newbie]!!
        assertEquals(gA, pos.galaxyId, "섞은 벡터로 비교했다면 B(100번 축 성분이 있는 섞은 벡터) 곁으로 갔다")
        assertTrue(abs(pos.x - 0.2) < 1e-9, pos.toString())
    }
}
