package dev.gyeol.worker.cleanup

import dev.gyeol.core.identify.AliasMethod
import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.MedleyRepository
import dev.gyeol.core.identify.MedleySong
import dev.gyeol.core.identify.Query
import dev.gyeol.core.identify.TitleGuess
import dev.gyeol.core.identify.TitleGuessRepository
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.core.listen.ListenEvent
import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.listen.Source
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.Tokens
import dev.gyeol.core.upload.UploadRepository
import dev.gyeol.core.video.VideoCheckRepository
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
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 보관 기간이 지난 데이터 정리: S3 원본(7일) → 청취 기록(7일) → 세션(30일) */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.role=none", // 큐 리스너는 끈다
        "gyeol.worker.cleanup.enabled=false", // 스케줄 대신 직접 부른다
        "gyeol.worker.upload-bucket=gyeol-test-cleanup",
    ],
)
class YoutubeDerivedExpiryTest {

    companion object {
        private const val BUCKET = "gyeol-test-cleanup"

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
            registry.add("spring.cloud.aws.s3.path-style-access-enabled") { "true" }
        }

        val s3: S3Client by lazy {
            S3Client.builder().endpointOverride(localstack.endpoint).region(Region.of(localstack.region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)))
                .forcePathStyle(true).build()
        }

        @BeforeAll
        @JvmStatic
        fun bucket() {
            s3.createBucket { it.bucket(BUCKET) }
        }
    }

    @Autowired lateinit var cleanup: CleanupService
    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var uploads: UploadRepository
    @Autowired lateinit var listens: ListenEventRepository
    @Autowired lateinit var identify: IdentifyRepository
    @Autowired lateinit var guesses: TitleGuessRepository
    @Autowired lateinit var medleys: MedleyRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val now: Instant = Instant.now()
    private val edge: Instant get() = now.minus(VideoCheckRepository.RETENTION)

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM session").update()
        jdbc.sql("DELETE FROM video_check").update()
        jdbc.sql("DELETE FROM title_alias WHERE alias_key LIKE 'i48%'").update()
        jdbc.sql("DELETE FROM title_guess WHERE title LIKE 'i48%'").update()
        jdbc.sql("DELETE FROM medley_song WHERE title LIKE 'i48%'").update()
    }

    /** 세션 하나에 재생 기록 넣기 (만료는 넉넉히) */
    private fun session(events: List<ListenEvent>): Long {
        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, now.plus(Duration.ofDays(30)))
        val id = UUID.randomUUID()
        uploads.create(id, sid, "uploads/$id.json")
        listens.insertBatch(sid, id, events, now.plus(Duration.ofDays(7)))
        return sid
    }

    private fun judged(videoId: String, at: Instant) {
        jdbc.sql("INSERT INTO video_check (video_id, category_id, checked_at) VALUES (:v, '10', :t)").param("v", videoId).param("t", Timestamp.from(at)).update()
    }

    private fun aliasCat(key: String) = jdbc.sql("SELECT category_checked_at FROM title_alias WHERE alias_key = :k").param("k", key)
        .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }.list().single()

    private fun near(a: Instant?, b: Instant) = assertTrue(a != null && Duration.between(a, b).abs() < Duration.ofSeconds(1), "$a ≈ $b")

    @Test
    fun `playGroups - 판정 경로로만 들어온 묶음은 판정 시각, 음악 앱이 섞이거나 Topic이면 null`() {
        val t = now.minus(Duration.ofDays(2))
        judged("i48v1", t)
        val sid = session(
            listOf(
                ListenEvent("i48v1", "i48 일반 영상", "어느 방송", now, Source.YOUTUBE),
                ListenEvent("i48v1", "i48 일반 영상", "어느 방송", now.minusSeconds(60), Source.YOUTUBE),
                ListenEvent("i48v2", "i48 섞인 영상", "공식 채널", now, Source.YOUTUBE),
                ListenEvent("i48v2", "i48 섞인 영상", "공식 채널", now, Source.MUSIC_APP),
                ListenEvent("i48v3", "i48 토픽 곡", "가수 - Topic", now, Source.YOUTUBE),
            ),
        )
        val g = identify.playGroups(sid).associateBy { it.title }
        near(g["i48 일반 영상"]!!.categoryCheckedAt, t)
        assertNull(g["i48 섞인 영상"]!!.categoryCheckedAt)
        assertNull(g["i48 토픽 곡"]!!.categoryCheckedAt)
    }

    @Test
    fun `별칭 판정 시각 합치기 - 판정끼리는 늦은 쪽, 판정 없는 근거가 오면 null이 되고 다시 판정이 와도 null`() {
        val t1 = now.minus(Duration.ofDays(10)); val t2 = now.minus(Duration.ofDays(3))
        identify.saveAlias("i48 a|x", null, AliasMethod.RULE, null, categoryCheckedAt = t1)
        near(aliasCat("i48 a|x"), t1)
        identify.saveAlias("i48 a|x", null, AliasMethod.RULE, null, categoryCheckedAt = t2)
        near(aliasCat("i48 a|x"), t2)
        identify.touchAliasCategory("i48 a|x", t1)
        near(aliasCat("i48 a|x"), t2) // 더 이른 판정은 줄이지 않는다
        identify.touchAliasCategory("i48 a|x", null)
        assertNull(aliasCat("i48 a|x"), "판정 없는 세션이 캐시를 맞히면 계속 보관")
        identify.saveAlias("i48 a|x", null, AliasMethod.RULE, null, categoryCheckedAt = t2)
        assertNull(aliasCat("i48 a|x"), "한 번 null이면 판정 경로가 다시 와도 null")
    }

    @Test
    fun `정리 - 판정 뒤 29일 지난 별칭·추측·메들리·식별 대상만 지우고, 지운 별칭의 YouTube 표기는 검색 색인에서 빠진다, 곡은 남는다`() {
        val old = edge.minusSeconds(60); val fresh = edge.plusSeconds(60)
        val a = ArtistInfo(UUID.randomUUID(), "i48가수", "i48", emptyList())
        val track = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "i48원곡제목", a, null, null, null, null, null))
        identify.saveAlias("i48 old|ch", track, AliasMethod.LLM, Query("i48가수", "i48유튜브표기", false), categoryCheckedAt = old)
        identify.saveAlias("i48 fresh|ch", null, AliasMethod.RULE, null, categoryCheckedAt = fresh)
        identify.saveAlias("i48 keep|ch", null, AliasMethod.RULE, null, categoryCheckedAt = null)
        fun terms(): List<String> = jdbc.sql("SELECT term FROM track_search_term WHERE track_id = :t").param("t", track).query { rs, _ -> rs.getString(1) }.list().filterNotNull()
        assertTrue(terms().any { it.contains("유튜브표기") }, "정리 전에는 YouTube 표기가 색인에 있다")
        guesses.save(TitleGuess("i48 guess old", "ch", "가수", null, "곡", null, "ORIGINAL"), old)
        guesses.save(TitleGuess("i48 guess fresh", "ch", "가수", null, "곡", null, "ORIGINAL"), fresh)
        medleys.save("i48 medley old", "ch", listOf(MedleySong("가수", "곡1")), old)
        medleys.save("i48 medley keep", "ch", listOf(MedleySong("가수", "곡1")), null)
        val sid = session(emptyList())
        jdbc.sql("INSERT INTO identify_target (session_id, alias_key, title, channel, source, plays, category_checked_at) VALUES (:s, 'i48 t1', 't', 'c', 'YOUTUBE', 1, :o), (:s, 'i48 t2', 't', 'c', 'YOUTUBE', 1, NULL)")
            .param("s", sid).param("o", Timestamp.from(old)).update()

        val r = cleanup.run(now)

        assertTrue(r.apiDerived >= 4, r.toString())
        val keys = jdbc.sql("SELECT alias_key FROM title_alias WHERE alias_key LIKE 'i48%' ORDER BY 1").query(String::class.java).list()
        assertEquals(listOf("i48 fresh|ch", "i48 keep|ch"), keys)
        assertEquals(listOf("i48 guess fresh"), jdbc.sql("SELECT title FROM title_guess WHERE title LIKE 'i48%'").query(String::class.java).list())
        assertEquals(listOf("i48 medley keep"), jdbc.sql("SELECT DISTINCT title FROM medley_song WHERE title LIKE 'i48%'").query(String::class.java).list())
        assertEquals(listOf("i48 t2"), jdbc.sql("SELECT alias_key FROM identify_target WHERE session_id = :s").param("s", sid).query(String::class.java).list())
        assertTrue(terms().none { it.contains("유튜브표기") }, "지운 별칭의 표기는 색인에서 빠진다: ${terms()}")
        assertTrue(terms().any { it.contains("원곡제목") }, "곡 이름 표기는 남는다")
        assertEquals(1, jdbc.sql("SELECT count(*) FROM track WHERE id = :t").param("t", track).query(Int::class.java).single(), "곡은 지우지 않는다")
    }

    @Test
    fun `재사용 경로 - 세션에 음악 앱 재생이 있으면 기존 추측·메들리의 판정 시각이 null로, 판정 재생뿐이면 늦은 쪽으로`() {
        val t1 = now.minus(Duration.ofDays(20)); val t2 = now.minus(Duration.ofDays(1))
        guesses.save(TitleGuess("i48 g", "방송", "가수", null, "곡", null, "ORIGINAL"), t1)
        medleys.save("i48 m", "방송", listOf(MedleySong("가수", "곡1"), MedleySong("가수", "곡2")), t1)
        guesses.save(TitleGuess("i48 g2", "방송", "가수", null, "곡", null, "ORIGINAL"), t1)
        judged("i48m2", t2)
        val sid = session(
            listOf(
                ListenEvent("i48m1", "i48 g", "방송", now, Source.MUSIC_APP),
                ListenEvent("i48m1", "i48 m", "방송", now, Source.MUSIC_APP),
                ListenEvent("i48m2", "i48 g2", "방송", now, Source.YOUTUBE),
            ),
        )
        guesses.touchCategories(sid)
        fun gcat(t: String) = jdbc.sql("SELECT category_checked_at FROM title_guess WHERE title = :t").param("t", t).query { rs, _ -> rs.getTimestamp(1)?.toInstant() }.list().single()
        assertNull(gcat("i48 g"))
        assertEquals(listOf<Instant?>(null, null), jdbc.sql("SELECT category_checked_at FROM medley_song WHERE title = 'i48 m' ORDER BY position").query { rs, _ -> rs.getTimestamp(1)?.toInstant() }.list())
        near(gcat("i48 g2"), t2)
        // 메들리를 다시 저장해도 null은 유지
        medleys.save("i48 m", "방송", listOf(MedleySong("가수", "곡1")), t2)
        assertNull(jdbc.sql("SELECT category_checked_at FROM medley_song WHERE title = 'i48 m'").query { rs, _ -> rs.getTimestamp(1)?.toInstant() }.list().single())
    }

    @Test
    fun `V34 채우기 규칙 - Topic 키·판정 없는 대상은 null, 근거 없는 Topic 아닌 별칭은 확인 시각, 판정 대상은 판정 시각`() {
        val sql = javaClass.classLoader.getResource("db/migration/V34__youtube_derived_expiry.sql")!!.readText()
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(";")
            .map { it.trim() }.filter { it.startsWith("UPDATE") || it.startsWith("WITH") }
        val t = now.minus(Duration.ofDays(5))
        jdbc.sql("INSERT INTO title_alias (alias_key, status, method, checked_at) VALUES ('i48 곡|가수 - topic', 'NOT_FOUND', 'RULE', now()), ('i48 곡|방송', 'NOT_FOUND', 'RULE', :c), ('i48 곡2|방송', 'NOT_FOUND', 'RULE', now()), ('i48 곡3|방송', 'NOT_FOUND', 'RULE', now())")
            .param("c", Timestamp.from(t)).update()
        val sid = session(emptyList())
        jdbc.sql("INSERT INTO identify_target (session_id, alias_key, title, channel, source, plays, category_checked_at) VALUES (:s, 'i48 곡2|방송', 'i48 곡2', '방송', 'MUSIC_APP', 1, NULL), (:s, 'i48 곡3|방송', 'i48 곡3', '방송', 'YOUTUBE', 1, :t)")
            .param("s", sid).param("t", Timestamp.from(t)).update()
        // 1)의 식별 대상 채우기는 재생이 없는 세션이라 출처로 판단: MUSIC_APP은 그대로 null, YOUTUBE는 갱신 시각 → 여기서는 넣은 값 유지 확인을 위해 별칭 단계만 본다
        sql.filter { it.contains("UPDATE title_alias") }.forEach { jdbc.sql(it).update() }
        assertNull(aliasCat("i48 곡|가수 - topic"), "Topic 키는 계속 보관")
        near(aliasCat("i48 곡|방송"), t)
        assertNull(aliasCat("i48 곡2|방송"), "판정 없는 대상이 쓰는 키")
        near(aliasCat("i48 곡3|방송"), t)
        // 나머지 채우기 문장도 오류 없이 돈다 (두 번 돌려도 같음)
        repeat(2) { sql.forEach { s -> jdbc.sql(s).update() } }
    }
}
