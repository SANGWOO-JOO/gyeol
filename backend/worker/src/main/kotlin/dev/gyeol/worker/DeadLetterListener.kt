package dev.gyeol.worker

import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.upload.UploadRepository
import dev.gyeol.core.upload.UploadStatus
import dev.gyeol.worker.identify.IdentifyMessage
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URLDecoder

/**
 * 3번 실패해 실패 큐로 간 메시지를 사용자에게 보이는 실패로 바꾼다.
 * 그대로 두면 화면이 "처리 중"에서 멈춘다. 원인 분석용으로 로그를 남긴다.
 * 큐를 듣는 워커(parse·identify 역할)에서만 켠다.
 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('parse') or '\${gyeol.worker.role:parse}'.contains('identify')")
class DeadLetterListener(
    private val uploads: UploadRepository,
    private val sessions: SessionRepository,
    private val identify: IdentifyRepository,
    private val json: JsonMapper,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** parse 실패: 업로드·세션을 FAILED로 (아직 처리 전일 때만) */
    @SqsListener("\${gyeol.worker.parse-dlq}")
    fun onParseDeadLetter(body: String) {
        json.readTree(body).path("Records").toList().forEach { r ->
            val key = URLDecoder.decode(r.path("s3").path("object").path("key").asString(""), Charsets.UTF_8)
            val upload = uploads.findByS3Key(key) ?: return@forEach
            if (upload.status != UploadStatus.PENDING) return@forEach
            tx.executeWithoutResult {
                uploads.markFailed(upload.id)
                sessions.markFailed(upload.sessionId, "처리 중 오류가 반복되었습니다. 잠시 뒤 다시 올려 주세요")
            }
            log.error("parse 실패 큐: 업로드 {} (세션 {})를 FAILED로 표시", upload.id, upload.sessionId)
        }
    }

    /** identify 실패: 묶음의 곡을 FAILED로 (진행률이 멈추지 않게) */
    @SqsListener("\${gyeol.worker.identify.dlq}")
    fun onIdentifyDeadLetter(body: String) {
        val msg = json.readValue(body, IdentifyMessage::class.java)
        val n = msg.keys.count { identify.finishTarget(msg.sessionId, it, TargetStatus.FAILED) }
        log.error("identify 실패 큐: 세션 {}, {}곡을 FAILED로 표시", msg.sessionId, n)
    }
}
