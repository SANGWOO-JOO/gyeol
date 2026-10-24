package dev.gyeol.worker.parse

import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.session.SessionStatus
import dev.gyeol.core.upload.Upload
import dev.gyeol.core.upload.UploadRepository
import dev.gyeol.core.upload.UploadStatus
import dev.gyeol.worker.WorkerProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

enum class ParseOutcome { DONE, SKIPPED, FAILED }

/**
 * 업로드 파일 하나를 처리한다.
 * - 이미 처리된 업로드면 건너뛴다 (같은 메시지가 두 번 와도 한 번만)
 * - 청취 기록 저장 · 업로드 PROCESSED · 세션 DONE은 한 트랜잭션. 중간에 죽으면 아무것도 남지 않아 재시도해도 중복이 없다
 * - 파일 형식 오류(영구)는 FAILED로 끝내고, S3·DB 연결 오류(일시)는 예외를 던져 큐가 다시 시도하게 한다
 */
@Service
class ParseService(
    private val sessions: SessionRepository,
    private val uploads: UploadRepository,
    private val listens: ListenEventRepository,
    private val s3: S3Client,
    private val json: JsonMapper,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(s3Key: String): ParseOutcome {
        val upload = uploads.findByS3Key(s3Key)
        if (upload == null) {
            // 세션이 이미 삭제되었거나 우리가 발급하지 않은 파일
            log.warn("업로드 기록이 없는 파일, 건너뜀: {}", s3Key)
            return ParseOutcome.SKIPPED
        }
        if (upload.status != UploadStatus.PENDING) {
            log.info("이미 처리된 업로드, 건너뜀: {} ({})", upload.id, upload.status)
            return ParseOutcome.SKIPPED
        }
        val session = sessions.findById(upload.sessionId) ?: return ParseOutcome.SKIPPED
        if (session.status != SessionStatus.UPLOADING && session.status != SessionStatus.PROCESSING) {
            log.warn("처리할 수 없는 세션 상태 {}, 건너뜀: 세션 {}", session.status, session.id)
            return ParseOutcome.SKIPPED
        }
        sessions.transition(session.id, SessionStatus.UPLOADING, SessionStatus.PROCESSING)

        val started = System.nanoTime()
        // S3 읽기 실패는 일시 오류일 수 있으므로 그대로 던진다 (큐가 다시 시도)
        val bytes = s3.getObjectAsBytes { it.bucket(props.uploadBucket).key(s3Key) }.asByteArray()

        val parsed = try {
            UploadFile.parse(bytes, json)
        } catch (e: InvalidUploadException) {
            fail(upload, e.message ?: "파일 형식 오류")
            return ParseOutcome.FAILED
        }

        val expiresAt = Instant.now().plus(props.listenTtl)
        val saved = tx.execute {
            // PENDING → PROCESSED를 먼저 잡아, 동시에 같은 업로드를 처리하는 다른 워커가 있으면 여기서 멈춘다
            if (!uploads.markProcessed(upload.id, parsed.events.size)) return@execute null
            listens.insertBatch(session.id, upload.id, parsed.events, expiresAt)
            val start = parsed.events.minOfOrNull { it.watchedAt }
            val end = parsed.events.maxOfOrNull { it.watchedAt }
            sessions.markDone(session.id, parsed.events.size, start, end)
            parsed.events.size
        }
        if (saved == null) {
            log.info("다른 워커가 먼저 처리한 업로드, 건너뜀: {}", upload.id)
            return ParseOutcome.SKIPPED
        }

        val ms = (System.nanoTime() - started) / 1_000_000
        log.info("처리 완료: 세션 {}, 저장 {}건, 건너뛴 항목 {}건, 파일 {}KB, {}ms", session.id, saved, parsed.skipped, bytes.size / 1024, ms)
        return ParseOutcome.DONE
    }

    private fun fail(upload: Upload, reason: String) {
        tx.executeWithoutResult {
            uploads.markFailed(upload.id)
            sessions.markFailed(upload.sessionId, reason)
        }
        log.warn("처리 실패(재시도 안 함): 업로드 {}, {}", upload.id, reason)
    }
}
