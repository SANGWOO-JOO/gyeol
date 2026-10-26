package dev.gyeol.worker.parse

import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URLDecoder

/**
 * SQS parse 큐를 듣는다 (WORKER_ROLE에 parse가 있을 때만).
 * 메시지는 S3 업로드 알림. 예외가 나면 메시지가 지워지지 않아 다시 받게 되고, 3번 실패하면 실패 큐로 간다.
 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('parse')")
class ParseListener(
    private val service: ParseService,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${gyeol.worker.parse-queue}")
    fun onMessage(body: String) {
        val root = json.readTree(body)
        // 알림을 설정하면 S3가 한 번 보내는 시험 메시지
        if (root.path("Event").asString("") == "s3:TestEvent") {
            log.info("S3 시험 메시지, 무시")
            return
        }
        root.path("Records").toList().forEach { r ->
            // S3 알림의 키는 URL 인코딩되어 온다 (공백은 +)
            val key = URLDecoder.decode(r.path("s3").path("object").path("key").asString(""), Charsets.UTF_8)
            if (!key.startsWith("uploads/")) {
                log.warn("uploads/ 밖의 키, 무시: {}", key)
                return@forEach
            }
            service.process(key)
        }
    }
}
