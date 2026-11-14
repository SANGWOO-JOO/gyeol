package dev.gyeol.worker.room

import com.sun.net.httpserver.HttpServer
import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.worker.llm.CleanResult
import dev.gyeol.worker.llm.FakeTitleCleaner
import dev.gyeol.worker.llm.MedleyResult
import dev.gyeol.worker.llm.RawTitle
import dev.gyeol.worker.llm.SpellResult
import dev.gyeol.worker.llm.TitleCleaner
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 검색 때 가수 미리 준비 (11-14 B안). 가짜 fetcher:
 * - 준비가수: 인기곡 대표1·없는곡·대표2·대표3·대표4, find-artist → AAAA, 대표N 식별 → 가수 AAAA
 * - 동명가수: find-artist 404 (동명이인이 남음)
 * - 섞인가수: find-artist → BBBB, "남의곡"은 다른 가수(AAAA) 곡으로 식별됨, "섞인곡"은 BBBB 곡
 * LLM은 호출 수를 세는 가짜로 바꿔, 준비 작업이 LLM을 한 번도 부르지 않는지 본다
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=none",
        "gyeol.worker.cleanup.enabled=false",
        "gyeol.worker.cover.queue=gyeol-prep-cover",
        "gyeol.worker.room.song-queue=gyeol-prep-room-song",
        "gyeol.worker.artist-image.queue=gyeol-prep-artist-image",
        "gyeol.worker.artist-image.bulk-pause=1ms",
    ],
)
class ArtistPrepareIntegrationTest {

    @TestConfiguration
    class CountingLlm {
        @Bean
        @Primary
        fun countingCleaner(): TitleCleaner = object : TitleCleaner {
            private val fake = FakeTitleCleaner()
            override fun clean(items: List<RawTitle>, model: String?): CleanResult {
                LLM_CALLS.incrementAndGet()
                // I-55 시험: 영어 제목과 다른 언어 제목(titleAlt)을 함께 돌려주는 LLM 흉내 (가짜, 비용 없음)
                if (items.any { it.title.contains("언어판시험") }) return CleanResult(
                    items.map { dev.gyeol.worker.llm.CleanedTitle(it.index, "언어가수", null, "You&I", "너랑나곡", dev.gyeol.worker.llm.Version.ORIGINAL) },
                    "fake", 0, 0, 0,
                )
                return fake.clean(items, model)
            }
            override fun spellings(items: List<RawTitle>, model: String?): SpellResult { LLM_CALLS.incrementAndGet(); return fake.spellings(items, model) }
            override fun medleySongs(items: List<RawTitle>, model: String?): MedleyResult { LLM_CALLS.incrementAndGet(); return fake.medleySongs(items, model) }
        }
    }

    companion object {
        val LLM_CALLS = AtomicInteger()
        const val A = "aaaaaaaa-0000-0000-0000-000000000001"
        const val B = "bbbbbbbb-0000-0000-0000-000000000002"

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3", "sqs")

        private fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            if (code == 404) { ex.sendResponseHeaders(404, -1); ex.close(); return }
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }

        private fun recording(title: String) = UUID.nameUUIDFromBytes(title.toByteArray()).toString()

        private val fetcher = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/internal/lastfm/top-tracks") { ex ->
                val q = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), Charsets.UTF_8)
                val titles = when {
                    q.contains("artist=준비가수") -> listOf("대표1", "없는곡", "대표2", "대표3", "대표4")
                    q.contains("artist=동명가수") -> listOf("노래")
                    q.contains("artist=섞인가수") -> listOf("남의곡", "섞인곡")
                    else -> emptyList()
                }
                reply(ex, 200, """{"items":[${titles.joinToString(",") { """{"title":"$it","artist":"x","mbid":null}""" }}],"total":${titles.size}}""")
            }
            createContext("/internal/artist-image/find-artist") { ex ->
                val q = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), Charsets.UTF_8)
                when {
                    q.contains("name=준비가수") -> reply(ex, 200, """{"mbid":"$A"}""")
                    q.contains("name=섞인가수") -> reply(ex, 200, """{"mbid":"$B"}""")
                    else -> reply(ex, 404, "")
                }
            }
            createContext("/internal/musicbrainz/identify") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
                // I-55: 영어 제목은 일본어판 녹음만 맞고(languageVersion), 한국어 제목은 원곡에 맞는다
                if (body.contains("언어가수")) {
                    val jp = title == "You&I"
                    val t = if (jp) "You & I (Japanese Version)" else "너랑나곡"
                    reply(ex, 200, """{"status":"MATCHED","recording":{"mbid":"${recording("lang$t")}","title":"$t"},
                        "artist":{"mbid":"cccccccc-0000-0000-0000-000000000003","name":"언어가수","sortName":"언어가수","aliases":[]},
                        "release":{"mbid":"${recording("rel$t")}","groupMbid":"${recording("rg$t")}","title":"음반 $t","date":"2011-11-29","type":"Album"},
                        "languageVersion":$jp,"calls":2,"ms":10}""")
                    return@createContext
                }
                val (artistMbid, artistName) = when {
                    body.contains("준비가수") && title.startsWith("대표") -> A to "준비가수"
                    body.contains("섞인가수") && title == "남의곡" -> A to "준비가수"
                    body.contains("섞인가수") && title == "섞인곡" -> B to "섞인가수"
                    else -> null to null
                }
                val res = if (artistMbid == null) """{"status":"NOT_FOUND","reason":"RECORDING","calls":2,"ms":10}"""
                else """{"status":"MATCHED","recording":{"mbid":"${recording(artistName + title)}","title":"$title"},
                    "artist":{"mbid":"$artistMbid","name":"$artistName","sortName":"$artistName","aliases":[]},
                    "release":{"mbid":"${recording("rel$title")}","groupMbid":"${recording("rg$title")}","title":"음반","date":"2020-01-01","type":"Album"},
                    "calls":2,"ms":10}"""
                reply(ex, 200, res)
            }
            start()
        }

        @DynamicPropertySource @JvmStatic
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
        lateinit var coverUrl: String
        lateinit var imageUrl: String
        lateinit var songUrl: String

        @BeforeAll @JvmStatic
        fun queues() {
            coverUrl = sqs.createQueue { it.queueName("gyeol-prep-cover") }.queueUrl()
            imageUrl = sqs.createQueue { it.queueName("gyeol-prep-artist-image") }.queueUrl()
            songUrl = sqs.createQueue { it.queueName("gyeol-prep-room-song") }.queueUrl()
        }
    }

    @Autowired lateinit var service: ArtistPrepareService
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var identify: IdentifyRepository

    @BeforeEach
    fun reset() {
        LLM_CALLS.set(0)
        listOf(coverUrl, imageUrl, songUrl).forEach { messages(it) } // 남은 메시지 비우기
    }

    private fun queued(key: String, name: String) =
        jdbc.sql("INSERT INTO search_artist_prep (prep_key, name, status) VALUES (:k, :n, 'QUEUED') ON CONFLICT (prep_key) DO UPDATE SET status = 'QUEUED'")
            .param("k", key).param("n", name).update()

    private fun row(key: String): Triple<String, Long?, String?> =
        jdbc.sql("SELECT status, artist_id, reason FROM search_artist_prep WHERE prep_key = :k").param("k", key)
            .query { rs, _ -> Triple(rs.getString(1), rs.getObject(2) as Long?, rs.getString(3)) }.single()

    private fun messages(url: String): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        repeat(3) {
            val got = sqs.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1) }.messages()
            got.forEach { m -> sqs.deleteMessage { it.queueUrl(url).receiptHandle(m.receiptHandle()) } }
            out += got.map { json.readTree(it.body()) }
        }
        return out
    }

    private fun artistIdOf(mbid: String): Long? =
        jdbc.sql("SELECT id FROM artist WHERE mbid = :m::uuid").param("m", mbid).query(Long::class.java).optional().orElse(null)

    @Test
    fun `처음 보는 가수 - 확정한 가수의 인기곡 3곡을 LLM 없이 식별하고 표지·사진 큐로, 지도에는 올리지 않음`() {
        queued("n:준비가수", "준비가수")
        service.prepare("n:준비가수", "준비가수", null)

        val aid = artistIdOf(A)!!
        assertEquals(Triple("DONE", aid, null), row("n:준비가수"))
        val titles = jdbc.sql("SELECT title FROM track WHERE artist_id = :a ORDER BY id").param("a", aid).query(String::class.java).list()
        assertEquals(listOf("대표1", "대표2", "대표3"), titles, "못 찾은 곡은 건너뛰고 3곡에서 멈춘다")
        val coverIds = messages(coverUrl).flatMap { m -> m["trackIds"].toList().map { it.asLong() } }.toSet()
        val trackIds = jdbc.sql("SELECT id FROM track WHERE artist_id = :a").param("a", aid).query(Long::class.java).list().toSet()
        assertEquals(trackIds, coverIds)
        val photo = messages(imageUrl).single()
        assertEquals(aid, photo["items"][0]["artistId"].asLong())
        assertTrue(photo["commonsOnly"].asBoolean())
        assertEquals(0, LLM_CALLS.get(), "유료 LLM 호출 0 (allowLlm=false)")
        assertTrue(messages(songUrl).none { it["type"].asString() == "PREPARE_ARTIST" }, "공용 지도 준비를 맡기지 않는다")
        // 못 찾은 Topic 곡은 캐시에 남기지 않는다 (나중에 LLM 경로가 다시 볼 수 있게)
        assertNull(identify.findAlias(dev.gyeol.core.identify.AliasKey.of("없는곡", "준비가수 - Topic")))
    }

    @Test
    fun `동명이인이 남으면(find-artist 404) 준비하지 않는다`() {
        queued("n:동명가수", "동명가수")
        service.prepare("n:동명가수", "동명가수", null)
        assertEquals(Triple("SKIPPED", null, "AMBIGUOUS"), row("n:동명가수"))
        assertTrue(messages(coverUrl).isEmpty() && messages(imageUrl).isEmpty())
        assertEquals(0, LLM_CALLS.get())
    }

    @Test
    fun `식별된 곡의 가수가 확정한 가수와 다르면 그 곡은 버린다`() {
        queued("n:섞인가수", "섞인가수")
        service.prepare("n:섞인가수", "섞인가수", null)
        val b = artistIdOf(B)!!
        assertEquals(Triple("DONE", b, null), row("n:섞인가수"))
        val other = jdbc.sql("SELECT t.id FROM track t WHERE t.title = '남의곡'").query(Long::class.java).list()
        val coverIds = messages(coverUrl).flatMap { m -> m["trackIds"].toList().map { it.asLong() } }
        assertTrue(coverIds.none { it in other }, "다른 가수 곡은 표지 큐에 보내지 않는다")
        assertEquals(1, coverIds.size)
        assertEquals(0, LLM_CALLS.get())
    }

    @Test
    fun `우리 가수인데 얼굴이 없으면 표지 미확인 곡과 사진만 다시 본다 (식별 없음)`() {
        val a = ArtistInfo(UUID.randomUUID(), "얼굴없는가수", "Faceless", emptyList())
        val ids = (1..4).map { i -> identify.upsertTrack(TrackInfo(UUID.randomUUID(), "곡 $i", a, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single")) }
        jdbc.sql("UPDATE track SET cover_checked_at = now() WHERE id = :t").param("t", ids[0]).update() // 이미 확인한 곡은 빼고
        val aid = jdbc.sql("SELECT artist_id FROM track WHERE id = :t").param("t", ids[0]).query(Long::class.java).single()
        queued("id:$aid", "얼굴없는가수")
        service.prepare("id:$aid", "얼굴없는가수", aid)
        assertEquals("DONE", row("id:$aid").first)
        val coverIds = messages(coverUrl).flatMap { m -> m["trackIds"].toList().map { it.asLong() } }.toSet()
        assertEquals(ids.drop(1).toSet(), coverIds, "확인 안 한 곡 3곡")
        assertEquals(aid, messages(imageUrl).single()["items"][0]["artistId"].asLong())
        assertEquals(0, LLM_CALLS.get())
    }

    @Test
    fun `같은 메시지를 두 번 받아도 한 번만 한다 (QUEUED일 때만 시작)`() {
        queued("n:준비가수2", "준비가수")
        service.prepare("n:준비가수2", "준비가수", null)
        val first = jdbc.sql("SELECT finished_at FROM search_artist_prep WHERE prep_key = 'n:준비가수2'").query(java.sql.Timestamp::class.java).single()
        messages(coverUrl); messages(imageUrl)
        service.prepare("n:준비가수2", "준비가수", null)
        assertEquals(first, jdbc.sql("SELECT finished_at FROM search_artist_prep WHERE prep_key = 'n:준비가수2'").query(java.sql.Timestamp::class.java).single())
        assertTrue(messages(coverUrl).isEmpty() && messages(imageUrl).isEmpty(), "두 번째는 아무것도 보내지 않는다")
    }

    @Autowired lateinit var identifyService: dev.gyeol.worker.identify.IdentifyService

    @Test
    fun `I-55 LLM 경로 - 영어 제목이 언어판만 맞으면 다른 언어 제목으로 다시 찾아 원곡을 고른다 (가짜 LLM)`() {
        val raw = "언어가수 _ You&I(너랑나곡) 언어판시험"
        val target = dev.gyeol.core.identify.Target(0, dev.gyeol.core.identify.AliasKey.of(raw, "어느채널"), raw, "어느채널", dev.gyeol.core.listen.Source.YOUTUBE, 1, dev.gyeol.core.identify.TargetStatus.PENDING)
        var found: Long? = null
        identifyService.identifyTargets(listOf(target), object : dev.gyeol.worker.identify.IdentifyService.TargetSink {
            override fun finish(t: dev.gyeol.core.identify.Target, trackId: Long?) { found = trackId }
            override fun fail(t: dev.gyeol.core.identify.Target) {}
        })
        val title = jdbc.sql("SELECT title FROM track WHERE id = :t").param("t", found!!).query(String::class.java).single()
        assertEquals("너랑나곡", title, "원곡")
        assertEquals(0, jdbc.sql("SELECT count(*) FROM track WHERE title = 'You & I (Japanese Version)'").query(Int::class.java).single(), "언어판 곡은 만들지 않는다")
    }
}
