package dev.gyeol.worker

import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.Session
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.SessionStatus
import dev.gyeol.core.session.Tokens
import dev.gyeol.core.upload.UploadRepository
import org.junit.jupiter.api.BeforeAll
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
import software.amazon.awssdk.services.s3.model.Event
import software.amazon.awssdk.services.s3.model.NotificationConfiguration
import software.amazon.awssdk.services.s3.model.QueueConfiguration
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S3 업로드 → SQS 알림 → parse 워커 → DB 저장까지 실제와 같은 경로로 확인한다 */
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "gyeol.worker.parse-queue=gyeol-test-parse",
        "gyeol.worker.upload-bucket=gyeol-test-uploads",
        "gyeol.worker.role=parse",
        "gyeol.worker.parse-dlq=gyeol-test-parse-dlq",
        "gyeol.worker.identify.queue=gyeol-test-identify", // 계획은 큐에 넣기만 한다 (identify 리스너는 꺼짐)
        "gyeol.worker.cleanup.enabled=false",
    ],
)
class ParseWorkerIntegrationTest {

    companion object {
        private const val BUCKET = "gyeol-test-uploads"
        private const val QUEUE = "gyeol-test-parse"
        private const val DLQ = "gyeol-test-parse-dlq"

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

        private val creds by lazy { StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)) }
        val s3: S3Client by lazy {
            S3Client.builder().endpointOverride(localstack.endpoint).region(Region.of(localstack.region)).credentialsProvider(creds).forcePathStyle(true).build()
        }
        val sqs: SqsClient by lazy {
            SqsClient.builder().endpointOverride(localstack.endpoint).region(Region.of(localstack.region)).credentialsProvider(creds).build()
        }
        lateinit var queueUrl: String
        lateinit var dlqUrl: String

        /** 로컬 환경(local/localstack/init-aws.sh)과 같은 구성: 버킷, 큐 + 실패 큐(3번), uploads/ → 큐 알림 */
        @BeforeAll
        @JvmStatic
        fun infra() {
            s3.createBucket { it.bucket(BUCKET) }
            sqs.createQueue { it.queueName("gyeol-test-identify") }
            dlqUrl = sqs.createQueue { it.queueName(DLQ) }.queueUrl()
            val dlqArn = sqs.getQueueAttributes { it.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN) }.attributes()[QueueAttributeName.QUEUE_ARN]
            queueUrl = sqs.createQueue {
                it.queueName(QUEUE).attributes(
                    mapOf(
                        QueueAttributeName.VISIBILITY_TIMEOUT to "30",
                        QueueAttributeName.REDRIVE_POLICY to """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"3"}""",
                    ),
                )
            }.queueUrl()
            val queueArn = sqs.getQueueAttributes { it.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN) }.attributes()[QueueAttributeName.QUEUE_ARN]
            s3.putBucketNotificationConfiguration {
                it.bucket(BUCKET).notificationConfiguration(
                    NotificationConfiguration.builder().queueConfigurations(
                        QueueConfiguration.builder().queueArn(queueArn).events(Event.S3_OBJECT_CREATED).build(),
                    ).build(),
                )
            }
        }
    }

    @Autowired lateinit var sessions: SessionRepository
    @Autowired lateinit var uploads: UploadRepository
    @Autowired lateinit var jdbc: JdbcClient

    /** api가 업로드 주소를 발급한 직후와 같은 상태를 만든다: 세션 UPLOADING + 업로드 PENDING */
    private fun newUpload(): Pair<Long, String> {
        val sid = sessions.create(Tokens.hash(Tokens.newToken()), Entry.TAKEOUT, Instant.now().plus(30, ChronoUnit.DAYS))
        sessions.markUploading(sid, 100, 10, 50)
        val id = UUID.randomUUID()
        val key = "uploads/$id.json"
        uploads.create(id, sid, key)
        return sid to key
    }

    private fun item(i: Int) =
        """{"videoId":"vid${i % 50}","title":"곡 ${i % 50}","channel":"가수 ${i % 7} - Topic","watchedAt":"${Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i * 600L)}","source":"${if (i % 3 == 0) "MUSIC_APP" else "YOUTUBE"}"}"""

    private fun file(n: Int, extra: List<String> = emptyList()) =
        """{"schemaVersion":1,"items":[${((0 until n).map(::item) + extra).joinToString(",")}]}"""

    private fun put(key: String, body: String) = s3.putObject({ it.bucket(BUCKET).key(key) }, RequestBody.fromString(body))

    private fun waitFor(sid: Long, timeout: Duration = Duration.ofSeconds(30), until: (Session) -> Boolean): Session {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val s = sessions.findById(sid)!!
            if (until(s)) return s
            Thread.sleep(200)
        }
        error("시간 안에 기대한 상태가 되지 않았습니다: ${sessions.findById(sid)}")
    }

    private fun listenCount(sid: Long) = jdbc.sql("SELECT count(*) FROM listen_event WHERE session_id = :sid").param("sid", sid).query(Long::class.java).single()

    private fun queueDepth(url: String) = sqs.getQueueAttributes {
        it.queueUrl(url).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
    }.attributes().values.sumOf { it.toInt() }

    @Test
    fun `업로드하면 청취 기록을 저장하고 DONE이 된다, 잘못된 항목은 건너뛴다`() {
        val (sid, key) = newUpload()
        val bad = listOf(
            """{"title":"","watchedAt":"2025-01-01T00:00:00Z","source":"YOUTUBE"}""", // 빈 제목
            """{"title":"곡","watchedAt":"어제","source":"YOUTUBE"}""", // 시각 형식 오류
            """{"title":"곡","watchedAt":"2025-01-01T00:00:00Z","source":"TV"}""", // 출처 오류
        )
        put(key, file(120, bad))

        val done = waitFor(sid) { it.status == SessionStatus.DONE }
        assertEquals(120, done.savedCount)
        assertEquals(120L, listenCount(sid))
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), done.periodStart)
        assertEquals(Instant.parse("2025-01-01T00:00:00Z").plusSeconds(119 * 600L), done.periodEnd)
    }

    @Test
    fun `같은 메시지가 다시 와도 한 번만 저장한다`() {
        val (sid, key) = newUpload()
        put(key, file(30))
        waitFor(sid) { it.status == SessionStatus.DONE }

        // S3 알림과 같은 모양의 메시지를 한 번 더 보낸다
        sqs.sendMessage { it.queueUrl(queueUrl).messageBody("""{"Records":[{"s3":{"bucket":{"name":"$BUCKET"},"object":{"key":"$key"}}}]}""") }
        Thread.sleep(3000)
        assertEquals(30L, listenCount(sid))
        assertEquals(0, queueDepth(queueUrl), "메시지는 처리(건너뜀) 후 지워져야 합니다")
    }

    @Test
    fun `형식이 틀린 파일은 재시도하지 않고 FAILED로 끝낸다`() {
        val (sid, key) = newUpload()
        put(key, "이건 JSON이 아니다")

        val failed = waitFor(sid) { it.status == SessionStatus.FAILED }
        assertTrue(failed.errorMessage!!.contains("JSON"))
        Thread.sleep(2000)
        assertEquals(0, queueDepth(queueUrl), "영구 오류는 메시지를 지워 재시도하지 않습니다")
        assertEquals(0, queueDepth(dlqUrl))
    }

    @Test
    fun `S3 시험 메시지는 무시한다`() {
        sqs.sendMessage { it.queueUrl(queueUrl).messageBody("""{"Service":"Amazon S3","Event":"s3:TestEvent","Bucket":"$BUCKET"}""") }
        Thread.sleep(3000)
        assertEquals(0, queueDepth(queueUrl))
        assertEquals(0, queueDepth(dlqUrl))
    }

    @Test
    fun `실제 규모 - 음악 항목 2만 5천 건 처리 시간`() {
        val (sid, key) = newUpload()
        val n = 25_409 // 실제 내 활동 파일에서 규칙 필터가 고른 음악 건수
        val body = file(n)
        val started = System.nanoTime()
        put(key, body)
        val done = waitFor(sid, Duration.ofSeconds(60)) { it.status == SessionStatus.DONE }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("실제 규모 처리: ${n}건, 파일 ${body.length / 1024}KB, 업로드부터 DONE까지 ${ms}ms")
        assertEquals(n, done.savedCount)
    }
}
