package dev.gyeol.worker.identify

import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.worker.WorkerProperties
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.listener.Visibility
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
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
    private val s3: S3Client,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${gyeol.worker.cover.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String, visibility: Visibility) = retrySoonIfBusy(visibility, props.busyRetry.toSeconds().toInt()) { handle(body) }

    private fun handle(body: String) {
        val msg = json.readValue(body, CoverMessage::class.java)
        val retryBefore = Instant.now().minus(props.cover.retry)
        var found = 0
        var stored = 0
        var transientFailure: Exception? = null
        msg.trackIds.distinct().forEach { id ->
            val t = repo.coverTarget(id) ?: return@forEach
            val checked = t.checkedAt?.isAfter(retryBefore) == true
            if (checked && (t.coverUrl == null || t.coverKey != null)) return@forEach
            val url = if (checked) {
                t.coverUrl
            } else {
                // 음반 없이 식별된 곡은 글자 타일로 둔다
                (if (t.releaseMbid == null && t.releaseGroupMbid == null) null else fetcher.cover(t)).also { repo.saveCover(id, it) }
            }
            if (url == null) return@forEach
            found++
            try {
                store(id, url)
                stored++
            } catch (e: HttpClientErrorException) {
                log.warn("표지 이미지 없음: 곡 {}, {}", id, e.statusCode) // 다시 시도해도 같다
            } catch (e: RuntimeException) {
                transientFailure = e // 나머지 곡은 계속 받고, 끝난 뒤 메시지를 다시 받는다
            }
        }
        log.info("표지: {}곡 중 {}곡 찾음, {}곡 저장", msg.trackIds.size, found, stored)
        transientFailure?.let { throw it }
    }

    private fun store(trackId: Long, url: String) {
        val image = fetcher.coverImage(url)
        val key = "covers/$trackId"
        val type = image.headers.contentType?.toString() ?: "image/jpeg"
        s3.putObject({ it.bucket(props.cover.bucket).key(key).contentType(type) }, RequestBody.fromBytes(image.body ?: error("표지 응답이 비어 있습니다")))
        repo.saveCoverKey(trackId, key)
    }
}
