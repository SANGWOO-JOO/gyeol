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
            createContext("/internal/lastfm/search") { ex ->
                val body = """{"items":[{"title":"새 노래","artist":"가수 A","mbid":null,"listeners":10,"releaseYear":null}],"source":"lastfm","cached":false}""".toByteArray()
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
        assertEquals(200, call("POST", "/api/room-drafts/$token/resend").status)
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
        val msg = sqs().use { c ->
            val url = c.getQueueUrl { it.queueName(SONG_QUEUE) }.queueUrl()
            c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5) }.messages()
        }.map { json.readTree(it.body()) }.single { it.path("requestId").asLong(-1) == requestId }
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
}
