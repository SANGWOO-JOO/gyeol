package dev.gyeol.worker.identify

import io.awspring.cloud.sqs.annotation.SqsListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** SQS identify 큐 (WORKER_ROLE에 identify가 있을 때만). 3번 실패하면 실패 큐로 간다 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('identify')")
class IdentifyListener(private val service: IdentifyService, private val json: JsonMapper) {

    @SqsListener("\${gyeol.worker.identify.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String) {
        service.process(json.readValue(body, IdentifyMessage::class.java))
    }
}
