package dev.gyeol.worker.identify

import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.worker.WorkerProperties
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.listener.Visibility
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * SQS cover 큐 (WORKER_ROLE에 cover가 있을 때만). 곡마다 원곡 음반 표지 주소를 찾아 저장한다.
 * 곡마다 한 번만 확인하고, 표지가 없던 곡은 [WorkerProperties.Cover.retry] 뒤에 다시 확인한다.
 * 실패하면 큐가 다시 시도하고, 3번 실패하면 실패 큐로 간다 (화면은 글자 타일로 남는다).
 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('cover')")
class CoverListener(
    private val repo: IdentifyRepository,
    private val fetcher: FetcherClient,
    private val json: JsonMapper,
    private val props: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${gyeol.worker.cover.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String, visibility: Visibility) = retrySoonIfBusy(visibility, props.busyRetry.toSeconds().toInt()) { handle(body) }

    private fun handle(body: String) {
        val msg = json.readValue(body, CoverMessage::class.java)
        val retryBefore = Instant.now().minus(props.cover.retry)
        var found = 0
        msg.trackIds.distinct().forEach { id ->
            val t = repo.coverTarget(id) ?: return@forEach
            if (t.checkedAt?.isAfter(retryBefore) == true) return@forEach
            val url = fetcher.cover(t)
            repo.saveCover(id, url)
            if (url != null) found++
        }
        log.info("표지: {}곡 중 {}곡 찾음", msg.trackIds.size, found)
    }
}
