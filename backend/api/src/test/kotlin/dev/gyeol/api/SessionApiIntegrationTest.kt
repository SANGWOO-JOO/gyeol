package dev.gyeol.api

import dev.gyeol.core.identify.AliasMethod
import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.Query
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.core.listen.ListenEvent
import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.listen.Source
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.Tokens
import dev.gyeol.core.upload.UploadRepository
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
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
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PostgreSQL + LocalStack을 컨테이너로 띄워 API 전체 흐름을 확인한다 (parse 워커는 ⑦에서, 여기서는 DB에 직접 넣어 흉내 냄) */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionApiIntegrationTest {

    companion object {
        private const val BUCKET = "gyeol-test-uploads"
        private const val IDENTIFY_QUEUE = "gyeol-test-identify"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))

        @Container
        @JvmStatic
        val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("s3", "sqs")

        // 가짜 external-fetcher: 켜져 있으면 고정 결과, 꺼져 있으면 503
        @Volatile var fakeFetcherUp = true
        val fetcherQueries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val youtubeAsked = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val fakeFetcher = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0).apply {
            // 가짜 YouTube 판정: "m"으로 시작하는 ID는 음악(10), 나머지는 22, "x"로 시작하면 응답에 없음(삭제된 영상)
            createContext("/internal/youtube/categories") { ex ->
                val ids = Regex("\"([\\w-]{6,32})\"").findAll(ex.requestBody.readAllBytes().decodeToString()).map { it.groupValues[1] }.filter { it != "videoIds" }.toList()
                youtubeAsked += ids
                val cats = ids.filter { !it.startsWith("x") }.joinToString(",") { "\"$it\":\"${if (it.startsWith("m")) "10" else "22"}\"" }
                val body = """{"categories":{$cats},"calls":1,"ms":5}""".toByteArray()
                if (fakeFetcherUp) {
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                } else {
                    ex.sendResponseHeaders(503, -1); ex.close()
                }
            }
            createContext("/internal/lastfm/search") { ex ->
                fetcherQueries += ex.requestURI.rawQuery
                val body = """{"items":[{"title":"Rain","artist":"태연","mbid":null,"listeners":123456,"releaseYear":null}],"source":"lastfm","cached":false}""".toByteArray()
                if (fakeFetcherUp) {
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                } else {
                    ex.sendResponseHeaders(503, -1); ex.close()
                }
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
            registry.add("spring.cloud.aws.s3.path-style-access-enabled") { "true" }
            registry.add("gyeol.upload.bucket") { BUCKET }
            registry.add("gyeol.identify-queue") { IDENTIFY_QUEUE }
        }

        fun sqs(): SqsClient = SqsClient.builder()
            .endpointOverride(localstack.endpoint).region(Region.of(localstack.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)))
            .build()

        fun s3(): S3Client = S3Client.builder()
            .endpointOverride(localstack.endpoint)
            .region(Region.of(localstack.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)))
            .forcePathStyle(true)
            .build()

        @BeforeAll
        @JvmStatic
        fun createBucket() {
            s3().use { it.createBucket { b -> b.bucket(BUCKET) } }
            sqs().use { it.createQueue { q -> q.queueName(IDENTIFY_QUEUE) } }
        }
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var uploads: UploadRepository
    @Autowired lateinit var listens: ListenEventRepository
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var identify: IdentifyRepository

    private val http = HttpClient.newHttpClient()

    private fun call(method: String, path: String, body: Any? = null): Pair<Int, JsonNode?> {
        val req = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body().takeIf { it.isNotBlank() }?.let { json.readTree(it) }
    }

    @Test
    fun `청취 기록 업로드 세션 - 업로드 주소 발급부터 통계와 삭제까지`() {
        val (created, session) = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT"))
        assertEquals(201, created)
        val token = session!!["token"].asString()
        assertEquals(43, token.length)
        assertEquals("CREATED", session["status"].asString())

        // 브라우저가 필터를 거쳐 올릴 파일
        val file = json.writeValueAsBytes(
            mapOf(
                "schemaVersion" to 1,
                "items" to listOf(mapOf("videoId" to "abc", "title" to "노래", "channel" to "가수 - Topic", "watchedAt" to "2026-10-24T09:00:00Z", "source" to "MUSIC_APP")),
            ),
        )
        val (issued, ticket) = call(
            "POST", "/api/sessions/$token/upload-url",
            mapOf("totalCount" to 10, "musicCount" to 3, "unclassifiedCount" to 5, "byteSize" to file.size),
        )
        assertEquals(200, issued)
        val uploadId = UUID.fromString(ticket!!["uploadId"].asString())

        // 서명 URL로 S3에 직접 올린다 (브라우저와 같은 방식)
        val put = HttpRequest.newBuilder(URI(ticket["url"].asString()))
            .header("Content-Type", ticket["headers"]["content-type"]?.asString() ?: ticket["headers"]["Content-Type"].asString())
            .PUT(HttpRequest.BodyPublishers.ofByteArray(file))
            .build()
        assertEquals(200, http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode())

        val (_, uploading) = call("GET", "/api/sessions/$token")
        assertEquals("UPLOADING", uploading!!["status"].asString())
        assertEquals(3, uploading["counts"]["music"].asInt())

        // 같은 세션에 두 번째 업로드 주소는 안 된다
        assertEquals(409, call("POST", "/api/sessions/$token/upload-url", mapOf("totalCount" to 1, "musicCount" to 0, "unclassifiedCount" to 0, "byteSize" to 1)).first)
        // 처리 전에는 통계를 줄 수 없다
        assertEquals("NOT_READY", call("GET", "/api/sessions/$token/stats").second!!["code"].asString())

        // parse 워커 흉내: 청취 기록 저장 후 DONE
        val sid = sessions.findActiveByTokenHash(Tokens.hash(token))!!.id
        val base = Instant.parse("2026-10-24T00:00:00Z")
        val events = (0 until 30).map { i ->
            ListenEvent("v${i % 4}", "노래 ${i % 4}", if (i % 2 == 0) "가수 A - Topic" else "가수 A", base.plus(i.toLong(), ChronoUnit.HOURS), if (i % 3 == 0) Source.MUSIC_APP else Source.YOUTUBE)
        }
        listens.insertBatch(sid, uploadId, events, Instant.now().plus(7, ChronoUnit.DAYS))
        uploads.markProcessed(uploadId, events.size)
        sessions.markDone(sid, events.size, events.first().watchedAt, events.last().watchedAt)

        // 식별 대상이 없으면 진행률은 빠지고, 생기면 상태별로 센다 (L2-1)
        assertTrue(call("GET", "/api/sessions/$token").second!!["identify"].isNull)
        jdbc.sql(
            """
            INSERT INTO identify_target (session_id, alias_key, title, channel, source, plays, status) VALUES
              (:sid, 'a', '노래 0', '가수 A - Topic', 'YOUTUBE', 8, 'DONE'), (:sid, 'b', '노래 1', '가수 A', 'YOUTUBE', 7, 'PENDING')
            """.trimIndent(),
        ).param("sid", sid).update()
        val progress = call("GET", "/api/sessions/$token").second!!["identify"]
        assertEquals(2, progress["target"].asInt())
        assertEquals(1, progress["done"].asInt())
        assertEquals(1, progress["notFound"].asInt()) // 캐시 기록이 없는 완료는 못 찾음으로 센다

        val (ok, stats) = call("GET", "/api/sessions/$token/stats")
        assertEquals(200, ok)
        assertEquals(30, stats!!["totals"]["plays"].asInt())
        assertEquals(24, stats["byHour"].size())
        assertEquals(7, stats["byWeekday"].size())
        // " - Topic"을 떼고 같은 가수로 합친다
        assertEquals("가수 A", stats["topChannels"][0]["channel"].asString())
        assertEquals(30, stats["topChannels"][0]["plays"].asInt())
        assertEquals(10, stats["bySource"]["musicApp"].asInt())

        // 삭제하면 S3 파일과 세션이 함께 사라진다
        assertEquals(204, call("DELETE", "/api/sessions/$token").first)
        assertFailsWith<NoSuchKeyException> { s3().use { it.headObject { h -> h.bucket(BUCKET).key("uploads/$uploadId.json") } } }
        assertEquals(404, call("GET", "/api/sessions/$token").first)
    }

    @Test
    fun `곡 고르기 세션 - 3~5곡 저장`() {
        val token = call("POST", "/api/sessions", mapOf("entry" to "SEEDS")).second!!["token"].asString()
        // mbid는 선택 값 (Last.fm 결과에는 없는 경우가 많음)
        val tracks = listOf(
            mapOf("mbid" to UUID.randomUUID().toString(), "title" to "곡 1", "artist" to "가수"),
            mapOf("mbid" to null, "title" to "곡 2", "artist" to "가수"),
            mapOf("title" to "곡 3", "artist" to "가수"),
        )
        // 같은 곡(제목·가수, 대소문자 무시)을 두 번 고를 수 없다
        val dup = tracks.take(2) + mapOf("title" to "곡 1", "artist" to "가수")
        assertEquals("VALIDATION", call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to dup)).second!!["code"].asString())

        assertEquals("VALIDATION", call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to tracks.take(2))).second!!["code"].asString())
        assertEquals(200, call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to tracks)).first)

        val (_, view) = call("GET", "/api/sessions/$token")
        assertEquals("DONE", view!!["status"].asString())
        assertEquals(3, view["seeds"].size())
        assertTrue(view["seeds"][1]["mbid"].isNull)
        assertEquals("INVALID_STATE", call("GET", "/api/sessions/$token/stats").second!!["code"].asString())
        // 같은 세션에 다시 저장할 수 없다
        assertEquals(409, call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to tracks)).first)

        // 고른 곡도 식별을 맡긴다 (L2-2): "가수 - Topic" 채널 키로 대상을 만들고 커밋 뒤 identify 큐로
        val sid = sessions.findActiveByTokenHash(Tokens.hash(token))!!.id
        assertEquals(3, identify.progress(sid).target)
        val msg = sqs().use { c ->
            val url = c.getQueueUrl { it.queueName(IDENTIFY_QUEUE) }.queueUrl()
            c.receiveMessage { it.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5) }.messages()
        }.map { json.readTree(it.body()) }.single { it["sessionId"].asLong() == sid }
        assertEquals(setOf("곡 1|가수 - topic", "곡 2|가수 - topic", "곡 3|가수 - topic"), msg["keys"].toList().map { it.asString() }.toSet())

        // worker 흉내: 곡 1을 찾아 연결하면 결과에 원곡 음반과 표지가 붙는다
        val trackId = identify.upsertTrack(track("곡 1", "가수", "첫 앨범", "2019-05-01"))
        identify.saveCover(trackId, "https://coverartarchive.org/release/x/front-250")
        jdbc.sql("UPDATE seed_track SET track_id = :t WHERE session_id = :sid AND position = 1").param("t", trackId).param("sid", sid).update()
        val seeds = call("GET", "/api/sessions/$token").second!!["seeds"]
        assertEquals("첫 앨범", seeds[0]["releaseTitle"].asString())
        assertEquals(2019, seeds[0]["releaseYear"].asInt())
        assertTrue(seeds[0]["coverUrl"].asString().startsWith("https://coverartarchive.org/"))
        assertTrue(seeds[1]["coverUrl"].isNull)
    }

    @Test
    fun `잘못된 요청과 없는 세션`() {
        assertEquals(400, call("POST", "/api/sessions", mapOf("entry" to "OTHER")).first)
        assertEquals(404, call("GET", "/api/sessions/짧은토큰").first)
        assertEquals(404, call("GET", "/api/sessions/${Tokens.newToken()}").first)

        val token = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT")).second!!["token"].asString()
        val tooBig = mapOf("totalCount" to 1, "musicCount" to 1, "unclassifiedCount" to 0, "byteSize" to 21L * 1024 * 1024)
        assertEquals("TOO_LARGE", call("POST", "/api/sessions/$token/upload-url", tooBig).second!!["code"].asString())
        val wrongSum = mapOf("totalCount" to 1, "musicCount" to 1, "unclassifiedCount" to 1, "byteSize" to 10)
        assertEquals("VALIDATION", call("POST", "/api/sessions/$token/upload-url", wrongSum).second!!["code"].asString())
        // 곡 고르기 세션이 아니면 seeds 불가
        assertEquals("INVALID_STATE", call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to emptyList<Any>())).second!!["code"].asString())
    }

    @Test
    fun `만료된 세션은 조회·통계·삭제 모두 404`() {
        val token = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT")).second!!["token"].asString()
        assertEquals(200, call("GET", "/api/sessions/$token").first)
        jdbc.sql("UPDATE session SET expires_at = now() - interval '1 second' WHERE token_hash = :h").param("h", Tokens.hash(token)).update()

        assertEquals("SESSION_NOT_FOUND", call("GET", "/api/sessions/$token").second!!["code"].asString())
        assertEquals(404, call("GET", "/api/sessions/$token/stats").first)
        assertEquals(404, call("POST", "/api/sessions/$token/upload-url", mapOf("totalCount" to 1, "musicCount" to 1, "unclassifiedCount" to 0, "byteSize" to 10)).first)
        assertEquals(404, call("DELETE", "/api/sessions/$token").first)
    }

    @Test
    fun `통계는 한국 시간 기준 - 자정 전후가 다른 시간·요일·월로 들어간다`() {
        val token = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT")).second!!["token"].asString()
        val sid = sessions.findActiveByTokenHash(Tokens.hash(token))!!.id
        val uploadId = UUID.randomUUID()
        uploads.create(uploadId, sid, "uploads/$uploadId.json")
        val times = listOf(
            "2026-10-25T14:59:00Z", // 한국 10/25(일) 23:59
            "2026-10-25T15:00:00Z", // 한국 10/26(월) 00:00
            "2026-10-31T15:00:00Z", // 한국 11/1(일) 00:00
        ).map(Instant::parse)
        listens.insertBatch(sid, uploadId, times.map { ListenEvent("v", "노래", "가수 - Topic", it, Source.MUSIC_APP) }, Instant.now().plus(7, ChronoUnit.DAYS))
        sessions.markDone(sid, times.size, times.first(), times.last())

        val stats = call("GET", "/api/sessions/$token/stats").second!!
        assertEquals(1, stats["byHour"][23].asInt())
        assertEquals(2, stats["byHour"][0].asInt())
        assertEquals(1, stats["byWeekday"][0].asInt()) // 월
        assertEquals(2, stats["byWeekday"][6].asInt()) // 일
        assertEquals(listOf("2026-10" to 2, "2026-11" to 1), stats["byMonth"].toList().map { it["month"].asString() to it["plays"].asInt() })
        assertEquals("2026-10-25", stats["period"]["start"].asString())
        assertEquals("2026-11-01", stats["period"]["end"].asString())
        assertEquals(8, stats["period"]["days"].asInt())
    }

    @Test
    fun `추천 평가 - 추천된 곡만, 다시 누르면 바뀌고, 비교군은 응답에 없으며, 세션을 지워도 집계용으로 남는다`() {
        val token = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT")).second!!["token"].asString()
        val sid = sessions.findActiveByTokenHash(Tokens.hash(token))!!.id
        val a = identify.upsertTrack(track("추천 1", "가수", "음반", "2020-01-01"))
        val b = identify.upsertTrack(track("추천 2", "가수", "음반", "2020-01-01"))
        listOf(a to "RAG", b to "NORAG").forEachIndexed { i, (t, v) ->
            jdbc.sql("INSERT INTO recommendation (session_id, rank, track_id, score, reason, model, reason_variant, score_rank, source) VALUES (:s, :r, :t, 0.5, '이유', 'm', :v, :r, 'LASTFM')")
                .param("s", sid).param("r", i + 1).param("t", t).param("v", v).update()
        }
        val path = "/api/sessions/$token/recommendations"
        assertEquals(204, call("PUT", "$path/$a/feedback", mapOf("rating" to "LIKE")).first)
        assertEquals(204, call("PUT", "$path/$b/feedback", mapOf("rating" to "LIKE")).first)
        assertEquals(204, call("PUT", "$path/$b/feedback", mapOf("rating" to "DISLIKE", "reasonWrong" to true)).first)
        assertEquals(400, call("PUT", "$path/$b/feedback", mapOf("rating" to "MEH")).first)
        assertEquals(404, call("PUT", "$path/999999/feedback", mapOf("rating" to "LIKE")).first)

        val items = call("GET", path).second!!["items"]
        assertEquals(listOf("LIKE", "DISLIKE"), items.toList().map { it["rating"].asString() })
        assertTrue(items[1]["reasonWrong"].asBoolean())
        assertTrue(items.toList().none { it.has("reasonVariant") }, "비교군은 화면에 보내지 않는다")

        assertEquals(204, call("DELETE", "$path/$a/feedback").first)
        assertTrue(call("GET", path).second!!["items"][0]["rating"].isNull)

        call("DELETE", "/api/sessions/$token")
        val left = jdbc.sql("SELECT session_id IS NULL, rating, reason_variant FROM rec_feedback WHERE track_id = :t").param("t", b)
            .query { rs, _ -> Triple(rs.getBoolean(1), rs.getString(2), rs.getString(3)) }.single()
        assertEquals(Triple(true, "DISLIKE", "NORAG"), left)
    }

    private fun track(title: String, artist: String, release: String, date: String) = TrackInfo(
        recordingMbid = UUID.randomUUID(), title = title,
        artist = ArtistInfo(UUID.randomUUID(), artist, artist, emptyList()),
        releaseMbid = UUID.randomUUID(), releaseGroupMbid = UUID.randomUUID(), releaseTitle = release, releaseDate = date, releaseType = "Single",
    )

    @Test
    fun `곡 단위 순위 - 식별된 곡은 곡으로 합치고 YouTube 표기로, 못 찾은 곡은 그대로 섞는다`() {
        val token = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT")).second!!["token"].asString()
        val sid = sessions.findActiveByTokenHash(Tokens.hash(token))!!.id
        val uploadId = UUID.randomUUID()
        uploads.create(uploadId, sid, "uploads/$uploadId.json")
        val t0 = Instant.parse("2026-10-01T00:00:00Z")
        fun ev(title: String, channel: String, n: Int) = (0 until n).map { ListenEvent("v", title, channel, t0.plusSeconds(it * 60L), Source.YOUTUBE) }
        listens.insertBatch(sid, uploadId, ev("밤편지", "아이유 - Topic", 3) + ev("[MV] 밤편지", "1theK", 2) + ev("가상곡", "가수B - Topic", 4), Instant.now().plus(7, ChronoUnit.DAYS))
        sessions.markDone(sid, 9, t0, t0)

        // MusicBrainz 공식 표기는 "IU", "밤편지 (Through the Night)"
        val trackId = identify.upsertTrack(track("밤편지 (Through the Night)", "IU", "밤편지", "2017-03-24"))
        identify.saveCover(trackId, "https://coverartarchive.org/release/y/front-250")
        jdbc.sql("UPDATE listen_event SET track_id = :t WHERE session_id = :sid AND title IN ('밤편지', '[MV] 밤편지')").param("t", trackId).param("sid", sid).update()

        val stats = call("GET", "/api/sessions/$token/stats").second!!
        val songs = stats["topSongs"]
        assertEquals(2, songs.size())
        with(songs[0]) {
            assertEquals(trackId, this["trackId"].asLong())
            assertEquals("밤편지", this["title"].asString()) // 사용자가 가장 많이 본 제목
            assertEquals("아이유", this["artist"].asString()) // Topic 채널 이름 (1theK 같은 일반 채널은 쓰지 않음)
            assertEquals(5, this["plays"].asInt()) // 두 채널 기록이 한 곡으로
            assertEquals(2017, this["release"]["year"].asInt())
            assertTrue(this["coverUrl"].asString().startsWith("https://coverartarchive.org/"))
        }
        with(songs[1]) {
            assertTrue(this["trackId"].isNull)
            assertEquals("가상곡", this["title"].asString())
            assertEquals("가수B", this["artist"].asString())
            assertEquals(4, this["plays"].asInt())
        }
        assertEquals(listOf("아이유" to 5, "가수B" to 4), stats["topArtists"].toList().map { it["name"].asString() to it["plays"].asInt() })
        assertEquals(5, stats["identified"]["plays"].asInt())
        assertEquals(9, stats["identified"]["totalPlays"].asInt())
    }

    @Test
    fun `우리 DB 곡 검색 - 붙여쓰기·부분·초성·오타·별칭, DB에 없는 곡은 가로채지 않는다`() {
        // 식별된 곡과 사용자가 본 YouTube 표기 (worker가 하는 일을 흉내)
        val night = identify.upsertTrack(track("밤편지 (Through the Night)", "IU", "밤편지", "2017-03-24"))
        identify.saveAlias("밤편지|아이유 - topic", night, AliasMethod.RULE, Query("아이유", "밤편지", false))
        val comet = identify.upsertTrack(track("혜성", "Younha", "혜성", "2006-01-01"))
        identify.saveAlias("혜성|윤하 - topic", comet, AliasMethod.RULE, Query("윤하", "혜성", false))
        fun search(q: String) = call("GET", "/api/tracks/search?q=${java.net.URLEncoder.encode(q, Charsets.UTF_8)}").second!!

        for (q in listOf("아이유밤편지", "밤편", "ㅂㅍㅈ", "IU 밤편지", "아이유 - 밤편지!")) {
            val first = search(q)["items"][0]
            assertEquals(night, first["trackId"].asLong(), q)
            assertEquals("밤편지", first["title"].asString(), "표시는 YouTube 표기") // MusicBrainz 표기 아님
            assertEquals("아이유", first["artist"].asString())
            assertEquals(2017, first["releaseYear"].asInt())
        }
        assertEquals(comet, search("윤하 헤성")["items"][0]["trackId"].asLong(), "가수가 맞는 오타")

        // DB에 없는 곡: 같은 가수의 다른 곡이 1위를 가로채지 않는다 (가짜 fetcher는 늘 "Rain · 태연"을 준다)
        val miss = search("아이유 라일락")
        assertTrue(miss["items"][0]["trackId"].isNull)
        assertEquals("lastfm", miss["source"].asString())

        // DB와 Last.fm을 합칠 때: DB 결과가 앞, 출처는 db+lastfm
        assertEquals("db+lastfm", search("밤편지")["source"].asString())

        // Last.fm이 장애여도 DB 결과가 있으면 503이 아니다
        fakeFetcherUp = false
        try {
            val (code, body) = call("GET", "/api/tracks/search?q=${java.net.URLEncoder.encode("밤편지", Charsets.UTF_8)}")
            assertEquals(200, code)
            assertEquals("db", body!!["source"].asString())
        } finally {
            fakeFetcherUp = true
        }

        // DB 곡을 고르면 식별 없이 바로 연결된다 (식별 대상이 생기지 않음)
        val token = call("POST", "/api/sessions", mapOf("entry" to "SEEDS")).second!!["token"].asString()
        val picks = listOf(
            mapOf("title" to "밤편지", "artist" to "아이유", "trackId" to night),
            mapOf("title" to "혜성", "artist" to "윤하", "trackId" to comet),
            mapOf("title" to "없는 곡", "artist" to "가수", "trackId" to 999_999),
        )
        assertEquals(200, call("POST", "/api/sessions/$token/seeds", mapOf("tracks" to picks)).first)
        val sid = sessions.findActiveByTokenHash(Tokens.hash(token))!!.id
        assertEquals(1, identify.progress(sid).target, "없는 trackId만 식별에 맡긴다")
        val seeds = call("GET", "/api/sessions/$token").second!!["seeds"]
        assertEquals("밤편지", seeds[0]["releaseTitle"].asString())
        assertTrue(seeds[2]["releaseTitle"].isNull)
    }

    @Test
    fun `일반 영상 음악 판정 - ID만 받아 음악인 것만 돌려주고, 30일 캐시, 판정 실패면 건너뛴다`() {
        val token = call("POST", "/api/sessions", mapOf("entry" to "TAKEOUT")).second!!["token"].asString()
        fun check(ids: List<String>) = call("POST", "/api/sessions/$token/video-check", mapOf("videoIds" to ids))

        val first = check(listOf("m-music-01", "v-vlog-001", "x-deleted1")).second!!
        assertEquals(listOf("m-music-01"), first["musicIds"].toList().map { it.asString() })
        assertEquals(3, first["fromApi"].asInt())
        assertEquals(false, first["skipped"].asBoolean())

        // 두 번째는 캐시 (삭제된 영상도 다시 묻지 않음), 새 ID만 YouTube로
        youtubeAsked.clear()
        val second = check(listOf("m-music-01", "x-deleted1", "m-music-02")).second!!
        assertEquals(listOf("m-music-01", "m-music-02"), second["musicIds"].toList().map { it.asString() })
        assertEquals(2, second["fromCache"].asInt())
        assertEquals(listOf("m-music-02"), youtubeAsked.toList())

        // YouTube 쪽 장애: 오류가 아니라 판정을 건너뛰었다고 알려 준다 (캐시에 있는 것은 그대로)
        fakeFetcherUp = false
        try {
            val skipped = check(listOf("m-music-01", "m-music-03")).second!!
            assertTrue(skipped["skipped"].asBoolean())
            assertEquals(listOf("m-music-01"), skipped["musicIds"].toList().map { it.asString() })
        } finally {
            fakeFetcherUp = true
        }

        assertEquals("VALIDATION", check(listOf("잘못된 id!")).second!!["code"].asString())
        assertEquals("VALIDATION", check((1..1001).map { "m-video-%04d".format(it) }).second!!["code"].asString())
        // 업로드를 시작한 세션에서는 판정하지 않는다
        call("POST", "/api/sessions/$token/upload-url", mapOf("totalCount" to 1, "musicCount" to 1, "unclassifiedCount" to 0, "byteSize" to 10))
        assertEquals("INVALID_STATE", check(listOf("m-music-01")).second!!["code"].asString())
    }

    @Test
    fun `곡 검색 - external-fetcher 결과를 그대로 돌려주고, 장애면 503`() {
        fakeFetcherUp = true
        val (ok, res) = call("GET", "/api/tracks/search?q=%ED%83%9C%EC%97%B0&limit=5")
        assertEquals(200, ok)
        assertEquals("Rain", res!!["items"][0]["title"].asString())
        assertEquals("lastfm", res["source"].asString())
        assertTrue(fetcherQueries.last().contains("q=%ED%83%9C%EC%97%B0") || fetcherQueries.last().contains("q=태연"))

        assertEquals("VALIDATION", call("GET", "/api/tracks/search?q=a").second!!["code"].asString())

        fakeFetcherUp = false
        assertEquals("SEARCH_UNAVAILABLE", call("GET", "/api/tracks/search?q=rain").second!!["code"].asString())
    }
}
