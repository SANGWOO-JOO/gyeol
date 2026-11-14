package dev.gyeol.api

import dev.gyeol.api.mail.LogMailSender
import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.core.room.RoomSongRepository
import dev.gyeol.core.map.MapRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 음악 방 ① 방·참가·담기 (docs/todo/room-api-db.md). worker 쪽 식별은 DB에 직접 넣어 흉내 낸다 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomApiIntegrationTest {

    companion object {
        private const val SONG_QUEUE = "gyeol-test-room-song"
        private const val GIFT_QUEUE = "gyeol-test-gift-image"
        private const val CODE_QUEUE = "gyeol-test-code-match"
        // 테스트 전용 가짜 키·비밀번호·OTP 비밀값
        private const val ADMIN_KEY = "dGVzdC1vbmx5LWZha2Uta2V5LTAxMjM0NTY3ODlhYiE="
        private const val ADMIN_PW = "test-only-password"
        private const val ADMIN_TOTP = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        private const val COVERS = "gyeol-test-room-covers"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3", "sqs")

        // 가짜 Last.fm 검색: 항상 "새 노래 / 가수 A"
        private val fakeFetcher = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0).apply {
            // I-30 ②: 가수 인기곡. TAEYEON은 "곡 1~30" + 8282, 태연(한글 페이지)은 8282·시간아 멈춰라·곡 1
            createContext("/internal/lastfm/top-tracks") { ex ->
                val q = ex.requestURI.query.orEmpty()
                val page = Regex("page=(\\d+)").find(q)?.groupValues?.get(1)?.toInt() ?: 1
                val asked = Regex("artist=([^&]+)").find(q)?.groupValues?.get(1).orEmpty()
                fun t(title: String, plays: Long) = """{"title":"$title","artist":"$asked","mbid":null,"playcount":$plays,"listeners":1}"""
                val items = when {
                    page > 1 -> emptyList()
                    // I-41: 청취자 수가 다른 곡 (녹음 목록 대조·2% 규칙)
                    q.contains("artist=STAYC") -> listOf(
                        """{"title":"Teddy Bear","artist":"STAYC","mbid":null,"playcount":9000,"listeners":1000}""",
                        """{"title":"ASAP","artist":"STAYC","mbid":null,"playcount":8000,"listeners":900}""",
                        """{"title":"Big Song","artist":"STAYC","mbid":null,"playcount":5000,"listeners":500}""",
                        """{"title":"ASAP (Inst.)","artist":"STAYC","mbid":null,"playcount":4000,"listeners":400}""",
                        """{"title":"Celebrity","artist":"STAYC","mbid":null,"playcount":300,"listeners":5}""",
                        """{"title":"남의 노래","artist":"다른 가수","mbid":null,"playcount":7000,"listeners":800}""",
                    )
                    q.contains("artist=TAEYEON") -> listOf(t("8282", 1000)) + (1..30).map { t("곡 $it", 300L - it) }
                    q.contains("artist=태연") -> listOf(t("시간아 멈춰라", 900), t("8282", 500), t("곡 1", 100))
                    else -> emptyList()
                }
                val body = """{"items":[${items.joinToString(",")}],"total":${items.size}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            createContext("/internal/lastfm/search") { ex ->
                // 검색어에 "Pretty"가 있으면 스트리밍 앱 문구가 붙은 가수명 결과 (11-13 " • 잡음" 정리 확인)
                val noisy = ex.requestURI.rawQuery.orEmpty().contains("Pretty")
                val q = ex.requestURI.query.orEmpty()
                val body = (
                    if (q.contains("다비치") || q.contains("성시경")) {
                        // I-30 ①: 같은 가수 두 이름(다비치/DAVICHI), 같은 곡 다른 표기(Every Moment Of You/너의 모든 순간)
                        """{"items":[{"title":"8282","artist":"다비치","mbid":null,"listeners":9,"releaseYear":null},""" +
                            """{"title":"시간아 멈춰라","artist":"DAVICHI","mbid":null,"listeners":8,"releaseYear":null},""" +
                            """{"title":"시간아 멈춰라","artist":"다비치","mbid":null,"listeners":7,"releaseYear":null},""" +
                            """{"title":"Every Moment Of You","artist":"성시경","mbid":null,"listeners":6,"releaseYear":null},""" +
                            """{"title":"너의 모든 순간","artist":"Sung Si Kyung","mbid":null,"listeners":5,"releaseYear":null},""" +
                            """{"title":"너의 모든 순간","artist":"성시경(Sungsikyung)의 킬링보이스를 라이브로!","mbid":null,"listeners":4,"releaseYear":null}],"source":"lastfm","cached":false}"""
                    } else if (q.contains("준비가수")) {
                        // 미리 준비: 우리 DB에 없는 가수(Last.fm 결과만) → 카드 artistId 없음
                        """{"items":[""" + (1..3).joinToString(",") { """{"title":"준비 곡 $it","artist":"준비가수","mbid":null,"listeners":${10 - it},"releaseYear":null}""" } + """],"source":"lastfm","cached":false}"""
                    } else if (q.contains("더보기시험")) {
                        // 더보기: 서로 다른 곡 35개 중 limit만큼 (Last.fm처럼 limit이 커도 앞부분 순서는 같다). 첫 곡은 DB 곡과 같은 곡
                        val n = Regex("""limit=(\d+)""").find(q)!!.groupValues[1].toInt()
                        val items = (listOf("""{"title":"더보기시험 DB 0","artist":"더보기DB가수","mbid":null,"listeners":999,"releaseYear":null}""") +
                            (1 until 35).map { """{"title":"더보기시험 원격 $it","artist":"원격가수 $it","mbid":null,"listeners":${500 - it},"releaseYear":null}""" }).take(n)
                        """{"items":[${items.joinToString(",")}],"source":"lastfm","cached":false}"""
                    } else if (noisy) {
                        """{"items":[{"title":"Pretty Girl","artist":"Clairo • Lossless","mbid":null,"listeners":5,"releaseYear":null},""" +
                            """{"title":"Pretty Girl","artist":"Clairo","mbid":"0b0b0b0b-0000-0000-0000-000000000001","listeners":100,"releaseYear":null},""" +
                            """{"title":"Pretty Girl","artist":"Clairo • Recommended for you","mbid":null,"listeners":7,"releaseYear":null}],"source":"lastfm","cached":false}"""
                    } else {
                        """{"items":[{"title":"새 노래","artist":"가수 A","mbid":null,"listeners":10,"releaseYear":null}],"source":"lastfm","cached":false}"""
                    }
                    ).toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("gyeol.fetcher-url") { "http://localhost:${fakeFetcher.address.port}" }
            registry.add("spring.cloud.aws.endpoint") { localstack.endpoint.toString() }
            registry.add("spring.cloud.aws.region.static") { localstack.region }
            registry.add("spring.cloud.aws.credentials.access-key") { localstack.accessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { localstack.secretKey }
            registry.add("gyeol.room.song-queue") { SONG_QUEUE }
            registry.add("gyeol.room.gift-queue") { GIFT_QUEUE }
            registry.add("gyeol.room.code-match-queue") { CODE_QUEUE }
            registry.add("gyeol.room.code-match.daily-limit") { "3" }
            registry.add("gyeol.admin.totp-key") { ADMIN_KEY }
            registry.add("gyeol.admin.login-per-minute") { "1000" }
            registry.add("gyeol.cover-bucket") { COVERS }
            // 한 테스트에서 방을 여러 번 만들므로 IP 제한을 넉넉히
            registry.add("gyeol.room.draft-per-minute") { "1000" }
            registry.add("gyeol.room.join-per-minute") { "1000" }
            registry.add("gyeol.room.mail-per-ip-hour") { "1000" }
        }

        fun sqs(): SqsClient = SqsClient.builder()
            .endpointOverride(localstack.endpoint).region(Region.of(localstack.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)))
            .build()

        fun s3(): software.amazon.awssdk.services.s3.S3Client = software.amazon.awssdk.services.s3.S3Client.builder()
            .endpointOverride(localstack.endpoint).region(Region.of(localstack.region)).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)))
            .build()

        @BeforeAll
        @JvmStatic
        fun createQueue() {
            sqs().use { it.createQueue { q -> q.queueName(SONG_QUEUE) }; it.createQueue { q -> q.queueName(GIFT_QUEUE) }; it.createQueue { q -> q.queueName(CODE_QUEUE) } }
            s3().use { it.createBucket { b -> b.bucket(COVERS) } }
        }
    }

    @LocalServerPort var port: Int = 0
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mail: LogMailSender
    @Autowired lateinit var identify: IdentifyRepository
    @Autowired lateinit var songs: RoomSongRepository
    @Autowired lateinit var maps: MapRepository
    @Autowired lateinit var gifts: dev.gyeol.core.room.GiftRepository
    @Autowired lateinit var roomRepo: dev.gyeol.core.room.RoomRepository
    @Autowired lateinit var releases: dev.gyeol.core.identify.ArtistReleaseRepository
    @Autowired lateinit var knowledge: dev.gyeol.core.knowledge.KnowledgeRepository

    private val http = HttpClient.newHttpClient()

    data class Res(val status: Int, val body: JsonNode?, val headers: Map<String, List<String>>)

    private fun call(method: String, path: String, body: Any? = null, key: String? = null, headers: Map<String, String> = emptyMap()): Res {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Content-Type", "application/json")
        key?.let { b.header("X-Room-Key", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Res(res.statusCode(), res.body().takeIf { it.isNotBlank() }?.let { json.readTree(it) }, res.headers().map())
    }

    data class Joined(val rid: String, val key: String, val memberId: Long)

    private fun Res.joined() = Joined(body!!["roomId"].asString(), body["key"].asString(), body["member"]["id"].asLong())

    private fun newRoom(email: String = "host${UUID.randomUUID().toString().take(8)}@example.com", nickname: String = "상우", pin: String = "1234"): Joined {
        val draft = call("POST", "/api/room-drafts", mapOf("name" to "새벽 드라이브", "nickname" to nickname, "pin" to pin, "email" to email, "consent" to true))
        assertEquals(201, draft.status)
        val token = draft.body!!["draftToken"].asString()
        val res = call("POST", "/api/room-drafts/$token/verify", mapOf("code" to mail.outbox[email]!!))
        assertEquals(201, res.status)
        return res.joined()
    }

    private fun join(rid: String, nickname: String, pin: String = "5678") = call("POST", "/api/rooms/$rid/members", mapOf("nickname" to nickname, "pin" to pin))

    private fun track(title: String, artist: String = "가수 A"): Long = identify.upsertTrack(
        TrackInfo(UUID.randomUUID(), title, ArtistInfo(UUID.randomUUID(), artist, artist, emptyList()), UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single"),
    )

    @Test
    fun `방 만들기 - 메일 코드로 인증하면 방·방장·열쇠, 틀리면 남은 횟수, 5번이면 다시 받기, 다시 받기는 1분 뒤`() {
        val email = "make@example.com"
        val draft = call("POST", "/api/room-drafts", mapOf("name" to "우리 우주", "nickname" to "상우", "pin" to "1234", "email" to email, "consent" to true))
        assertEquals(201, draft.status)
        assertEquals("m***@example.com", draft.body!!["email"].asString(), "응답에는 가린 주소만")
        val token = draft.body["draftToken"].asString()
        // 코드 만료(10분)와 초안 만료(1시간)는 따로
        fun minutesLeft(v: JsonNode, field: String) = java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(v[field].asString())).toMinutes()
        assertTrue(minutesLeft(draft.body, "codeExpiresAt") in 9..10, draft.body.toString())
        assertTrue(minutesLeft(draft.body, "expiresAt") in 59..60)
        assertEquals(400, call("POST", "/api/room-drafts", mapOf("name" to "우리 우주", "nickname" to "상우", "pin" to "12a4", "email" to email, "consent" to true)).status)
        assertEquals(400, call("POST", "/api/room-drafts", mapOf("name" to "우리 우주", "nickname" to "상우", "pin" to "1234", "email" to email, "consent" to false)).status)

        val right = mail.outbox[email]!!
        val wrong = if (right == "000000") "111111" else "000000"
        val first = call("POST", "/api/room-drafts/$token/verify", mapOf("code" to wrong))
        assertEquals("CODE_MISMATCH", first.body!!["code"].asString())
        assertEquals(4, first.body["detail"]["attemptsLeft"].asInt())
        repeat(4) { call("POST", "/api/room-drafts/$token/verify", mapOf("code" to wrong)) }
        assertEquals("CODE_LOCKED", call("POST", "/api/room-drafts/$token/verify", mapOf("code" to right)).body!!["code"].asString())

        assertEquals("RESEND_TOO_SOON", call("POST", "/api/room-drafts/$token/resend").body!!["code"].asString())
        jdbc.sql("UPDATE email_code SET last_sent_at = now() - interval '2 minutes'").update()
        val resent = call("POST", "/api/room-drafts/$token/resend")
        assertEquals(200, resent.status)
        assertTrue(minutesLeft(resent.body!!, "codeExpiresAt") in 9..10, "다시 보내면 새 코드 기준 10분")
        val res = call("POST", "/api/room-drafts/$token/verify", mapOf("code" to mail.outbox[email]!!))
        assertEquals(201, res.status)
        val j = res.joined()
        val cookie = res.headers["set-cookie"]!!.single()
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("Path=/api/rooms/${j.rid}"), cookie)

        val summary = call("GET", "/api/rooms/${j.rid}").body!!
        assertEquals("우리 우주", summary["name"].asString())
        assertEquals(listOf("HOST"), summary["members"].toList().map { it["role"].asString() })
        assertEquals(email, jdbc.sql("SELECT email FROM room_host_email").query(String::class.java).list().single { it == email })
        // 한 번 쓴 draft는 다시 쓸 수 없다
        assertEquals(404, call("POST", "/api/room-drafts/$token/verify", mapOf("code" to "123456")).status)
    }

    @Test
    fun `참가 - 5명까지, 닉네임 중복은 대소문자 무시, 색은 빈 번호, 마감·삭제된 방`() {
        val host = newRoom()
        assertEquals(201, join(host.rid, "Minji").status)
        assertEquals("NICKNAME_TAKEN", join(host.rid, "minji").body!!["code"].asString())
        listOf("하은", "지수", "도윤").forEach { assertEquals(201, join(host.rid, it).status) }
        assertEquals("ROOM_FULL", join(host.rid, "여섯째").body!!["code"].asString())
        val colors = call("GET", "/api/rooms/${host.rid}").body!!["members"].toList().map { it["colorIdx"].asInt() }
        assertEquals(listOf(0, 1, 2, 3, 4), colors)

        val other = newRoom()
        assertEquals(200, call("PATCH", "/api/rooms/${other.rid}", mapOf("closed" to true), other.key).status)
        assertEquals("ROOM_CLOSED", join(other.rid, "민지").body!!["code"].asString())
        assertEquals(204, call("DELETE", "/api/rooms/${other.rid}", key = other.key).status)
        assertEquals(404, call("GET", "/api/rooms/${other.rid}").status)
        assertEquals(404, join(other.rid, "민지").status)
        val purgeIn = jdbc.sql("SELECT extract(day FROM purge_at - hidden_at)::int FROM room WHERE public_id = :p").param("p", other.rid).query(Int::class.java).single()
        assertEquals(7, purgeIn, "숨긴 뒤 7일에 완전 삭제")
    }

    @Test
    fun `열쇠 - 헤더·쿠키 모두, 다른 방 열쇠·나간 기기는 401, 링크만으로는 요약만`() {
        val a = newRoom()
        val b = newRoom()
        assertEquals(200, call("GET", "/api/rooms/${a.rid}/me", key = a.key).status)
        assertEquals(200, call("GET", "/api/rooms/${a.rid}/me", headers = mapOf("Cookie" to "gyeol_room=${a.key}")).status)
        assertEquals(401, call("GET", "/api/rooms/${a.rid}/me", key = b.key).status)
        assertEquals(401, call("GET", "/api/rooms/${a.rid}/map").status, "참가하지 않으면 지도를 볼 수 없다")
        assertEquals(200, call("GET", "/api/rooms/${a.rid}").status)

        assertEquals(204, call("DELETE", "/api/rooms/${a.rid}/session", key = a.key).status)
        assertEquals(401, call("GET", "/api/rooms/${a.rid}/me", key = a.key).status)
    }

    @Test
    fun `다시 들어오기 - 5번 틀리면 잠금, 방장이 새 암호를 주면 그 숫자로 들어오고 잠금도 풀린다`() {
        val host = newRoom()
        val minji = join(host.rid, "민지", "1111").joined()
        val path = "/api/rooms/${host.rid}/session"
        val first = call("POST", path, mapOf("memberId" to minji.memberId, "pin" to "2222"))
        assertEquals(401, first.status)
        assertEquals(4, first.body!!["detail"]["attemptsLeft"].asInt())
        repeat(3) { call("POST", path, mapOf("memberId" to minji.memberId, "pin" to "2222")) }
        assertEquals(423, call("POST", path, mapOf("memberId" to minji.memberId, "pin" to "2222")).status)
        val locked = call("POST", path, mapOf("memberId" to minji.memberId, "pin" to "1111"))
        assertEquals(423, locked.status, "잠긴 동안에는 맞는 암호도 거절")
        assertTrue(locked.body!!["detail"]["retryAfterSeconds"].asLong() > 0)

        // 참가자는 새 암호를 만들 수 없다
        assertEquals(403, call("POST", "/api/rooms/${host.rid}/members/${minji.memberId}/pin", key = minji.key).status)
        val pin = call("POST", "/api/rooms/${host.rid}/members/${minji.memberId}/pin", key = host.key).body!!["pin"].asString()
        assertTrue(Regex("^[0-9]{4}$").matches(pin))
        assertEquals(200, call("POST", path, mapOf("memberId" to minji.memberId, "pin" to pin)).status)
        assertEquals(200, call("GET", "/api/rooms/${host.rid}/me", key = minji.key).status, "기존 기기 열쇠는 유지")
        val audit = jdbc.sql("SELECT action FROM audit_log a JOIN room r ON r.id = a.room_id WHERE r.public_id = :p ORDER BY a.id").param("p", host.rid).query(String::class.java).list()
        assertEquals(listOf("ROOM_CREATE", "PIN_RESET"), audit)
    }

    @Test
    fun `담기 - DB 곡은 바로, 같은 곡 두 번은 409, 친구와 겹치면 2, Last fm 곡은 식별 요청, 하루 100곡, 빼도 한도는 그대로`() {
        val host = newRoom()
        val minji = join(host.rid, "민지").joined()
        val t1 = track("노래 1")
        val songsPath = "/api/rooms/${host.rid}/songs"

        val rev0 = call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!["rev"].asLong()
        val added = call("POST", songsPath, mapOf("trackId" to t1), host.key)
        assertEquals(201, added.status)
        assertEquals(1, added.body!!["song"]["sharedCount"].asInt())
        assertEquals(1, added.body["todayAdded"].asInt())
        assertEquals("ALREADY_ADDED", call("POST", songsPath, mapOf("trackId" to t1), host.key).body!!["code"].asString())
        assertEquals(2, call("POST", songsPath, mapOf("trackId" to t1), minji.key).body!!["song"]["sharedCount"].asInt())

        val map = call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!
        assertTrue(map["rev"].asLong() > rev0)
        assertEquals(listOf(host.memberId, minji.memberId), map["planets"][0]["songs"][0]["memberIds"].toList().map { it.asLong() })

        // 검색: 민지가 같은 가수를 담았으면 표시
        val searched = call("GET", "/api/rooms/${host.rid}/search?q=%EC%83%88%20%EB%85%B8%EB%9E%98", key = minji.key).body!!["items"]
        assertTrue(searched.toList().any { it["item"]["title"].asString() == "새 노래" && it["sameArtist"].toList().map { n -> n.asString() } == listOf("상우") })

        // Last.fm 곡: 202 + 큐 메시지, worker 흉내로 끝내면 DONE
        val req = call("POST", songsPath, mapOf("title" to "새 노래", "artist" to "가수 A"), host.key)
        assertEquals(202, req.status)
        val requestId = req.body!!["requestId"].asLong()
        // 같은 큐에 다른 테스트의 메시지(COVER·SCAN 등)가 10개 넘게 있을 수 있어 찾을 때까지 받는다
        val msg = sqs().use { c ->
            val url = c.getQueueUrl { it.queueName(SONG_QUEUE) }.queueUrl()
            var found: tools.jackson.databind.JsonNode? = null
            repeat(20) {
                if (found == null) found = c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(2) }.messages()
                    .map { json.readTree(it.body()) }.firstOrNull { it.path("requestId").asLong(-1) == requestId }
            }
            found ?: error("IDENTIFY 메시지 없음")
        }
        assertEquals("IDENTIFY", msg["type"].asString())
        assertEquals("IDENTIFYING", call("GET", "/api/rooms/${host.rid}/song-requests/$requestId", key = host.key).body!!["status"].asString())
        assertEquals(404, call("GET", "/api/rooms/${host.rid}/song-requests/$requestId", key = minji.key).status, "남의 요청은 못 본다")
        val t2 = track("새 노래")
        val rid = jdbc.sql("SELECT id FROM room WHERE public_id = :p").param("p", host.rid).query(Long::class.java).single()
        val songId = songs.add(rid, host.memberId, t2, songs.trackArtist(t2)!!)!!
        songs.finishRequest(requestId, "DONE", t2, songId, null)
        val done = call("GET", "/api/rooms/${host.rid}/song-requests/$requestId", key = host.key).body!!
        assertEquals("DONE", done["status"].asString())
        assertEquals("새 노래", done["song"]["title"].asString())
        assertTrue(done["gift"].isNull, "상자가 생기지 않았으면 gift는 null (I-51)")

        // 내 노래: 최근 담은 순, 커서
        val page1 = call("GET", "/api/rooms/${host.rid}/me/songs?limit=1", key = host.key).body!!
        assertEquals("새 노래", page1["items"][0]["title"].asString())
        val page2 = call("GET", "/api/rooms/${host.rid}/me/songs?limit=1&cursor=${page1["nextCursor"].asLong()}", key = host.key).body!!
        assertEquals("노래 1", page2["items"][0]["title"].asString())
        assertTrue(page2["nextCursor"].isNull)

        // 빼도 오늘 담은 수는 그대로 (빼고 다시 담기로 한도를 넘지 못하게)
        val before = call("GET", "/api/rooms/${host.rid}/me", key = host.key).body!!["todayAdded"].asInt()
        assertEquals(204, call("DELETE", "/api/rooms/${host.rid}/songs/$songId", key = host.key).status)
        assertEquals(404, call("DELETE", "/api/rooms/${host.rid}/songs/$songId", key = host.key).status)
        assertEquals(before, call("GET", "/api/rooms/${host.rid}/me", key = host.key).body!!["todayAdded"].asInt())

        // 하루 100곡
        jdbc.sql("UPDATE member_daily SET songs_added = 100 WHERE member_id = :m").param("m", host.memberId).update()
        assertEquals("DAILY_LIMIT", call("POST", songsPath, mapOf("trackId" to track("노래 3")), host.key).body!!["code"].asString())
    }

    @Test
    fun `내보내기 - 그 사람의 열쇠와 노래가 함께 사라지고 지도가 바뀐다`() {
        val host = newRoom()
        val minji = join(host.rid, "민지").joined()
        val t = track("함께 담은 노래")
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t), host.key)
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t), minji.key)
        assertEquals(403, call("DELETE", "/api/rooms/${host.rid}/members/${host.memberId}", key = minji.key).status)
        assertEquals(204, call("DELETE", "/api/rooms/${host.rid}/members/${minji.memberId}", key = host.key).status)
        assertEquals(401, call("GET", "/api/rooms/${host.rid}/me", key = minji.key).status)
        val map = call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!
        assertEquals(listOf(host.memberId), map["planets"][0]["songs"][0]["memberIds"].toList().map { it.asLong() })
        assertEquals(1, map["members"].size())
        // 같은 닉네임으로 다시 들어올 수 있다 (내보낸 사람은 중복 검사에서 빠짐)
        assertEquals(201, join(host.rid, "민지").status)
    }

    @Test
    fun `지도 - 바뀐 게 없으면 304, 담으면 새 rev`() {
        val host = newRoom()
        val first = call("GET", "/api/rooms/${host.rid}/map", key = host.key)
        val etag = first.headers["etag"]!!.single()
        assertEquals(304, call("GET", "/api/rooms/${host.rid}/map", key = host.key, headers = mapOf("If-None-Match" to etag)).status)
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to track("지도 노래")), host.key)
        val after = call("GET", "/api/rooms/${host.rid}/map", key = host.key, headers = mapOf("If-None-Match" to etag))
        assertEquals(200, after.status)
        assertEquals(1, after.body!!["planets"].size())
        assertTrue(after.body["placeholder"].asBoolean())
    }

    @Test
    fun `지도 판이 있으면 진짜 좌표·구역·근처 가수, 자리 없는 가수는 준비 중, 행성 자세히`() {
        val host = newRoom()
        val placedTrack = track("자리 있는 노래", "자리 가수")
        val newTrack = track("새 가수 노래", "처음 가수")
        val placedArtist = songs.trackArtist(placedTrack)!!
        val newArtist = songs.trackArtist(newTrack)!!
        val ghostArtist = songs.trackArtist(track("유령 노래", "근처 가수"))!!

        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 2, true) RETURNING id").query(Long::class.java).single()
        val galaxy = jdbc.sql("INSERT INTO galaxy (map_version, label, name, cx, cy) VALUES (:v, 0, '새벽 감성 발라드', 0.3, 0.3) RETURNING id").param("v", version).query(Long::class.java).single()
        fun v(d: Int, w: Float = 0f) = FloatArray(384) { if (it == d) 1f else if (it == 9) w else 0f }
        maps.saveVector(placedArtist, "fake", v(1), null, v(1, 0.1f), "a")
        maps.saveVector(ghostArtist, "fake", v(1), null, v(1, 0.2f), "b")
        maps.savePosition(version, placedArtist, 0.3, 0.31, galaxy, "UMAP")
        maps.savePosition(version, ghostArtist, 0.35, 0.33, galaxy, "UMAP")
        maps.ensureProfile(placedArtist, "KR")
        maps.saveDescription(placedArtist, "잔잔한 발라드", emptyList(), "fake")

        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to placedTrack), host.key)
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to newTrack), host.key)
        // 자리 없는 가수는 worker에 준비를 맡긴다 (한 번만)
        assertEquals(listOf("PENDING"), maps.prepareStatus(listOf(newArtist)).values.toList())
        assertTrue(maps.prepareStatus(listOf(placedArtist)).isEmpty())

        val map = call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!
        assertTrue(!map["placeholder"].asBoolean())
        val planets = map["planets"].toList().associateBy { it["artistId"].asLong() }
        assertEquals(0.3, planets[placedArtist]!!["x"].asDouble())
        assertEquals(galaxy, planets[placedArtist]!!["galaxyId"].asLong())
        assertTrue(planets[newArtist]!!["preparing"].asBoolean())
        assertEquals(listOf("새벽 감성 발라드"), map["galaxies"].toList().map { it["name"].asString() })
        assertEquals(listOf(ghostArtist), map["ghosts"].toList().map { it["artistId"].asLong() }, "방에 없는 가까운 가수")

        val detail = call("GET", "/api/rooms/${host.rid}/artists/$placedArtist", key = host.key).body!!
        assertEquals("잔잔한 발라드", detail["description"].asString())
        assertEquals("자리 있는 노래", detail["songs"][0]["title"].asString())
        assertEquals(ghostArtist, detail["nearby"][0]["artistId"].asLong())
        assertTrue(!detail["nearby"][0]["inRoom"].asBoolean())
        assertTrue(call("GET", "/api/rooms/${host.rid}/artists/$newArtist", key = host.key).body!!["preparing"].asBoolean())
        jdbc.sql("UPDATE map_version SET active = false").update()
    }

    @Test
    fun `동시에 6명이 참가해도 방장 포함 5명만 들어간다`() {
        val host = newRoom()
        val pool = Executors.newFixedThreadPool(6)
        val statuses = pool.invokeAll((1..6).map { i -> Callable { join(host.rid, "동시$i").status } }).map { it.get() }
        pool.shutdown()
        assertEquals(4, statuses.count { it == 201 })
        assertEquals(2, statuses.count { it == 409 })
        assertNotNull(call("GET", "/api/rooms/${host.rid}").body)
        assertEquals(5, call("GET", "/api/rooms/${host.rid}").body!!["members"].size())
    }

    @Test
    fun `선물 - 50곡째 담기에 상자, 내 정보 요약, 열기는 READY만, 캐릭터 그림은 같은 방만, 얼굴 바꾸기`() {
        val host = newRoom()
        val minji = join(host.rid, "민지").joined()
        val other = newRoom()
        val songsPath = "/api/rooms/${host.rid}/songs"
        repeat(49) { i -> assertNull(call("POST", songsPath, mapOf("trackId" to track("선물 $i")), host.key).body!!["gift"]?.takeUnless { it.isNull }) }
        val fiftieth = call("POST", songsPath, mapOf("trackId" to track("선물 49")), host.key)
        assertEquals(201, fiftieth.status)
        val gift = fiftieth.body!!["gift"]
        assertEquals(50, gift["milestone"].asInt())
        assertEquals("READY", gift["status"].asString())
        val boxId = gift["boxId"].asLong()

        val me = call("GET", "/api/rooms/${host.rid}/me", key = host.key).body!!["gift"]
        assertEquals(50, me["songCount"].asInt())
        assertEquals(50, me["remaining"].asInt())
        assertTrue(me["receivedToday"].asBoolean())
        assertEquals(boxId, me["readyBoxId"].asLong())

        // 열기: 남의 상자 404, READY만 202, 두 번째는 409, 큐에 상자 번호
        val open = "/api/rooms/${host.rid}/me/gifts/$boxId/open"
        assertEquals(404, call("POST", "/api/rooms/${minji.rid}/me/gifts/$boxId/open", key = minji.key).status)
        val opened = call("POST", open, key = host.key)
        assertEquals(202, opened.status)
        assertEquals("GENERATING", opened.body!!["box"]["status"].asString())
        assertEquals("GIFT_NOT_READY", call("POST", open, key = host.key).body!!["code"].asString())
        val msgs = sqs().use { c ->
            val url = c.getQueueUrl { it.queueName(GIFT_QUEUE) }.queueUrl()
            c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5) }.messages()
        }.map { json.readTree(it.body())["boxId"].asLong() }
        assertTrue(boxId in msgs)

        // worker 흉내: 그림을 S3에 올리고 캐릭터 저장
        val key = "characters/${host.memberId}/$boxId.png"
        s3().use { it.putObject({ r -> r.bucket(COVERS).key(key).contentType("image/png") }, software.amazon.awssdk.core.sync.RequestBody.fromBytes(byteArrayOf(1, 2, 3))) }
        val characterId = gifts.finishOpening(boxId, key, "prompt", "test", false)!!
        val detail = call("GET", "/api/rooms/${host.rid}/me/gifts/$boxId", key = host.key).body!!
        assertEquals("OPENED", detail["box"]["status"].asString())
        assertEquals(characterId, detail["character"]["id"].asLong())

        // 그림: 같은 방(민지)은 보이고, 다른 방은 404
        val imgPath = "/api/rooms/${host.rid}/characters/$characterId/image"
        val img = http.send(HttpRequest.newBuilder(URI("http://localhost:$port$imgPath")).header("X-Room-Key", minji.key).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, img.statusCode())
        assertEquals("image/png", img.headers().firstValue("Content-Type").get())
        assertEquals(listOf<Byte>(1, 2, 3), img.body().toList())
        assertEquals(404, call("GET", "/api/rooms/${other.rid}/characters/$characterId/image", key = other.key).status)

        // 기본 캐릭터는 내장 SVG
        val fallbackId = jdbc.sql("INSERT INTO gift_character (member_id, s3_key, model, is_fallback) VALUES (:m, 'preset:3', 'preset', true) RETURNING id")
            .param("m", host.memberId).query(Long::class.java).single()
        val svg = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/rooms/${host.rid}/characters/$fallbackId/image")).header("X-Room-Key", host.key).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, svg.statusCode())
        assertTrue(svg.body().startsWith("<svg"))

        // 얼굴 바꾸기: 내 캐릭터만, 참가자 목록에 보이고 지도 rev가 오른다, null이면 기본
        val rev = call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!["rev"].asLong()
        assertEquals(404, call("PUT", "/api/rooms/${minji.rid}/me/avatar", mapOf("characterId" to characterId), minji.key).status)
        assertEquals(characterId, call("PUT", "/api/rooms/${host.rid}/me/avatar", mapOf("characterId" to characterId), host.key).body!!["avatarCharacterId"].asLong())
        val members = call("GET", "/api/rooms/${host.rid}/members", key = minji.key).body!!
        assertEquals(characterId, members.toList().single { it["id"].asLong() == host.memberId }["avatarCharacterId"].asLong())
        assertTrue(call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!["rev"].asLong() > rev)
        assertTrue(call("PUT", "/api/rooms/${host.rid}/me/avatar", mapOf("characterId" to null), host.key).body!!["avatarCharacterId"]?.isNull ?: true)

        val list = call("GET", "/api/rooms/${host.rid}/me/gifts", key = host.key).body!!
        assertEquals(2, list["characters"].size())
        assertEquals(1, list["boxes"].size())
    }

    @Test
    fun `코드 맞추기 - 5곡부터, 점수는 바로 이유는 큐, 같은 입력은 재사용, 곡이 바뀌면 stale, 방당 하루 제한`() {
        val host = newRoom()
        val minji = join(host.rid, "민지").joined()
        val other = newRoom()
        val path = "/api/rooms/${host.rid}/code-match"
        val shared = (1..2).map { track("함께 $it", "같이 듣는 가수") }
        fun add(who: Joined, t: Long) = assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t), who.key).status)
        shared.forEach { add(host, it); add(minji, it) }
        (1..2).forEach { add(host, track("상우 $it", "상우 가수 $it")); add(minji, track("민지 $it", "민지 가수 $it")) }

        assertEquals("NOT_ENOUGH_SONGS", call("POST", path, mapOf("a" to host.memberId, "b" to minji.memberId), host.key).body!!["code"].asString())
        add(host, track("상우 3", "상우 가수 3")); add(minji, track("민지 3", "민지 가수 3"))
        assertEquals(400, call("POST", path, mapOf("a" to host.memberId, "b" to host.memberId), host.key).status)
        assertEquals(404, call("POST", path, mapOf("a" to host.memberId, "b" to other.memberId), host.key).status, "다른 방 사람")

        val first = call("POST", path, mapOf("a" to minji.memberId, "b" to host.memberId), minji.key)
        assertEquals(202, first.status)
        val v = first.body!!
        assertEquals(minOf(host.memberId, minji.memberId), v["a"]["memberId"].asLong(), "항상 id 작은 쪽이 a")
        assertEquals("REASONING", v["status"].asString())
        assertEquals("LOW", v["confidence"].asString())
        assertEquals(5, v["a"]["songCount"].asInt())
        assertEquals(2, v["sharedSongs"].asInt())
        assertEquals(setOf("같이 듣는 가수"), v["sharedArtists"].toList().map { it.asString() }.toSet())
        assertTrue(v["score"].asInt() in 1..100)
        val msgs = sqs().use { c ->
            val url = c.getQueueUrl { it.queueName(CODE_QUEUE) }.queueUrl()
            c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5) }.messages()
        }.map { json.readTree(it.body())["matchId"].asLong() }
        assertTrue(v["id"].asLong() in msgs)

        // 같은 입력: 200, 같은 결과 (하루 횟수에 안 셈)
        repeat(3) { assertEquals(200, call("POST", path, mapOf("a" to host.memberId, "b" to minji.memberId), host.key).status) }
        val got = call("GET", "$path?a=${host.memberId}&b=${minji.memberId}", key = minji.key).body!!
        assertEquals(v["id"].asLong(), got["id"].asLong())
        assertTrue(!got["stale"].asBoolean())

        // 곡이 바뀌면 stale → 다시 계산하면 새 결과, 방당 하루 3번(테스트 설정)을 넘으면 429
        add(minji, track("민지 4", "민지 가수 4"))
        assertTrue(call("GET", "$path?a=${host.memberId}&b=${minji.memberId}", key = host.key).body!!["stale"].asBoolean())
        assertEquals(202, call("POST", path, mapOf("a" to host.memberId, "b" to minji.memberId), host.key).status)
        add(minji, track("민지 5", "민지 가수 5"))
        assertEquals(202, call("POST", path, mapOf("a" to host.memberId, "b" to minji.memberId), host.key).status)
        add(minji, track("민지 6", "민지 가수 6"))
        assertEquals("CODE_MATCH_LIMIT", call("POST", path, mapOf("a" to host.memberId, "b" to minji.memberId), host.key).body!!["code"].asString())
        assertEquals(404, call("GET", "/api/rooms/${other.rid}/code-match?a=${host.memberId}&b=${minji.memberId}", key = other.key).status)
    }

    // ── ⑥ 관리자 ──

    private fun newAdmin(): String {
        val email = "admin${UUID.randomUUID().toString().take(6)}@example.com"
        val hash = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(ADMIN_PW)
        jdbc.sql("INSERT INTO admin_user (email, password_hash, totp_secret) VALUES (:e, :h, :t)")
            .param("e", email).param("h", hash).param("t", dev.gyeol.api.admin.TotpCipher(ADMIN_KEY).encrypt(ADMIN_TOTP)).update()
        return email
    }

    private fun otpNow(offset: Long = 0) =
        dev.gyeol.api.admin.Totp.code(dev.gyeol.api.admin.Totp.base32Decode(ADMIN_TOTP), dev.gyeol.api.admin.Totp.step(java.time.Instant.now()) + offset)

    /** 로그인해서 쿠키 헤더("gyeol_admin=…")를 돌려준다. 같은 30초 칸 코드는 다시 못 쓰므로 계정마다 한 번 */
    private fun adminLogin(email: String): String {
        val ch = call("POST", "/api/admin/login", mapOf("email" to email, "password" to ADMIN_PW))
        assertEquals(200, ch.status)
        val res = call("POST", "/api/admin/login/otp", mapOf("challenge" to ch.body!!["challenge"].asString(), "code" to otpNow()))
        assertEquals(200, res.status)
        val setCookie = res.headers.entries.first { it.key.equals("set-cookie", true) }.value.single()
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && setCookie.contains("Path=/api/admin"))
        return setCookie.substringBefore(';')
    }

    private fun admin(method: String, path: String, cookie: String, body: Any? = null, origin: String? = null) =
        call(method, path, body, headers = mapOf("Cookie" to cookie) + (origin?.let { mapOf("Origin" to it) } ?: emptyMap()))

    @Test
    fun `관리자 로그인 - 비밀번호 다음 OTP, 틀리면 같은 오류, 같은 코드 재사용 거절, 쿠키 없으면 401, 로그아웃`() {
        val email = newAdmin()
        assertEquals("ADMIN_LOGIN_FAILED", call("POST", "/api/admin/login", mapOf("email" to "nobody@example.com", "password" to ADMIN_PW)).body!!["code"].asString())
        assertEquals("ADMIN_LOGIN_FAILED", call("POST", "/api/admin/login", mapOf("email" to email, "password" to "wrong")).body!!["code"].asString())
        val ch = call("POST", "/api/admin/login", mapOf("email" to email.uppercase(), "password" to ADMIN_PW)).body!!["challenge"].asString()
        assertEquals(401, call("POST", "/api/admin/login/otp", mapOf("challenge" to ch, "code" to otpNow(5))).status)
        assertEquals(401, call("GET", "/api/admin/me", headers = mapOf("Cookie" to "gyeol_admin=$ch")).status, "OTP 전 challenge로는 못 들어온다")
        val ok = call("POST", "/api/admin/login/otp", mapOf("challenge" to ch, "code" to otpNow()))
        assertEquals(200, ok.status)
        val cookie = ok.headers.entries.first { it.key.equals("set-cookie", true) }.value.single().substringBefore(';')
        assertEquals(email, admin("GET", "/api/admin/me", cookie).body!!["email"].asString())
        assertEquals("no-store", admin("GET", "/api/admin/me", cookie).headers.entries.first { it.key.equals("cache-control", true) }.value.single())

        // 같은 코드로 다시: 새 challenge를 받아도 같은 30초 칸 코드는 거절
        val ch2 = call("POST", "/api/admin/login", mapOf("email" to email, "password" to ADMIN_PW)).body!!["challenge"].asString()
        assertEquals(401, call("POST", "/api/admin/login/otp", mapOf("challenge" to ch2, "code" to otpNow())).status)

        assertEquals(401, call("GET", "/api/admin/rooms").status)
        assertEquals(204, admin("POST", "/api/admin/logout", cookie).status)
        assertEquals(401, admin("GET", "/api/admin/me", cookie).status)
    }

    @Test
    fun `관리자 잠금 - 비밀번호·OTP 실패 합쳐 5번이면 15분, 맞는 비밀번호도 막힌다`() {
        val email = newAdmin()
        repeat(3) { call("POST", "/api/admin/login", mapOf("email" to email, "password" to "wrong")) }
        val ch = call("POST", "/api/admin/login", mapOf("email" to email, "password" to ADMIN_PW)).body!!["challenge"].asString()
        assertEquals(401, call("POST", "/api/admin/login/otp", mapOf("challenge" to ch, "code" to ("000000".takeIf { it != otpNow() } ?: "111111"))).status)
        val fifth = call("POST", "/api/admin/login/otp", mapOf("challenge" to ch, "code" to otpNow(9)))
        assertEquals("ADMIN_LOCKED", fifth.body!!["code"].asString())
        assertEquals("ADMIN_LOCKED", call("POST", "/api/admin/login", mapOf("email" to email, "password" to ADMIN_PW)).body!!["code"].asString())
        val until = jdbc.sql("SELECT extract(epoch FROM locked_until - now())::int FROM admin_user WHERE email = :e").param("e", email).query(Int::class.java).single()
        assertTrue(until in 14 * 60..15 * 60, "약 15분: $until 초")
        val fails = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'ADMIN_LOGIN_FAIL' AND actor_id = (SELECT id FROM admin_user WHERE email = :e)")
            .param("e", email).query(Int::class.java).single()
        assertEquals(5, fails, "실패도 감사 기록에 남는다 (401과 함께 되돌려지지 않음)")
        jdbc.sql("UPDATE admin_user SET locked_until = now() - interval '1 second' WHERE email = :e").param("e", email).update()
        assertEquals(200, call("POST", "/api/admin/login", mapOf("email" to email, "password" to ADMIN_PW)).status)
    }

    @Test
    fun `관리자 방 관리 - 목록·상세(이메일 가림), 닉네임 변경, 내보내기(방장 409), 숨김·되살리기, 감사 기록, 다른 출처 403`() {
        val cookie = adminLogin(newAdmin())
        val host = newRoom(email = "abcd${UUID.randomUUID().toString().take(4)}@gmail.com")
        val minji = join(host.rid, "민지").joined()
        assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to track("관리 노래")), minji.key).status)

        val list = admin("GET", "/api/admin/rooms?q=${host.rid}&sort=songs", cookie).body!!["items"]
        assertTrue(list.toList().any { it["publicId"].asString() == host.rid && it["memberCount"].asInt() == 2 && it["songCount"].asInt() == 1 && it["hostNickname"].asString() == "상우" })
        val detail = admin("GET", "/api/admin/rooms/${host.rid}", cookie).body!!
        assertTrue(detail["hostEmail"].asString().matches(Regex("a\\*\\*\\*@g\\*\\*\\*\\.com")), detail["hostEmail"].asString())
        assertTrue(!detail.has("recentSongs"), "관리자 상세에는 담긴 노래를 보이지 않는다 (I-19)")
        assertEquals(1, detail["room"]["songCount"].asInt(), "곡 수만")

        // 다른 출처: CORS 단계(본문이 JSON이 아님) 또는 관리자 Origin 확인에서 403
        val evil = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/admin/rooms/${host.rid}/members/${minji.memberId}"))
                .header("Content-Type", "application/json").header("Cookie", cookie).header("Origin", "https://evil.example")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("""{"nickname":"x"}""")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, evil.statusCode())
        assertEquals(204, admin("PATCH", "/api/admin/rooms/${host.rid}/members/${minji.memberId}", cookie, mapOf("nickname" to "민지2")).status)
        assertEquals("CANNOT_KICK_HOST", admin("DELETE", "/api/admin/rooms/${host.rid}/members/${host.memberId}", cookie).body!!["code"].asString())
        assertEquals(204, admin("DELETE", "/api/admin/rooms/${host.rid}/members/${minji.memberId}", cookie, mapOf("reason" to "테스트")).status)
        assertEquals(401, call("GET", "/api/rooms/${host.rid}/me", key = minji.key).status, "내보낸 사람의 열쇠는 폐기")
        assertEquals(204, admin("PATCH", "/api/admin/rooms/${host.rid}", cookie, mapOf("name" to "정리된 이름")).status)

        // 숨김 → 일반 화면에서 사라짐 → 되살리면 원래 상태(마감)로
        assertTrue(call("PATCH", "/api/rooms/${host.rid}", mapOf("closed" to true), host.key).status in 200..204)
        assertEquals(204, admin("POST", "/api/admin/rooms/${host.rid}/hide", cookie, mapOf("reason" to "신고")).status)
        assertEquals(404, call("GET", "/api/rooms/${host.rid}").status)
        assertEquals("ALREADY_HIDDEN", admin("POST", "/api/admin/rooms/${host.rid}/hide", cookie).body!!["code"].asString())
        assertEquals(204, admin("POST", "/api/admin/rooms/${host.rid}/restore", cookie).status)
        assertEquals("CLOSED", call("GET", "/api/rooms/${host.rid}").body!!["status"].asString())

        // 7일이 지난 숨긴 방은 되살릴 수 없다
        assertEquals(204, admin("POST", "/api/admin/rooms/${host.rid}/hide", cookie).status)
        jdbc.sql("UPDATE room SET purge_at = now() - interval '1 minute' WHERE public_id = :p").param("p", host.rid).update()
        assertEquals("RESTORE_NOT_ALLOWED", admin("POST", "/api/admin/rooms/${host.rid}/restore", cookie).body!!["code"].asString())

        val audit = admin("GET", "/api/admin/audit?room=${host.rid}", cookie).body!!.toList()
        val actions = audit.map { it["action"].asString() }
        assertTrue(listOf("ADMIN_MEMBER_RENAME", "ADMIN_MEMBER_KICK", "ADMIN_ROOM_RENAME", "ADMIN_ROOM_HIDE", "ADMIN_ROOM_RESTORE", "ROOM_CREATE").all { it in actions }, actions.toString())
        val rename = audit.first { it["action"].asString() == "ADMIN_MEMBER_RENAME" }["detail"]
        assertEquals("민지", rename["oldNickname"].asString())
        assertTrue(rename.has("ip"))

        // 완전 삭제: 감사 기록은 남고(room_id NULL, 방 공개 id·이름 유지) IP·닉네임은 지워진다
        roomRepo.purgeHidden()
        val after = admin("GET", "/api/admin/audit?room=${host.rid}", cookie).body!!.toList()
        assertEquals(audit.size, after.size)
        assertTrue(after.all { it["roomId"].isNull && it["detail"]["roomPublicId"].asString() == host.rid })
        assertTrue(after.none { d -> listOf("ip", "nickname", "oldNickname", "newNickname").any { d["detail"].has(it) } })
        assertTrue(after.any { it["detail"]["roomName"].asString() == "정리된 이름" })
    }

    @Test
    fun `방장 암호 재설정 - 등록 메일로 코드, 주소는 안 보임, 틀림·잠금·만료·다른 방, 새 암호로 들어오고 열쇠 발급`() {
        val email = "reset${UUID.randomUUID().toString().take(6)}@example.com"
        val host = newRoom(email = email, pin = "1234")
        val other = newRoom()
        val sendPath = "/api/rooms/${host.rid}/host-reset"
        val verifyPath = "$sendPath/verify"
        fun backdateSend() = jdbc.sql("UPDATE email_code SET last_sent_at = now() - interval '2 minutes' WHERE purpose = 'HOST_RESET'").update()

        assertEquals(404, call("POST", "/api/rooms/zzzzzzzzzz/host-reset").status)
        val sent = call("POST", sendPath)
        assertEquals(202, sent.status)
        assertTrue(sent.body!!.propertyNames().asSequence().toSet() == setOf("codeExpiresAt", "resendAfter"), "이메일 주소는 응답에 없다: ${sent.body}")
        val left = java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(sent.body["codeExpiresAt"].asString())).toMinutes()
        assertTrue(left in 9..10)
        assertEquals("RESEND_TOO_SOON", call("POST", sendPath).body!!["code"].asString())
        val code = mail.outbox[email]!!
        val wrong = if (code == "000000") "111111" else "000000"

        assertEquals(400, call("POST", verifyPath, mapOf("code" to code, "newPin" to "12a4")).status, "새 암호 형식")
        assertEquals("CODE_EXPIRED", call("POST", "/api/rooms/${other.rid}/host-reset/verify", mapOf("code" to code, "newPin" to "9876")).body!!["code"].asString(), "다른 방에는 이 코드가 없다")
        val mismatch = call("POST", verifyPath, mapOf("code" to wrong, "newPin" to "9876"))
        assertEquals("CODE_MISMATCH", mismatch.body!!["code"].asString())
        assertEquals(4, mismatch.body["detail"]["attemptsLeft"].asInt())

        val ok = call("POST", verifyPath, mapOf("code" to code, "newPin" to "9876"))
        assertEquals(200, ok.status)
        assertEquals(host.memberId, ok.body!!["member"]["id"].asLong())
        assertTrue(ok.headers["set-cookie"]!!.single().contains("Path=/api/rooms/${host.rid}"))
        assertEquals(200, call("GET", "/api/rooms/${host.rid}/me", key = ok.body["key"].asString()).status, "새 열쇠")
        assertEquals(401, call("GET", "/api/rooms/${host.rid}/me", key = host.key).status, "다른 기기 열쇠는 폐기 (I-10)")
        assertEquals("PIN_MISMATCH", call("POST", "/api/rooms/${host.rid}/session", mapOf("memberId" to host.memberId, "pin" to "1234")).body!!["code"].asString())
        assertEquals(200, call("POST", "/api/rooms/${host.rid}/session", mapOf("memberId" to host.memberId, "pin" to "9876")).status)
        assertEquals("CODE_EXPIRED", call("POST", verifyPath, mapOf("code" to code, "newPin" to "5555")).body!!["code"].asString(), "쓴 코드는 다시 못 쓴다")
        val actions = jdbc.sql("SELECT action FROM audit_log WHERE detail ->> 'roomPublicId' = :p").param("p", host.rid).query(String::class.java).list()
        assertTrue("HOST_RESET_SENT" in actions && "HOST_PIN_RESET" in actions, actions.toString())
        val revoked = jdbc.sql("SELECT (detail ->> 'revokedKeys')::int FROM audit_log WHERE action = 'HOST_PIN_RESET' AND detail ->> 'roomPublicId' = :p")
            .param("p", host.rid).query(Int::class.java).single()
        assertEquals(1, revoked, "방 만들 때 받은 열쇠 1개")

        // 5번 틀리면 맞는 코드도 잠김
        backdateSend()
        assertEquals(202, call("POST", sendPath).status)
        val code2 = mail.outbox[email]!!
        val wrong2 = if (code2 == "000000") "111111" else "000000"
        repeat(5) { call("POST", verifyPath, mapOf("code" to wrong2, "newPin" to "1111")) }
        assertEquals("CODE_LOCKED", call("POST", verifyPath, mapOf("code" to code2, "newPin" to "1111")).body!!["code"].asString())

        // 만료
        backdateSend()
        assertEquals(202, call("POST", sendPath).status)
        val code3 = mail.outbox[email]!!
        jdbc.sql("UPDATE email_code SET expires_at = now() - interval '1 second' WHERE purpose = 'HOST_RESET' AND used_at IS NULL").update()
        assertEquals("CODE_EXPIRED", call("POST", verifyPath, mapOf("code" to code3, "newPin" to "1111")).body!!["code"].asString())
    }

    @Test
    fun `검색 - Last fm 가수명의 점 뒤 잡음을 잘라 같은 곡은 한 줄로, 방 검색과 곡 고르기 검색 모두`() {
        val host = newRoom()
        val room = call("GET", "/api/rooms/${host.rid}/search?q=Pretty%20Girl", key = host.key).body!!["items"].toList()
        assertEquals(1, room.size, room.toString())
        assertEquals("Clairo", room.single()["item"]["artist"].asString())
        assertEquals("0b0b0b0b-0000-0000-0000-000000000001", room.single()["item"]["mbid"].asString(), "mbid가 있는 쪽을 남긴다")
        val pick = call("GET", "/api/tracks/search?q=Pretty%20Girl").body!!["items"].toList()
        assertEquals(listOf("Clairo"), pick.map { it["artist"].asString() })
    }

    @Test
    fun `선물 상자 genreMix - 열린 상자만, 연 시각까지 담은 노래의 구역 비율, 가수 이름 없음`() {
        val host = newRoom()
        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 2, true) RETURNING id").query(Long::class.java).single()
        val city = jdbc.sql("INSERT INTO galaxy (map_version, label, name, cx, cy) VALUES (:v, 0, '시티팝', 0.2, 0.2) RETURNING id").param("v", version).query(Long::class.java).single()
        val dance = jdbc.sql("INSERT INTO galaxy (map_version, label, name, cx, cy) VALUES (:v, 1, '댄스 팝', 0.8, 0.8) RETURNING id").param("v", version).query(Long::class.java).single()
        fun addIn(galaxy: Long, n: Int, artist: String) {
            val first = track("$artist 0", artist)
            maps.savePosition(version, songs.trackArtist(first)!!, 0.5, 0.5, galaxy, "UMAP")
            assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to first), host.key).status)
            (1 until n).forEach { i ->
                // 같은 가수의 다른 곡 (track()은 부를 때마다 새 가수를 만들므로 같은 가수로 맞춘다)
                val t = track("$artist $i", artist)
                jdbc.sql("UPDATE track SET artist_id = :a WHERE id = :t").param("a", songs.trackArtist(first)!!).param("t", t).update()
                assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t), host.key).status)
            }
        }
        addIn(city, 3, "시티 가수")
        addIn(dance, 2, "댄스 가수")
        val boxId = jdbc.sql("INSERT INTO gift_box (member_id, milestone, status, ready_on) VALUES (:m, 50, 'READY', current_date) RETURNING id")
            .param("m", host.memberId).query(Long::class.java).single()
        assertTrue(call("GET", "/api/rooms/${host.rid}/me/gifts/$boxId", key = host.key).body!!["genreMix"].isEmpty, "열기 전에는 비어 있다")
        assertEquals(202, call("POST", "/api/rooms/${host.rid}/me/gifts/$boxId/open", key = host.key).status)
        gifts.finishOpening(boxId, "preset:1", null, "preset", true)
        // 연 뒤에 담은 노래는 세지 않는다
        jdbc.sql("UPDATE room_song SET added_at = now() - interval '2 minutes' WHERE member_id = :m").param("m", host.memberId).update()
        jdbc.sql("UPDATE gift_box SET opened_at = now() - interval '1 minute' WHERE id = :b").param("b", boxId).update()
        addIn(dance, 2, "나중 가수")

        val mix = call("GET", "/api/rooms/${host.rid}/me/gifts/$boxId", key = host.key).body!!["genreMix"].toList()
        assertEquals(listOf("시티팝" to 3, "댄스 팝" to 2), mix.map { it["galaxy"].asString() to it["songs"].asInt() })
        assertTrue(mix.none { it.toString().contains("가수") }, "가수 이름 없음")
        jdbc.sql("UPDATE map_version SET active = false WHERE id = :v").param("v", version).update()
    }

    @Test
    fun `선물 하루 새 그림 - 남은 장수(한도 10 - 오늘 만든 수), 기본 캐릭터 이유 DAILY_LIMIT·FAILED`() {
        val host = newRoom()
        jdbc.sql("DELETE FROM llm_call WHERE purpose = 'GIFT_IMAGE'").update()
        repeat(3) { jdbc.sql("INSERT INTO llm_call (purpose, model, items, input_tokens, output_tokens, latency_ms) VALUES ('GIFT_IMAGE', 'test', 1, 0, 0, 0)").update() }
        val me = call("GET", "/api/rooms/${host.rid}/me", key = host.key).body!!["gift"]
        assertEquals(10, me["imageDailyLimit"].asInt())
        assertEquals(7, me["imagesLeftToday"].asInt())

        val boxId = jdbc.sql("INSERT INTO gift_box (member_id, milestone, status, ready_on) VALUES (:m, 50, 'READY', current_date) RETURNING id")
            .param("m", host.memberId).query(Long::class.java).single()
        assertEquals(7, call("GET", "/api/rooms/${host.rid}/me/gifts/$boxId", key = host.key).body!!["imagesLeftToday"].asInt())
        repeat(10) { jdbc.sql("INSERT INTO llm_call (purpose, model, items, input_tokens, output_tokens, latency_ms) VALUES ('GIFT_IMAGE', 'test', 1, 0, 0, 0)").update() }
        assertEquals(0, call("GET", "/api/rooms/${host.rid}/me", key = host.key).body!!["gift"]["imagesLeftToday"].asInt(), "0 아래로 안 내려감")
        val opened = call("POST", "/api/rooms/${host.rid}/me/gifts/$boxId/open", key = host.key)
        assertEquals(202, opened.status, "0이어도 기본 캐릭터로 지금 받기는 열 수 있다")
        assertEquals(0, opened.body!!["imagesLeftToday"].asInt())
        gifts.finishOpening(boxId, "preset:2", null, "preset:DAILY_LIMIT", true)
        assertEquals("DAILY_LIMIT", call("GET", "/api/rooms/${host.rid}/me/gifts/$boxId", key = host.key).body!!["character"]["fallbackReason"].asString())

        val failedId = jdbc.sql("INSERT INTO gift_character (member_id, s3_key, model, is_fallback) VALUES (:m, 'preset:1', 'preset:FAILED', true) RETURNING id")
            .param("m", host.memberId).query(Long::class.java).single()
        val chars = call("GET", "/api/rooms/${host.rid}/me/gifts", key = host.key).body!!["characters"].toList()
        assertEquals("FAILED", chars.single { it["id"].asLong() == failedId }["fallbackReason"].asString())
        jdbc.sql("DELETE FROM llm_call WHERE purpose = 'GIFT_IMAGE' AND model = 'test'").update()
    }

    @Test
    fun `I-30 가수 묶기 - 다비치·DAVICHI는 한 가수, 같은 곡 다른 표기는 한 줄, 가수 카드`() {
        val host = newRoom()
        val davichi = ArtistInfo(UUID.randomUUID(), "DAVICHI", "DAVICHI", listOf("다비치", "Davichi"))
        val t8282 = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "8282", davichi, UUID.randomUUID(), UUID.randomUUID(), "음반", "2009-01-01", "Single"))
        val ssk = ArtistInfo(UUID.randomUUID(), "성시경", "Sung Si Kyung", listOf("Sung Si Kyung"))
        val moment = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "너의 모든 순간 (Every Moment of You)", ssk, UUID.randomUUID(), UUID.randomUUID(), "음반", "2014-02-12", "Single"))
        assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t8282), host.key).status)

        val r = call("GET", "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("다비치", "UTF-8"), key = host.key).body!!
        val items = r["items"].toList().map { it["item"] }
        val davichiId = songs.trackArtist(t8282)!!
        assertEquals(1, items.count { it["title"].asString() == "8282" }, "DB 곡과 Last.fm 8282는 한 줄: $items")
        assertEquals(t8282, items.single { it["title"].asString() == "8282" }["trackId"].asLong())
        val stop = items.filter { it["title"].asString() == "시간아 멈춰라" }
        assertEquals(1, stop.size, "DAVICHI·다비치 두 Last.fm 결과가 한 가수로 합쳐짐")
        assertEquals(davichiId, stop.single()["artistId"].asLong())
        assertEquals(1, items.count { it["trackId"]?.asLong(-1) == moment }, "Every Moment Of You·너의 모든 순간(Sung Si Kyung)은 DB 곡 하나로")
        assertEquals(0, items.count { it["title"].asString() == "Every Moment Of You" || it["title"].asString() == "너의 모든 순간" && it["trackId"].isNull })
        assertEquals(1, items.map { it["artist"].asString() }.filter { it in setOf("다비치", "DAVICHI") }.distinct().size, "같은 가수는 한 표기로")
        assertTrue(items.none { it["artist"].asString().contains("킬링보이스") }, "영상 제목 같은 가수 이름은 거름 (I-41)")

        val card = r["artist"]
        assertEquals(davichiId, card["artistId"].asLong())
        assertEquals(1, card["songCountInRoom"].asInt())
        assertTrue(!card["onMap"].asBoolean())
        // 가수와 상관없는 검색어에는 카드 없음
        val other = call("GET", "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("새 노래", "UTF-8"), key = host.key).body!!
        assertTrue(other["artist"] == null || other["artist"].isNull)
    }

    @Test
    fun `I-30 인기순 - 한글·영문 페이지 합치기, 20곡씩, 우리 곡과 맞춤, 담음·친구 표시, 이름으로도`() {
        val host = newRoom()
        val minji = join(host.rid, "민지").joined()
        // (I-30 ① 테스트의 DAVICHI와 섞이지 않게 다른 가수)
        val taeyeon = ArtistInfo(UUID.randomUUID(), "TAEYEON", "TAEYEON", listOf("태연"))
        val t8282 = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "8282", taeyeon, UUID.randomUUID(), UUID.randomUUID(), "음반", "2009-01-01", "Single"))
        val aid = songs.trackArtist(t8282)!!
        assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t8282), host.key).status)

        val p1 = call("GET", "/api/rooms/${host.rid}/artists/$aid/songs?sort=popular&page=1", key = minji.key).body!!
        assertEquals(aid, p1["artist"]["artistId"].asLong())
        val items = p1["items"].toList()
        assertEquals(20, items.size)
        assertTrue(p1["hasMore"].asBoolean())
        // 8282(1000+500)가 1위, 시간아 멈춰라(900)가 2위, 곡 1(299+100)은 한 줄
        assertEquals(listOf("8282", "시간아 멈춰라", "곡 1"), items.take(3).map { it["item"]["title"].asString() })
        assertEquals(t8282, items[0]["item"]["trackId"].asLong())
        assertEquals(listOf("상우"), items[0]["sameSong"].toList().map { it.asString() }, "친구(상우)가 담은 곡")
        assertTrue(!items[0]["mine"].asBoolean())
        assertEquals(1, items[0]["rank"].asInt())
        assertTrue(items.all { it["item"]["artistId"].asLong() == aid })

        val p2 = call("GET", "/api/rooms/${host.rid}/artists/$aid/songs?sort=popular&page=2", key = host.key).body!!
        assertEquals(12, p2["items"].size(), "합쳐서 32곡(8282·곡 1~30·시간아 멈춰라): 20 + 12")
        assertTrue(!p2["hasMore"].asBoolean())
        assertEquals(21, p2["items"][0]["rank"].asInt())
        assertTrue(p2["items"].toList().none { it["item"]["title"].asString() in setOf("8282", "곡 1") }, "앞 쪽과 겹치지 않음")
        assertTrue(call("GET", "/api/rooms/${host.rid}/artists/$aid/songs?sort=popular&page=1", key = host.key).body!!["items"][0]["mine"].asBoolean())

        // 이름으로 와도 우리 가수와 맞으면 같은 결과, 모르는 정렬은 400, 다른 방 열쇠는 401
        assertEquals(aid, call("GET", "/api/rooms/${host.rid}/artist-songs?name=" + java.net.URLEncoder.encode("태연", "UTF-8") + "&page=1", key = host.key).body!!["artist"]["artistId"].asLong())
        assertEquals(400, call("GET", "/api/rooms/${host.rid}/artists/$aid/songs?sort=xyz", key = host.key).status)
        assertEquals(404, call("GET", "/api/rooms/${host.rid}/artists/99999999/songs", key = host.key).status)
    }

    @Test
    fun `I-30 최신순 - 처음 열면 모으기 요청 1번, 모으는 중엔 저장된 것만, 날짜순·날짜 없는 곡 맨 뒤·판 표시 숨김, 7일 뒤 다시`() {
        val host = newRoom()
        val iu = ArtistInfo(UUID.randomUUID(), "IU", "IU", listOf("아이유"))
        val recMbid = UUID.randomUUID()
        val trackId = identify.upsertTrack(TrackInfo(recMbid, "밤편지", iu, UUID.randomUUID(), UUID.randomUUID(), "음반", "2017-03-24", "Single"))
        val aid = songs.trackArtist(trackId)!!
        val path = "/api/rooms/${host.rid}/artists/$aid/songs?sort=latest"

        val first = call("GET", "$path&page=1", key = host.key).body!!
        assertEquals("RUNNING", first["scan"].asString())
        assertEquals(0, first["items"].size())
        // 같은 큐에 다른 시험의 메시지가 쌓여 있어 찾을 때까지 받는다 (받은 메시지는 지운다)
        val msgs = sqs().use { c ->
            val url = c.getQueueUrl { it.queueName(SONG_QUEUE) }.queueUrl()
            val found = mutableListOf<tools.jackson.databind.JsonNode>()
            repeat(20) {
                val got = c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1) }.messages()
                got.forEach { m -> c.deleteMessage { d -> d.queueUrl(url).receiptHandle(m.receiptHandle()) } }
                found += got.map { json.readTree(it.body()) }.filter { it.path("type").asString("") == "SCAN_ARTIST_RELEASES" && it["artistId"].asLong() == aid }
                if (got.isEmpty()) return@repeat
            }
            found
        }
        assertEquals(1, msgs.size)
        call("GET", "$path&page=1", key = host.key) // 다시 열어도 다시 요청하지 않음
        assertEquals("QUEUED", releases.scan(aid)!!.status)

        // worker 흉내: 모으는 중 일부 저장 → 저장된 것만 보임
        releases.startScan(aid)
        releases.upsert(aid, recMbid, "밤편지", "밤편지", "2017-03-24", false)
        releases.upsert(aid, UUID.randomUUID(), "날짜 없는 곡", "날짜없는곡", null, false)
        val running = call("GET", "$path&page=1", key = host.key).body!!
        assertEquals("RUNNING", running["scan"].asString())
        assertEquals(listOf("밤편지", "날짜 없는 곡"), running["items"].toList().map { it["item"]["title"].asString() })
        assertEquals(trackId, running["items"][0]["item"]["trackId"].asLong(), "우리 곡이면 trackId")
        assertEquals("2017-03-24", running["items"][0]["releaseDate"].asString())
        assertTrue(running["items"][1]["releaseDate"].isNull)

        (1..25).forEach { releases.upsert(aid, UUID.randomUUID(), "곡 $it", "곡$it", "2020-01-${"%02d".format(it)}", false) }
        releases.upsert(aid, UUID.randomUUID(), "밤편지 (Live)", "밤편지live", "2024-01-01", true)
        releases.finishScan(aid, true, 30, 28)
        val p1 = call("GET", "$path&page=1", key = host.key).body!!
        assertEquals("DONE", p1["scan"].asString())
        assertEquals(20, p1["items"].size())
        assertTrue(p1["hasMore"].asBoolean())
        assertEquals("곡 25", p1["items"][0]["item"]["title"].asString(), "가장 최근(2020-01-25)이 먼저")
        assertTrue(p1["items"].toList().none { it["item"]["title"].asString().contains("Live") }, "판 표시 숨김")
        val p2 = call("GET", "$path&page=2", key = host.key).body!!
        assertEquals(7, p2["items"].size())
        assertTrue(!p2["hasMore"].asBoolean())
        assertEquals("날짜 없는 곡", p2["items"].toList().last()["item"]["title"].asString())

        // 7일 지나면 다시 요청
        jdbc.sql("UPDATE artist_release_scan SET finished_at = now() - interval '8 days' WHERE artist_id = :a").param("a", aid).update()
        assertEquals("RUNNING", call("GET", "$path&page=1", key = host.key).body!!["scan"].asString())
        assertEquals("QUEUED", releases.scan(aid)!!.status)
        // DB에 없는 가수(이름만)는 최신순 400
        assertEquals(400, call("GET", "/api/rooms/${host.rid}/artist-songs?name=zzz&sort=latest", key = host.key).status)
        // 우리 가수와 맞는 이름이어도 이름 주소의 최신순은 400 (I-39, 최신순은 가수 id 주소로만)
        assertEquals(400, call("GET", "/api/rooms/${host.rid}/artist-songs?name=IU&sort=latest", key = host.key).status)
    }

    @Test
    fun `I-31 바로 담은 DB 곡이 표지 확인 전이면 room-song 큐에 COVER, 확인한 곡은 보내지 않음`() {
        val host = newRoom()
        val unchecked = track("표지 확인 전")
        val checked = track("표지 확인 후")
        jdbc.sql("UPDATE track SET cover_checked_at = now() WHERE id = :id").param("id", checked).update()
        assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to unchecked), host.key).status)
        assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to checked), host.key).status)
        val covers = sqs().use { c ->
            val url = c.getQueueUrl { it.queueName(SONG_QUEUE) }.queueUrl()
            val found = mutableListOf<Long>()
            repeat(20) {
                val got = c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1) }.messages()
                got.forEach { m -> c.deleteMessage { d -> d.queueUrl(url).receiptHandle(m.receiptHandle()) } }
                got.map { json.readTree(it.body()) }.filter { it.path("type").asString("") == "COVER" }.forEach { n -> found += n["trackIds"].toList().map { it.asLong() } }
                if (got.isEmpty()) return@repeat
            }
            found
        }
        assertTrue(unchecked in covers, covers.toString())
        assertTrue(checked !in covers)
    }

    @Test
    fun `I-34 저장할 때 가수 설명의 근거 번호와 근거 조각의 위키 각주를 지운다`() {
        val a = ArtistInfo(UUID.randomUUID(), "각주 가수", "각주 가수", emptyList())
        val aid = songs.trackArtist(identify.upsertTrack(TrackInfo(UUID.randomUUID(), "각주 노래", a, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020", "Single")))!!
        maps.ensureProfile(aid, "KR")
        maps.saveDescription(aid, "록 음악을 선보인다. [653][654]", listOf(653L, 654L), "test")
        assertEquals("록 음악을 선보인다.", maps.detail(aid)!!.description)
        assertEquals(listOf(653L, 654L), maps.detail(aid)!!.evidence, "근거 번호는 evidence 칸에 그대로")

        knowledge.saveEntity(dev.gyeol.core.knowledge.EntityType.ARTIST, a.mbid, listOf(
            dev.gyeol.core.knowledge.Chunk("LASTFM", "en", "https://last.fm/x", "CC-BY-SA", "Best Female Vocalist in 2005.[1] Lee hosted[2][3] a show."),
        ))
        val text = jdbc.sql("SELECT text FROM knowledge_chunk WHERE entity_mbid = :m").param("m", a.mbid).query(String::class.java).single()
        assertEquals("Best Female Vocalist in 2005. Lee hosted a show.", text)
    }

    @Test
    fun `I-41 인기순 - 녹음 목록 없으면 이름 규칙만 하고 수집 요청, 다 모이면 목록에 없고 청취자 2% 미만인 곡은 뺀다`() {
        val host = newRoom()
        val stayc = ArtistInfo(UUID.randomUUID(), "STAYC", "STAYC", emptyList())
        val tid = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "Teddy Bear", stayc, UUID.randomUUID(), UUID.randomUUID(), "음반", "2023-02-14", "Single"))
        val aid = songs.trackArtist(tid)!!
        fun titles() = call("GET", "/api/rooms/${host.rid}/artists/$aid/songs?sort=popular", key = host.key).body!!["items"].toList().map { it["item"]["title"].asString() }

        // 녹음 목록 없음: 반주(Inst.)·다른 가수 곡만 빠지고, 수집이 요청된다
        assertEquals(listOf("Teddy Bear", "ASAP", "Big Song", "Celebrity"), titles())
        assertEquals("QUEUED", releases.scan(aid)!!.status)

        // 모으는 중(일부만 저장)에는 대조하지 않는다
        releases.startScan(aid)
        releases.upsert(aid, UUID.randomUUID(), "Teddy Bear", "teddybear", "2023-02-14", false)
        assertEquals(listOf("Teddy Bear", "ASAP", "Big Song", "Celebrity"), titles())

        // 다 모임: 목록에 없는 곡은 청취자가 1위(1000)의 1%(10) 이상일 때만 → Celebrity(5) 빠짐, Big Song(500) 남음
        releases.upsert(aid, UUID.randomUUID(), "ASAP", "asap", "2023-05-01", false)
        releases.finishScan(aid, true, 2, 2)
        assertEquals(listOf("Teddy Bear", "ASAP", "Big Song"), titles())

        // 실패: 이름 규칙만 다시, 수집도 다시 요청
        releases.finishScan(aid, false, null, 2)
        jdbc.sql("UPDATE artist_release_scan SET finished_at = now() + interval '1 second' WHERE artist_id = :a").param("a", aid).update()
        assertTrue(titles().contains("Celebrity"))
        assertEquals("QUEUED", releases.scan(aid)!!.status)
    }

    @Test
    fun `I-40 흐린 행성 - 행성 1개면 가수 판 목록 앞 3명, 2개면 행성마다 3명씩 전체 6명, 방에 있는 가수·중복 없음`() {
        val host = newRoom()
        fun artistWithTrack(name: String): Pair<Long, Long> { val t = track("$name 노래", name); return t to songs.trackArtist(t)!! }
        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 12, true) RETURNING id").query(Long::class.java).single()
        // 원문 벡터: 행성 A는 1번 축, 이웃 a1~a5는 1번 축에서 조금씩 멀어짐 / 행성 B는 2번 축, 이웃 b1~b5
        fun vec(axis: Int, k: Int) = dev.gyeol.api.room.vecOf(axis, k)
        val (ta, a) = artistWithTrack("행성A"); val (tb, b) = artistWithTrack("행성B")
        val near = mutableMapOf<String, Long>()
        listOf("a" to 1, "b" to 2).forEach { (p, axis) -> (1..5).forEach { k -> near["$p$k"] = artistWithTrack("$p$k").second.also { id ->
            maps.saveVector(id, "fake", vec(axis, k), null, vec(axis, k), "$p$k"); maps.savePosition(version, id, 0.1 * k, 0.1 * axis, null, "UMAP") } } }
        maps.saveVector(a, "fake", vec(1, 0), null, vec(1, 0), "A"); maps.savePosition(version, a, 0.0, 0.1, null, "UMAP")
        maps.saveVector(b, "fake", vec(2, 0), null, vec(2, 0), "B"); maps.savePosition(version, b, 0.0, 0.2, null, "UMAP")
        // a2는 이미 방에 있음 → 흐린 행성에서 빠진다
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to ta), host.key)
        val tA2 = jdbc.sql("SELECT id FROM track WHERE artist_id = :a").param("a", near["a2"]).query(Long::class.java).single()
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to tA2), host.key)
        fun ghosts() = call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!["ghosts"].toList().map { it["artistId"].asLong() }

        // 행성은 A와 a2 두 개라 행성마다 3명
        val g2 = ghosts()
        assertTrue(g2.size <= 6 && g2.toSet().size == g2.size, "중복 없음, 6명 이하: $g2")
        assertTrue(near["a2"]!! !in g2 && a !in g2, "방에 있는 가수는 없음")

        // a2를 빼서 행성 1개(A)로: 가수 판 목록 앞 3명과 같다
        val a2song = call("GET", "/api/rooms/${host.rid}/me/songs", key = host.key).body!!["items"].toList().single { it["trackId"].asLong() == tA2 }["id"].asLong()
        call("DELETE", "/api/rooms/${host.rid}/songs/$a2song", key = host.key)
        val panel = call("GET", "/api/rooms/${host.rid}/artists/$a", key = host.key).body!!["nearby"].toList()
            .filter { !it["inRoom"].asBoolean() }.map { it["artistId"].asLong() }
        assertEquals(listOf(near["a1"], near["a2"], near["a3"]), panel.take(3), "가수 판 가까운 가수 순서")
        assertEquals(panel.take(3), ghosts(), "행성 1개 → 가수 판 목록 앞 3명")

        // 행성 2개(A·B): 행성마다 3명씩 6명, 각자 가까운 이웃
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to tb), host.key)
        val g6 = ghosts()
        assertEquals(6, g6.size)
        assertEquals(setOf(near["a1"], near["a2"], near["a3"], near["b1"], near["b2"], near["b3"]), g6.toSet())
        // 활성 판을 남기면 "판 없음(placeholder)"을 기대하는 다른 테스트가 깨진다
        jdbc.sql("UPDATE map_version SET active = false WHERE id = :v").param("v", version).update()
    }

    @Test
    fun `I-44 얼굴 - 사진(Commons만)·방 곡 표지·없음, 사진은 방 열쇠로만, 사진이 늘면 ETag가 바뀜, 가수 판·가수 화면 저작자 표시`() {
        val host = newRoom()
        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 4, true) RETURNING id").query(Long::class.java).single()
        val galaxy = jdbc.sql("INSERT INTO galaxy (map_version, label, cx, cy) VALUES (:v, 0, 0.5, 0.5) RETURNING id").param("v", version).query(Long::class.java).single()
        fun artistWithTrack(name: String): Pair<Long, Long> { val t = track("$name 노래", name); return t to songs.trackArtist(t)!! }
        fun vec(axis: Int, k: Int) = dev.gyeol.api.room.vecOf(axis, k)
        val (tp, photoArtist) = artistWithTrack("얼굴사진"); val (tc, coverArtist) = artistWithTrack("얼굴표지"); val (tn, noneArtist) = artistWithTrack("얼굴없음")
        val (_, youtubeArtist) = artistWithTrack("얼굴유튜브")
        val (_, ghostPhoto) = artistWithTrack("흐린사진")
        listOf(photoArtist to 0, coverArtist to 7, noneArtist to 8, youtubeArtist to 9).forEach { (id, k) ->
            maps.saveVector(id, "fake", vec(3, k), null, vec(3, k), "x"); maps.savePosition(version, id, 0.1, 0.1, galaxy, "UMAP")
        }
        maps.saveVector(ghostPhoto, "fake", vec(3, 1), null, vec(3, 1), "x"); maps.savePosition(version, ghostPhoto, 0.2, 0.2, galaxy, "UMAP")
        jdbc.sql("UPDATE track SET cover_url = 'https://coverartarchive.org/release/x/front-250' WHERE id = :t").param("t", tc).update()
        listOf(tp, tc, tn).forEach { call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to it), host.key) }
        val youtubeTrack = jdbc.sql("SELECT id FROM track WHERE artist_id = :a").param("a", youtubeArtist).query(Long::class.java).single()
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to youtubeTrack), host.key)

        val etag0 = call("GET", "/api/rooms/${host.rid}/map", key = host.key).headers["etag"]!!.single()
        // worker 흉내: Commons 사진 둘(행성·흐린 행성), YouTube 프로필 하나(지도에서는 안 씀)
        listOf(photoArtist, ghostPhoto).forEach { id ->
            val key = "artists/$id"
            s3().use { it.putObject({ r -> r.bucket(COVERS).key(key).contentType("image/jpeg") }, software.amazon.awssdk.core.sync.RequestBody.fromBytes(byteArrayOf(9, 8, 7))) }
            jdbc.sql("INSERT INTO artist_image (artist_id, source, s3_key, page_url, author, license) VALUES (:a, 'COMMONS', :k, 'https://commons.wikimedia.org/wiki/File:x.jpg', '작가', 'CC BY-SA 4.0')")
                .param("a", id).param("k", key).update()
        }
        jdbc.sql("INSERT INTO artist_image (artist_id, source, url) VALUES (:a, 'YOUTUBE', 'https://yt3.ggpht.com/x')").param("a", youtubeArtist).update()

        val res = call("GET", "/api/rooms/${host.rid}/map", key = host.key, headers = mapOf("If-None-Match" to etag0))
        assertEquals(200, res.status, "사진이 늘면 ETag가 바뀐다")
        val faces = res.body!!["planets"].toList().associate { it["artistId"].asLong() to it["face"] }
        faces[photoArtist]!!.let {
            assertEquals("PHOTO", it["kind"].asString())
            assertEquals("/api/rooms/${host.rid}/artists/$photoArtist/image", it["url"].asString())
            assertEquals("작가", it["author"].asString()); assertEquals("CC BY-SA 4.0", it["license"].asString())
            assertEquals("https://commons.wikimedia.org/wiki/File:x.jpg", it["pageUrl"].asString())
        }
        assertEquals("COVER", faces[coverArtist]!!["kind"].asString())
        assertEquals("https://coverartarchive.org/release/x/front-250", faces[coverArtist]!!["url"].asString())
        assertEquals("NONE", faces[noneArtist]!!["kind"].asString())
        assertTrue(faces[noneArtist]!!["url"].isNull)
        assertEquals("NONE", faces[youtubeArtist]!!["kind"].asString(), "YouTube 프로필은 지도 얼굴로 안 씀")
        assertEquals(galaxy, faces[noneArtist]!!["galaxyId"].asLong())
        val ghost = res.body!!["ghosts"].toList().single { it["artistId"].asLong() == ghostPhoto }
        assertEquals("PHOTO", ghost["face"]["kind"].asString())

        // 사진: 방 열쇠가 있어야, Commons 사진이 없는 가수는 404
        val imgPath = "/api/rooms/${host.rid}/artists/$photoArtist/image"
        val img = http.send(HttpRequest.newBuilder(URI("http://localhost:$port$imgPath")).header("X-Room-Key", host.key).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, img.statusCode())
        assertEquals(listOf<Byte>(9, 8, 7), img.body().toList())
        assertEquals(401, call("GET", imgPath).status)
        assertEquals(404, call("GET", "/api/rooms/${host.rid}/artists/$youtubeArtist/image", key = host.key).status)

        // 가수 판·가수 화면
        val panel = call("GET", "/api/rooms/${host.rid}/artists/$photoArtist", key = host.key).body!!
        assertEquals("PHOTO", panel["face"]["kind"].asString())
        assertEquals("작가", panel["photo"]["author"].asString())
        assertEquals("COVER", call("GET", "/api/rooms/${host.rid}/artists/$coverArtist", key = host.key).body!!["face"]["kind"].asString())
        assertTrue(call("GET", "/api/rooms/${host.rid}/artists/$coverArtist", key = host.key).body!!["photo"].isNull)
        val screen = call("GET", "/api/rooms/${host.rid}/artists/$photoArtist/songs?sort=latest", key = host.key).body!!
        assertEquals("CC BY-SA 4.0", screen["artist"]["photo"]["license"].asString())
        jdbc.sql("UPDATE map_version SET active = false WHERE id = :v").param("v", version).update()
    }

    @Test
    fun `I-14 방 표지 - 저장본이 있으면 coverPath(우리 주소), 없으면 null이고 coverUrl 그대로, 표지는 방 열쇠로만`() {
        val host = newRoom()
        val stored = track("표지저장 노래", "표지저장가수")
        val external = track("표지외부 노래", "표지외부가수")
        val key = "covers/test-$stored"
        s3().use { it.putObject({ r -> r.bucket(COVERS).key(key).contentType("image/jpeg") }, software.amazon.awssdk.core.sync.RequestBody.fromBytes(byteArrayOf(4, 5, 6))) }
        jdbc.sql("UPDATE track SET cover_key = :k, cover_url = 'https://coverartarchive.org/release/s/front-250', cover_checked_at = now() WHERE id = :t").param("k", key).param("t", stored).update()
        jdbc.sql("UPDATE track SET cover_url = 'https://coverartarchive.org/release/e/front-250', cover_checked_at = now() WHERE id = :t").param("t", external).update()
        val path = "/api/rooms/${host.rid}/covers/$stored"

        // 담기 응답
        val added = call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to stored), host.key).body!!["song"]
        assertEquals(path, added["coverPath"].asString())
        assertEquals("https://coverartarchive.org/release/s/front-250", added["coverUrl"].asString())
        val addedExt = call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to external), host.key).body!!["song"]
        assertTrue(addedExt["coverPath"].isNull)
        assertEquals("https://coverartarchive.org/release/e/front-250", addedExt["coverUrl"].asString())

        // 내 노래·지도 위성·가수 판
        val mine = call("GET", "/api/rooms/${host.rid}/me/songs", key = host.key).body!!["items"].toList().associate { it["trackId"].asLong() to it["coverPath"] }
        assertEquals(path, mine[stored]!!.asString()); assertTrue(mine[external]!!.isNull)
        val sats = call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!["planets"].toList().flatMap { it["songs"].toList() }.associate { it["trackId"].asLong() to it["coverPath"] }
        assertEquals(path, sats[stored]!!.asString()); assertTrue(sats[external]!!.isNull)
        val storedArtist = songs.trackArtist(stored)!!
        val panel = call("GET", "/api/rooms/${host.rid}/artists/$storedArtist", key = host.key).body!!
        assertEquals(path, panel["songs"].toList().single()["coverPath"].asString())
        assertEquals("COVER", panel["face"]["kind"].asString())
        assertEquals(path, panel["face"]["url"].asString(), "행성 얼굴 COVER도 저장본이 있으면 우리 주소")

        // 표지: 방 열쇠가 있어야, 저장본이 없으면 404
        val img = http.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("X-Room-Key", host.key).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, img.statusCode())
        assertEquals(listOf<Byte>(4, 5, 6), img.body().toList())
        assertTrue(img.headers().firstValue("Cache-Control").orElse("").contains("private"))
        assertEquals(401, call("GET", path).status)
        assertEquals(404, call("GET", "/api/rooms/${host.rid}/covers/$external", key = host.key).status)

        // 가수 화면 곡 목록 (인기순, 우리 DB 곡에만 trackId가 있다)
        val stayc = ArtistInfo(UUID.randomUUID(), "STAYC", "STAYC", emptyList())
        val teddy = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "Teddy Bear", stayc, UUID.randomUUID(), UUID.randomUUID(), "음반", "2023-02-14", "Single"))
        jdbc.sql("UPDATE track SET cover_key = :k WHERE id = :t").param("k", key).param("t", teddy).update()
        val items = call("GET", "/api/rooms/${host.rid}/artists/${songs.trackArtist(teddy)}/songs?sort=popular", key = host.key).body!!["items"].toList()
        val teddyItem = items.single { it["item"]["title"].asString() == "Teddy Bear" }
        assertEquals("/api/rooms/${host.rid}/covers/$teddy", teddyItem["coverPath"].asString())
        assertTrue(items.filter { it["item"]["trackId"].isNull }.all { it["coverPath"].isNull })
    }

    @Test
    fun `지도 ETag - 형식 버전이 앞에 붙고, 방 곡 표지 저장본이 늘면 바뀐다 (map_rev는 그대로)`() {
        val host = newRoom()
        val t = track("이태그 노래", "이태그가수")
        call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t), host.key)
        val etag0 = call("GET", "/api/rooms/${host.rid}/map", key = host.key).headers["etag"]!!.single()
        assertTrue(etag0.startsWith("\"${dev.gyeol.api.room.MAP_FORMAT}-"), etag0)
        assertEquals(304, call("GET", "/api/rooms/${host.rid}/map", key = host.key, headers = mapOf("If-None-Match" to etag0)).status)
        jdbc.sql("UPDATE track SET cover_key = 'covers/etag-test' WHERE id = :t").param("t", t).update()
        val res = call("GET", "/api/rooms/${host.rid}/map", key = host.key, headers = mapOf("If-None-Match" to etag0))
        assertEquals(200, res.status, "표지 저장본이 생기면 coverPath가 바뀌므로 새 본문")
        assertTrue(res.headers["etag"]!!.single() != etag0)
    }

    @Test
    fun `I-51 식별 결과에 상자 - 생긴 순간의 상태로 201과 같은 모양, 다시 받아도 같은 boxId, 상자가 지워지면 null`() {
        val host = newRoom()
        val songsPath = "/api/rooms/${host.rid}/songs"
        val requestId = call("POST", songsPath, mapOf("title" to "상자 노래", "artist" to "가수 A"), host.key).body!!["requestId"].asLong()
        val path = "/api/rooms/${host.rid}/song-requests/$requestId"
        assertTrue(call("GET", path, key = host.key).body!!["gift"].isNull, "식별 중에는 gift 없음")

        // worker 흉내: 곡을 담고 100곡째 상자가 WAITING으로 생김 (오늘 상자는 이미 받음)
        val rid = jdbc.sql("SELECT id FROM room WHERE public_id = :p").param("p", host.rid).query(Long::class.java).single()
        val t = track("상자 노래")
        val songId = songs.add(rid, host.memberId, t, songs.trackArtist(t)!!)!!
        songs.finishRequest(requestId, "DONE", t, songId, null)
        val boxId = jdbc.sql("INSERT INTO gift_box (member_id, milestone, status) VALUES (:m, 100, 'WAITING') RETURNING id")
            .param("m", host.memberId).query(Long::class.java).single()
        songs.setRequestGift(requestId, boxId, "WAITING")

        val first = call("GET", path, key = host.key).body!!
        assertEquals("DONE", first["status"].asString())
        val gift = first["gift"]
        assertEquals(setOf("boxId", "milestone", "status"), gift.propertyNames().toSet(), "201 응답의 gift와 같은 필드")
        assertEquals(boxId, gift["boxId"].asLong())
        assertEquals(100, gift["milestone"].asInt())
        assertEquals("WAITING", gift["status"].asString())
        // 다시 받아도 같은 boxId (화면은 이것으로 알림을 한 번만), 다음 날 READY가 돼도 알림용 상태는 생긴 순간 값
        jdbc.sql("UPDATE gift_box SET status = 'READY', ready_on = current_date WHERE id = :b").param("b", boxId).update()
        val again = call("GET", path, key = host.key).body!!["gift"]
        assertEquals(boxId, again["boxId"].asLong())
        assertEquals("WAITING", again["status"].asString())
        // 상자가 지워지면 gift도 없음
        jdbc.sql("DELETE FROM gift_box WHERE id = :b").param("b", boxId).update()
        assertTrue(call("GET", path, key = host.key).body!!["gift"].isNull)
    }

    @Test
    fun `방 검색 더보기 - 쪽마다 10곡, 쪽 사이 중복 없음, 마지막 쪽 hasMore=false, 1쪽은 그대로, 가수 카드는 1쪽에만`() {
        val host = newRoom()
        // DB 곡 3개는 한 가수 (track()은 부를 때마다 새 가수를 만든다)
        val dbArtist = ArtistInfo(UUID.randomUUID(), "더보기DB가수", "더보기DB가수", emptyList())
        repeat(3) { identify.upsertTrack(TrackInfo(UUID.randomUUID(), "더보기시험 DB $it", dbArtist, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single")) }
        val base = "/api/rooms/${host.rid}/search?q=%EB%8D%94%EB%B3%B4%EA%B8%B0%EC%8B%9C%ED%97%98"
        val plain = call("GET", base, key = host.key).body!!
        val p1 = call("GET", "$base&page=1", key = host.key).body!!
        assertEquals(plain.toString(), p1.toString(), "page를 빼면 1쪽과 같다")
        assertEquals(10, p1["items"].size())
        assertTrue(p1["hasMore"].asBoolean())
        assertEquals(1, p1["page"].asInt())

        fun keyOf(n: JsonNode) = n["item"]["trackId"].takeUnless { it.isNull }?.let { "id:${it.asLong()}" }
            ?: "${n["item"]["title"].asString()}|${n["item"]["artist"].asString()}"
        val pages = mutableListOf(p1)
        while (pages.last()["hasMore"].asBoolean()) {
            val next = call("GET", "$base&page=${pages.size + 1}", key = host.key).body!!
            assertEquals(pages.size + 1, next["page"].asInt())
            assertTrue(next["artist"].isNull, "가수 카드는 1쪽에만")
            pages.add(next)
        }
        assertEquals(4, pages.size, "DB 3곡 + Last.fm 35곡(1곡은 DB와 같음) = 37곡 → 4쪽")
        pages.dropLast(1).forEach { assertEquals(10, it["items"].size()) }
        assertEquals(7, pages.last()["items"].size())
        assertTrue(!pages.last()["hasMore"].asBoolean())
        val keys = pages.flatMap { it["items"].toList() }.map(::keyOf)
        assertEquals(keys.size, keys.toSet().size, "쪽 사이 중복 없음: $keys")
        assertEquals(1, keys.count { it.startsWith("id:") && pages.flatMap { p -> p["items"].toList() }.any { n -> keyOf(n) == it && n["item"]["title"].asString() == "더보기시험 DB 0" } }, "Last.fm의 같은 곡은 DB 곡 하나로")

        assertEquals(400, call("GET", "$base&page=0", key = host.key).status)
        assertEquals(400, call("GET", "$base&page=6", key = host.key).status)
        val p5 = call("GET", "$base&page=5", key = host.key).body!!
        assertEquals(0, p5["items"].size())
        assertTrue(!p5["hasMore"].asBoolean(), "5쪽은 늘 hasMore=false")
        // 10곡이 안 되는 검색은 1쪽 hasMore=false
        assertTrue(!call("GET", "/api/rooms/${host.rid}/search?q=%EC%83%88%20%EB%85%B8%EB%9E%98", key = host.key).body!!["hasMore"].asBoolean())
    }

    @Test
    fun `가수 카드 보강 - 지도에 있는 가수는 지도 이름(displayName)과 사진 얼굴, name은 검색 표기 그대로`() {
        val host = newRoom()
        val younha = ArtistInfo(UUID.randomUUID(), "윤하카드", "Younha", listOf("YOUNHACARD"))
        val t = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "사건의 지평선", younha, UUID.randomUUID(), UUID.randomUUID(), "음반", "2022-03-30", "Single"))
        val aid = songs.trackArtist(t)!!
        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 1, true) RETURNING id").query(Long::class.java).single()
        val galaxy = jdbc.sql("INSERT INTO galaxy (map_version, label, name, cx, cy) VALUES (:v, 0, '발라드', 0.5, 0.5) RETURNING id").param("v", version).query(Long::class.java).single()
        maps.savePosition(version, aid, 0.5, 0.5, galaxy, "UMAP")
        jdbc.sql("INSERT INTO artist_image (artist_id, source, s3_key, page_url, author, license) VALUES (:a, 'COMMONS', 'artists/card', 'https://commons.wikimedia.org/wiki/File:y.jpg', '사진가', 'CC BY 4.0')")
            .param("a", aid).update()

        val card = call("GET", "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("YOUNHACARD", "UTF-8"), key = host.key).body!!["artist"]
        assertEquals(aid, card["artistId"].asLong())
        assertTrue(card["onMap"].asBoolean())
        assertEquals("윤하카드", card["displayName"].asString(), "지도 행성과 같은 DB 이름")
        assertTrue(card.has("name") && card.has("aliases"), "기존 필드는 그대로")
        val face = card["face"]
        assertEquals("PHOTO", face["kind"].asString())
        assertEquals("/api/rooms/${host.rid}/artists/$aid/image", face["url"].asString())
        assertEquals(galaxy, face["galaxyId"].asLong())
        assertEquals("사진가", face["author"].asString())
        assertEquals("CC BY 4.0", face["license"].asString())
        jdbc.sql("UPDATE map_version SET active = false WHERE id = :v").param("v", version).update()
    }

    @Test
    fun `가수 카드 대체 얼굴 - 방에 곡이 없으면 DB 저장 표지(재생 수 많은 곡, 같으면 id 작은 곡), 저장본도 없으면 NONE`() {
        val host = newRoom()
        val a = ArtistInfo(UUID.randomUUID(), "저장표지가수", "Stored Cover", emptyList())
        fun t(title: String) = identify.upsertTrack(TrackInfo(UUID.randomUUID(), title, a, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single"))
        val low = t("저장 노래 1"); val tieA = t("저장 노래 2"); val tieB = t("저장 노래 3"); val noKey = t("저장 노래 4")
        val aid = songs.trackArtist(low)!!
        val q = "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("저장표지가수", "UTF-8")
        assertEquals("NONE", call("GET", q, key = host.key).body!!["artist"]["face"]["kind"].asString(), "저장본이 없으면 NONE")

        jdbc.sql("UPDATE track SET cover_key = 'covers/s-' || id WHERE id IN (:ids)").param("ids", listOf(low, tieA, tieB)).update()
        jdbc.sql("UPDATE track SET plays_total = CASE WHEN id = :low THEN 1 ELSE 5 END WHERE id IN (:ids)").param("low", low).param("ids", listOf(low, tieA, tieB, noKey)).update()
        jdbc.sql("UPDATE track SET plays_total = 99 WHERE id = :n").param("n", noKey).update() // 재생 수가 가장 많아도 저장본이 없으면 고르지 않는다
        val face = call("GET", q, key = host.key).body!!["artist"]["face"]
        assertEquals("COVER", face["kind"].asString())
        assertEquals("/api/rooms/${host.rid}/covers/${minOf(tieA, tieB)}", face["url"].asString(), "재생 수 5인 두 곡 중 id가 작은 곡")
        assertEquals(0, call("GET", q, key = host.key).body!!["artist"]["songCountInRoom"].asInt())
        assertEquals(aid, call("GET", q, key = host.key).body!!["artist"]["artistId"].asLong())
    }

    @Test
    fun `가수 카드 보강 - DB에만 있는 가수는 사진이 없으면 방 곡 표지 얼굴, 방에 곡도 없으면 NONE`() {
        val host = newRoom()
        val a = ArtistInfo(UUID.randomUUID(), "표지카드가수", "Cover Card", emptyList())
        val t = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "표지 노래", a, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single"))
        val aid = songs.trackArtist(t)!!
        val q = "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("표지카드가수", "UTF-8")

        val none = call("GET", q, key = host.key).body!!["artist"]
        assertEquals(aid, none["artistId"].asLong())
        assertTrue(!none["onMap"].asBoolean())
        assertEquals("표지카드가수", none["displayName"].asString())
        assertEquals("NONE", none["face"]["kind"].asString())
        assertTrue(none["face"]["url"].isNull, "사진 없는 가수에 /image 주소를 주지 않는다")

        jdbc.sql("UPDATE track SET cover_url = 'https://coverartarchive.org/release/x/front-250' WHERE id = :t").param("t", t).update()
        assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t), host.key).status)
        val cover = call("GET", q, key = host.key).body!!["artist"]
        assertEquals(1, cover["songCountInRoom"].asInt())
        assertEquals("COVER", cover["face"]["kind"].asString())
        assertEquals("https://coverartarchive.org/release/x/front-250", cover["face"]["url"].asString())
    }

    @Test
    fun `가수 판 대체 얼굴 - 방에 곡이 없으면 DB 저장 표지로 COVER, 지도 응답에는 영향 없음`() {
        val host = newRoom()
        val a = ArtistInfo(UUID.randomUUID(), "판표지가수", "Board Cover", emptyList())
        val t = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "판 노래", a, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single"))
        val aid = songs.trackArtist(t)!!
        val board = "/api/rooms/${host.rid}/artists/$aid"
        assertEquals("NONE", call("GET", board, key = host.key).body!!["face"]["kind"].asString(), "저장본이 없으면 NONE")
        jdbc.sql("UPDATE track SET cover_key = 'covers/board' WHERE id = :t").param("t", t).update()
        val face = call("GET", board, key = host.key).body!!["face"]
        assertEquals("COVER", face["kind"].asString())
        assertEquals("/api/rooms/${host.rid}/covers/$t", face["url"].asString())
        // 방에 곡이 없으니 지도에는 이 가수 행성이 없다 (지도 얼굴 계산은 그대로)
        assertTrue(call("GET", "/api/rooms/${host.rid}/map", key = host.key).body!!["planets"].toList().none { it["artistId"].asLong() == aid })
    }

    private fun prepareMessages(key: String): Int = sqs().use { c ->
        val url = c.getQueueUrl { it.queueName(SONG_QUEUE) }.queueUrl()
        // 같은 큐에 다른 테스트 메시지가 수십 개 쌓여 있을 수 있어 빌 때까지 받아 지운다 (이 테스트들은 차례로 돈다)
        var n = 0
        var empty = 0
        while (empty < 2) {
            val got = c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1) }.messages()
            if (got.isEmpty()) empty++ else empty = 0
            got.forEach { m ->
                val j = json.readTree(m.body())
                if (j.path("type").asString("") == "PREPARE_SEARCH_ARTIST" && j.path("prepKey").asString("") == key) n++
                c.deleteMessage { it.queueUrl(url).receiptHandle(m.receiptHandle()) }
            }
        }
        n
    }

    private fun prepRow(key: String) = jdbc.sql("SELECT status FROM search_artist_prep WHERE prep_key = :k").param("k", key).query(String::class.java).optional().orElse(null)

    @Test
    fun `미리 준비 - 우리 DB에 없는 가수 카드면 한 번 맡기고, 연타해도 메시지는 하나`() {
        val host = newRoom()
        val q = "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("준비가수", "UTF-8")
        val card = call("GET", q, key = host.key).body!!["artist"]
        assertTrue(card["artistId"].isNull)
        repeat(3) { call("GET", q, key = host.key) }
        assertEquals("QUEUED", prepRow("n:준비가수"))
        assertEquals(1, prepareMessages("n:준비가수"))
        assertEquals(1, jdbc.sql("SELECT count FROM search_artist_prep_daily WHERE room_id = (SELECT id FROM room WHERE public_id = :p)").param("p", host.rid).query(Int::class.java).single())
        assertEquals(0, call("GET", "$q&page=2", key = host.key).let { prepareMessages("n:준비가수") }, "2쪽 이후는 맡기지 않는다")
    }

    @Test
    fun `미리 준비 - 얼굴이 PHOTO나 COVER인 우리 가수면 맡기지 않고, NONE이면 id 키로 맡긴다`() {
        val host = newRoom()
        val a = ArtistInfo(UUID.randomUUID(), "준비얼굴가수", "Prep Face", emptyList())
        val t = identify.upsertTrack(TrackInfo(UUID.randomUUID(), "준비얼굴 노래", a, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single"))
        val aid = songs.trackArtist(t)!!
        val q = "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("준비얼굴가수", "UTF-8")
        jdbc.sql("UPDATE track SET cover_key = 'covers/prep' WHERE id = :t").param("t", t).update()
        assertEquals("COVER", call("GET", q, key = host.key).body!!["artist"]["face"]["kind"].asString())
        assertNull(prepRow("id:$aid"), "얼굴이 있으면 맡기지 않는다")
        jdbc.sql("UPDATE track SET cover_key = NULL WHERE id = :t").param("t", t).update()
        assertEquals("NONE", call("GET", q, key = host.key).body!!["artist"]["face"]["kind"].asString())
        assertEquals("QUEUED", prepRow("id:$aid"))
        assertEquals(1, prepareMessages("id:$aid"))
    }

    @Test
    fun `미리 준비 - 하루 지난 끝난 키와 1시간 넘게 멈춘 QUEUED는 다시 맡기고, 방당 하루 30명을 넘으면 맡기지 않는다`() {
        val host = newRoom()
        val roomId = jdbc.sql("SELECT id FROM room WHERE public_id = :p").param("p", host.rid).query(Long::class.java).single()
        val q = "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode("준비가수", "UTF-8")
        jdbc.sql("DELETE FROM search_artist_prep WHERE prep_key = 'n:준비가수'").update()
        call("GET", q, key = host.key)
        prepareMessages("n:준비가수")
        // 끝났지만 하루가 안 지남 → 다시 맡기지 않음
        jdbc.sql("UPDATE search_artist_prep SET status = 'DONE' WHERE prep_key = 'n:준비가수'").update()
        call("GET", q, key = host.key)
        assertEquals("DONE", prepRow("n:준비가수"))
        // 하루가 지남 → 다시
        jdbc.sql("UPDATE search_artist_prep SET requested_at = now() - interval '25 hours' WHERE prep_key = 'n:준비가수'").update()
        call("GET", q, key = host.key)
        assertEquals("QUEUED", prepRow("n:준비가수"))
        assertEquals(1, prepareMessages("n:준비가수"))
        // 진행 중(1시간 안) → 그대로, 1시간 넘게 멈춤 → 다시
        call("GET", q, key = host.key)
        assertEquals(0, prepareMessages("n:준비가수"))
        jdbc.sql("UPDATE search_artist_prep SET requested_at = now() - interval '61 minutes' WHERE prep_key = 'n:준비가수'").update()
        call("GET", q, key = host.key)
        assertEquals(1, prepareMessages("n:준비가수"))
        // 방당 하루 상한
        jdbc.sql("UPDATE search_artist_prep SET requested_at = now() - interval '2 days', status = 'DONE' WHERE prep_key = 'n:준비가수'").update()
        jdbc.sql("UPDATE search_artist_prep_daily SET count = ${dev.gyeol.api.room.PREPARE_PER_ROOM} WHERE room_id = :r").param("r", roomId).update()
        call("GET", q, key = host.key)
        assertEquals("DONE", prepRow("n:준비가수"), "상한이면 키도 바꾸지 않는다")
        assertEquals(0, prepareMessages("n:준비가수"))
    }

    @Test
    fun `내 노래 정렬 - recent는 그대로, genre·artist 묶음 순서·groupCount·분류 중, 쪽 경계에서도 중복·누락 없음, snapshot 뒤 담은 곡은 안 섞임`() {
        val host = newRoom()
        fun artist(name: String) = ArtistInfo(UUID.randomUUID(), name, name, emptyList())
        val a = artist("정렬가수가"); val b = artist("정렬가수나"); val c = artist("정렬가수다"); val d = artist("정렬가수라")
        fun add(info: ArtistInfo, title: String): Long {
            val t = identify.upsertTrack(TrackInfo(UUID.randomUUID(), title, info, UUID.randomUUID(), UUID.randomUUID(), "음반", "2020-01-01", "Single"))
            assertEquals(201, call("POST", "/api/rooms/${host.rid}/songs", mapOf("trackId" to t), host.key).status)
            return t
        }
        // 담은 순서: A1 B1 D1 A2 C1 B2 A3 D2
        val tA1 = add(a, "가1"); add(b, "나1"); add(d, "라1"); add(a, "가2"); add(c, "다1"); add(b, "나2"); add(a, "가3"); add(d, "라2")
        val aid = songs.trackArtist(tA1)!!
        fun idOf(info: ArtistInfo) = jdbc.sql("SELECT id FROM artist WHERE mbid = :m").param("m", info.mbid).query(Long::class.java).single()
        jdbc.sql("UPDATE map_version SET active = false").update()
        val version = jdbc.sql("INSERT INTO map_version (params, artist_count, active) VALUES ('{}', 3, true) RETURNING id").query(Long::class.java).single()
        val ballad = jdbc.sql("INSERT INTO galaxy (map_version, label, name, cx, cy) VALUES (:v, 0, '발라드', 0.2, 0.2) RETURNING id").param("v", version).query(Long::class.java).single()
        val dance = jdbc.sql("INSERT INTO galaxy (map_version, label, name, cx, cy) VALUES (:v, 1, '댄스', 0.8, 0.8) RETURNING id").param("v", version).query(Long::class.java).single()
        maps.savePosition(version, aid, 0.2, 0.2, ballad, "UMAP")
        maps.savePosition(version, idOf(c), 0.21, 0.2, ballad, "UMAP")
        maps.savePosition(version, idOf(b), 0.8, 0.8, dance, "UMAP") // D는 자리 없음 → 분류 중
        val base = "/api/rooms/${host.rid}/me/songs"

        // recent: sort 없이와 같고 최근 담은 순
        val recent = call("GET", "$base?limit=100", key = host.key).body!!
        assertEquals(recent.toString(), call("GET", "$base?limit=100&sort=recent", key = host.key).body!!.toString())
        assertEquals(listOf("라2", "가3", "나2", "다1", "가2", "라1", "나1", "가1"), recent["items"].toList().map { it["title"].asString() })
        assertTrue(recent["snapshot"].isNull)

        fun all(sort: String, limit: Int): List<JsonNode> {
            val out = mutableListOf<JsonNode>()
            var cursor: Long? = null
            var snap: Long? = null
            do {
                val q = "$base?sort=$sort&limit=$limit" + (cursor?.let { "&cursor=$it" } ?: "") + (snap?.let { "&snapshot=$it" } ?: "")
                val pg = call("GET", q, key = host.key).body!!
                assertEquals(sort, pg["sort"].asString())
                snap = pg["snapshot"].asLong()
                out += pg["items"].toList()
                cursor = pg["nextCursor"].takeUnless { it.isNull }?.asLong()
            } while (cursor != null)
            return out
        }
        // genre: 발라드(A3+C1=4) → 댄스(2)·분류 중(2)은 분류 중이 맨 뒤, 묶음 안은 최근 담은 순. 3곡씩 끊어도 같다
        val genre = all("genre", 3)
        assertEquals(listOf("가3", "다1", "가2", "가1", "나2", "나1", "라2", "라1"), genre.map { it["title"].asString() })
        assertEquals(listOf(4, 4, 4, 4, 2, 2, 2, 2), genre.map { it["group"]["count"].asInt() }, "groupCount는 쪽과 상관없이 전체 수")
        assertEquals(listOf("발라드", "댄스", "분류 중"), genre.map { it["group"]["name"].asString() }.distinct())
        assertEquals(ballad, genre.first()["group"]["galaxyId"].asLong())
        assertTrue(genre.last()["group"]["galaxyId"].isNull)
        assertEquals(genre.map { it["id"].asLong() }, all("genre", 100).map { it["id"].asLong() }, "쪽 크기와 상관없이 같은 순서")
        // artist: A(3) → B(2)·D(2)는 이름 순 → C(1)
        val byArtist = all("artist", 3)
        assertEquals(listOf("가3", "가2", "가1", "나2", "나1", "라2", "라1", "다1"), byArtist.map { it["title"].asString() })
        val g = byArtist.first()["group"]
        assertEquals("ARTIST", g["kind"].asString())
        assertEquals(aid, g["artistId"].asLong())
        assertEquals("정렬가수가", g["displayName"].asString())
        assertEquals(3, g["count"].asInt())
        assertTrue(g["face"]["kind"].asString() in setOf("PHOTO", "COVER", "NONE"))
        assertEquals(byArtist.size, byArtist.map { it["id"].asLong() }.toSet().size, "중복 없음")

        // snapshot: 첫 쪽 뒤에 곡을 더 담아도 다음 쪽에 섞이지 않는다
        val first = call("GET", "$base?sort=genre&limit=3", key = host.key).body!!
        add(a, "가4")
        val second = call("GET", "$base?sort=genre&limit=100&cursor=${first["nextCursor"].asLong()}&snapshot=${first["snapshot"].asLong()}", key = host.key).body!!
        assertEquals(listOf("가1", "나2", "나1", "라2", "라1"), second["items"].toList().map { it["title"].asString() })
        assertEquals(400, call("GET", "$base?sort=popular", key = host.key).status)
        jdbc.sql("UPDATE map_version SET active = false WHERE id = :v").param("v", version).update()
    }

    @Test
    fun `한 글자 검색 - 한글·한자·가나 한 글자는 허용하고 그 이름의 우리 가수를 카드와 앞자리로, 라틴 한 글자는 400, 곡 고르기 입구는 그대로`() {
        val host = newRoom()
        val rain = ArtistInfo(UUID.randomUUID(), "RAIN한글자", "RAIN", listOf("비", "Rain"))
        val other = ArtistInfo(UUID.randomUUID(), "다른가수한글자", "Other", emptyList())
        listOf("Rainism", "Hip Song").forEach { identify.upsertTrack(TrackInfo(UUID.randomUUID(), it, rain, UUID.randomUUID(), UUID.randomUUID(), "음반", "2008-01-01", "Single")) }
        identify.upsertTrack(TrackInfo(UUID.randomUUID(), "비 오는 날", other, UUID.randomUUID(), UUID.randomUUID(), "음반", "2010-01-01", "Single"))
        val rainId = jdbc.sql("SELECT id FROM artist WHERE mbid = :m").param("m", rain.mbid).query(Long::class.java).single()
        fun search(q: String) = call("GET", "/api/rooms/${host.rid}/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"), key = host.key)

        val bi = search("비")
        assertEquals(200, bi.status)
        assertEquals(rainId, bi.body!!["artist"]["artistId"].asLong(), "이름·별칭이 정확히 '비'인 우리 가수가 카드")
        val ids = bi.body["items"].toList().map { it["item"]["artistId"].takeUnless { n -> n.isNull }?.asLong() }
        assertEquals(listOf(rainId, rainId), ids.take(2), "그 가수 곡을 앞에: $ids")
        assertEquals(400, search("a").status)
        assertEquals(400, search("7").status)
        assertEquals(200, search("별").status, "DB에 없는 한 글자도 200")
        assertEquals(200, search(" 비 ").status, "앞뒤 공백은 뺀다")
        assertEquals(400, call("GET", "/api/tracks/search?q=" + java.net.URLEncoder.encode("비", "UTF-8")).status, "곡 고르기 입구는 2~100자 그대로")
    }
}
