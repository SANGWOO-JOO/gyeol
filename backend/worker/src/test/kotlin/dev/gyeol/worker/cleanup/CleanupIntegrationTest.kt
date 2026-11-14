package dev.gyeol.worker.cleanup

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
class CleanupIntegrationTest {

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
    @Autowired lateinit var jdbc: JdbcClient

    private val t0 = Instant.parse("2026-10-01T00:00:00Z")

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM session").update()
    }

    /** t0에 만든 업로드 세션 (30일 보관), 원본 파일 1개 + 청취 기록 n건 (7일 보관) */
    private fun uploaded(n: Int = 10): Pair<Long, String> {
        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, t0.plus(Duration.ofDays(30)))
        val id = UUID.randomUUID()
        val key = "uploads/$id.json"
        uploads.create(id, sid, key)
        jdbc.sql("UPDATE upload SET created_at = :t WHERE id = :id").param("t", Timestamp.from(t0)).param("id", id).update()
        s3.putObject({ it.bucket(BUCKET).key(key) }, RequestBody.fromString("{}"))
        val events = (0 until n).map { ListenEvent("v$it", "곡 $it", "가수 - Topic", t0.minusSeconds(it * 60L), Source.MUSIC_APP) }
        listens.insertBatch(sid, id, events, t0.plus(Duration.ofDays(7)))
        return sid to key
    }

    private fun fileExists(key: String) = runCatching { s3.headObject { it.bucket(BUCKET).key(key) } }
        .fold({ true }, { if (it is NoSuchKeyException) false else throw it })

    private fun listenCount(sid: Long) =
        jdbc.sql("SELECT count(*) FROM listen_event WHERE session_id = :sid").param("sid", sid).query(Long::class.java).single()

    @Test
    fun `보관 기간 전에는 아무것도 지우지 않는다`() {
        val (sid, key) = uploaded()
        assertEquals(CleanupResult(0, 0, 0), cleanup.run(t0.plus(Duration.ofDays(6))))
        assertNotNull(sessions.findById(sid))
        assertEquals(10L, listenCount(sid))
        assert(fileExists(key))
    }

    @Test
    fun `7일이 지나면 원본 파일과 청취 기록만 지우고 세션은 남긴다`() {
        val (sid, key) = uploaded()
        assertEquals(CleanupResult(1, 10, 0), cleanup.run(t0.plus(Duration.ofDays(8))))
        assertNotNull(sessions.findById(sid))
        assertEquals(0L, listenCount(sid))
        assert(!fileExists(key))
        // 다시 돌려도 같은 결과 (이미 지운 파일은 다시 지우지 않음)
        assertEquals(CleanupResult(0, 0, 0), cleanup.run(t0.plus(Duration.ofDays(9))))
    }

    @Test
    fun `30일이 지나면 세션까지 지운다, 곡 고르기 세션도 마찬가지`() {
        val (sid, key) = uploaded()
        val seeds = sessions.create(Tokens.hash(Tokens.newToken()), Entry.SEEDS, t0.plus(Duration.ofDays(30)))
        val alive = sessions.create(Tokens.hash(Tokens.newToken()), Entry.SEEDS, t0.plus(Duration.ofDays(60)))

        // 한 번도 정리하지 못한 채 31일이 지나도 순서대로 모두 지워진다
        assertEquals(CleanupResult(1, 10, 2), cleanup.run(t0.plus(Duration.ofDays(31))))
        assertNull(sessions.findById(sid))
        assertNull(sessions.findById(seeds))
        assertNotNull(sessions.findById(alive))
        assert(!fileExists(key))
    }

    @Test
    fun `원본 파일을 못 지운 만료 세션은 남겨 둔다`() {
        val (sid, key) = uploaded(0)
        // S3 삭제가 실패해 file_deleted_at이 비어 있는 상황: 세션을 지우면 S3 키를 잃으므로 남겨야 한다
        assertEquals(0, sessions.deleteExpired(t0.plus(Duration.ofDays(31)), 100))
        assertNotNull(sessions.findById(sid))
        assert(fileExists(key))
    }

    @Test
    fun `음악 판정 캐시는 보관 기간이 지나면 지운다 (YouTube API 정책)`() {
        jdbc.sql("INSERT INTO video_check (video_id, category_id, checked_at) VALUES ('old-video-1', '10', :t), ('new-video-1', '10', :n)")
            .param("t", Timestamp.from(t0)).param("n", Timestamp.from(t0.plus(Duration.ofDays(20)))).update()
        assertEquals(1, cleanup.run(t0.plus(Duration.ofDays(31))).videoChecks)
        assertEquals(listOf("new-video-1"), jdbc.sql("SELECT video_id FROM video_check").query { rs, _ -> rs.getString(1) }.list())
        jdbc.sql("DELETE FROM video_check").update()
    }

    @Test
    fun `음악 판정 캐시 29일 경계 - 조회와 정리가 같은 기준이라 정리가 하루 늦어도 30일을 넘지 않는다 (I-48)`() {
        val now = t0.plus(Duration.ofDays(40))
        val edge = now.minus(VideoCheckRepository.RETENTION)
        assertEquals(Duration.ofDays(29), VideoCheckRepository.RETENTION)
        jdbc.sql("INSERT INTO video_check (video_id, category_id, checked_at) VALUES ('edge-old-1', '10', :o), ('edge-new-1', '10', :n)")
            .param("o", Timestamp.from(edge.minusSeconds(60))).param("n", Timestamp.from(edge.plusSeconds(60))).update()
        val repo = VideoCheckRepository(jdbc)
        // 조회: 29일이 막 지난 행은 쓰지 않는다 (다시 묻는다)
        assertEquals(setOf("edge-new-1"), repo.fresh(listOf("edge-old-1", "edge-new-1"), edge).keys)
        // 정리: 같은 기준으로 29일이 막 지난 행만 지운다
        assertEquals(1, cleanup.run(now).videoChecks)
        assertEquals(listOf("edge-new-1"), jdbc.sql("SELECT video_id FROM video_check").query { rs, _ -> rs.getString(1) }.list())
        jdbc.sql("DELETE FROM video_check").update()
    }

    @Test
    fun `청취 기록이 많아도 나눠서 모두 지운다`() {
        val (sid, _) = uploaded(12_000)
        assertEquals(12_000, cleanup.run(t0.plus(Duration.ofDays(8))).listens)
        assertEquals(0L, listenCount(sid))
    }

    private fun ts(i: Instant) = Timestamp.from(i)

    /** 시험용 방 하나 (만든 시각·열쇠 마지막 요청·노래 담은 시각을 정해서) */
    private fun room(createdAgo: Duration, keySeenAgo: Duration?, songAddedAgo: Duration?): Long {
        val now = Instant.now()
        val id = jdbc.sql("INSERT INTO room (public_id, name, created_at) VALUES (:p, 'I-56 시험 방', :c) RETURNING id")
            .param("p", "t" + UUID.randomUUID().toString().take(12)).param("c", ts(now.minus(createdAgo))).query(Long::class.java).single()
        val member = jdbc.sql("INSERT INTO member (room_id, nickname, role, color_idx, pin_hash) VALUES (:r, '방장', 'HOST', 0, 'x') RETURNING id")
            .param("r", id).query(Long::class.java).single()
        keySeenAgo?.let {
            jdbc.sql("INSERT INTO member_key (key_hash, member_id, created_at, last_seen_at) VALUES (:k, :m, :c, :s)")
                .param("k", UUID.randomUUID().toString().toByteArray()).param("m", member).param("c", ts(now.minus(createdAgo))).param("s", ts(now.minus(it))).update()
        }
        songAddedAgo?.let {
            val artist = jdbc.sql("INSERT INTO artist (mbid, name) VALUES (:m, 'I-56 가수') RETURNING id").param("m", UUID.randomUUID()).query(Long::class.java).single()
            val track = jdbc.sql("INSERT INTO track (recording_mbid, title, artist_id) VALUES (:m, 'I-56 곡', :a) RETURNING id").param("m", UUID.randomUUID()).param("a", artist).query(Long::class.java).single()
            jdbc.sql("INSERT INTO room_song (room_id, member_id, track_id, artist_id, added_at) VALUES (:r, :m, :t, :a, :at)")
                .param("r", id).param("m", member).param("t", track).param("a", artist).param("at", ts(now.minus(it))).update()
        }
        return id
    }

    private fun status(id: Long) = jdbc.sql("SELECT status FROM room WHERE id = :i").param("i", id).query(String::class.java).single()

    @Test
    fun `I-56 마지막 사용 1년 지난 방만 숨기고 감사 기록을 남긴다 - 열쇠 요청이나 노래 담기가 1년 안이면 그대로`() {
        val old = room(Duration.ofDays(400), Duration.ofDays(370), null)
        val noKey = room(Duration.ofDays(380), null, null)
        val keyRecent = room(Duration.ofDays(400), Duration.ofDays(10), null)
        val songRecent = room(Duration.ofDays(400), Duration.ofDays(370), Duration.ofDays(30))
        val fresh = room(Duration.ofDays(5), null, null)
        val now = Instant.now()

        val r = cleanup.run(now)

        assertEquals(2, r.unusedRooms, r.toString())
        assertEquals("HIDDEN", status(old)); assertEquals("HIDDEN", status(noKey))
        listOf(keyRecent, songRecent, fresh).forEach { assertEquals("ACTIVE", status(it)) }
        val purgeAt = jdbc.sql("SELECT purge_at FROM room WHERE id = :i").param("i", old).query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()
        assertTrue(Duration.between(now.plus(Duration.ofDays(7)), purgeAt).abs() < Duration.ofSeconds(5), "7일 뒤 완전 삭제 흐름")
        val audit = jdbc.sql("SELECT actor_type, detail ->> 'lastUsedAt' FROM audit_log WHERE room_id = :i AND action = 'ROOM_EXPIRE_HIDE'").param("i", old)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        assertEquals("SYSTEM", audit.first)
        assertNotNull(audit.second)
        assertEquals(0, cleanup.run(now).unusedRooms, "이미 숨긴 방은 다시 숨기지 않는다")
        // 7일 뒤 기존 흐름으로 완전 삭제
        cleanup.run(now.plus(Duration.ofDays(8)))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM room WHERE id IN (:i)").param("i", listOf(old, noKey)).query(Int::class.java).single())
        jdbc.sql("DELETE FROM room WHERE id IN (:i)").param("i", listOf(keyRecent, songRecent, fresh)).update()
    }

    @Test
    fun `I-57 90일 지난 메일 발송 기록은 지우고 관리자 세션 IP는 비운다`() {
        val now = Instant.now()
        jdbc.sql("INSERT INTO mail_send_log (email_hash, ip, sent_at) VALUES ('\\x01', '1.1.1.1', :o), ('\\x02', '2.2.2.2', :n)")
            .param("o", ts(now.minus(Duration.ofDays(91)))).param("n", ts(now.minus(Duration.ofDays(89)))).update()
        val admin = jdbc.sql("INSERT INTO admin_user (email, password_hash, totp_secret) VALUES (:e, 'x', 'x') RETURNING id")
            .param("e", "i57-${UUID.randomUUID()}@example.com").query(Long::class.java).single()
        jdbc.sql("INSERT INTO admin_session (token_hash, admin_id, stage, ip, created_at, expires_at) VALUES ('\\x0a', :a, 'ACTIVE', '3.3.3.3', :o, :o), ('\\x0b', :a, 'ACTIVE', '4.4.4.4', :n, :n)")
            .param("a", admin).param("o", ts(now.minus(Duration.ofDays(91)))).param("n", ts(now.minus(Duration.ofDays(1)))).update()

        assertEquals(2, cleanup.run(now).oldIps)

        assertEquals(listOf("2.2.2.2"), jdbc.sql("SELECT ip FROM mail_send_log ORDER BY id").query(String::class.java).list())
        assertEquals(listOf(null, "4.4.4.4"), jdbc.sql("SELECT ip FROM admin_session WHERE admin_id = :a ORDER BY id").param("a", admin).query { rs, _ -> rs.getString(1) }.list())
        assertEquals(2, jdbc.sql("SELECT count(*) FROM admin_session WHERE admin_id = :a").param("a", admin).query(Int::class.java).single(), "세션 행은 남긴다")
        jdbc.sql("DELETE FROM mail_send_log").update()
        jdbc.sql("DELETE FROM admin_user WHERE id = :a").param("a", admin).update()
    }
}
