package dev.gyeol.worker.cleanup

import dev.gyeol.core.listen.ListenEventRepository
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.RoomDraftRepository
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.session.SessionRepository
import dev.gyeol.core.upload.UploadRepository
import dev.gyeol.core.video.VideoCheckRepository
import dev.gyeol.worker.WorkerProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import java.time.Instant

data class CleanupResult(val files: Int, val listens: Int, val sessions: Int, val videoChecks: Int = 0, val rooms: Int = 0, val drafts: Int = 0)

/**
 * 보관 기간이 지난 데이터를 지운다 (화면 안내 "30일 보관", 설계 "청취 기록 원본 7일").
 * 1. S3 원본 파일: 업로드 후 7일 또는 세션 만료 → 지우고 file_deleted_at 표시
 * 2. 청취 기록: listen_event.expires_at(7일) 지남
 * 3. 세션: expires_at(30일) 지남, 원본 파일을 다 지운 세션만 (연쇄 삭제)
 * 4. 일반 영상 음악 판정 캐시: 30일 지남 (YouTube API 정책)
 * 5. 음악 방: 숨긴 지 7일 지난 방 완전 삭제(참가자·노래·방장 이메일·선물 캐릭터 연쇄, 캐릭터 그림은 S3에서 먼저, 감사 기록은 IP·닉네임만 지우고 남김),
 *    인증 안 끝난 방 만들기 내용(1시간), 1년 지난 감사 기록
 * 순서가 중요하다: 세션을 먼저 지우면 S3 키를 잃어 파일이 고아로 남는다.
 * 여러 번 돌거나 워커 여러 대가 동시에 돌아도 결과가 같다 (S3 삭제는 없는 키도 성공).
 */
@Service
class CleanupService(
    private val sessions: SessionRepository,
    private val uploads: UploadRepository,
    private val listens: ListenEventRepository,
    private val s3: S3Client,
    private val props: WorkerProperties,
    private val videoChecks: VideoCheckRepository,
    private val rooms: RoomRepository,
    private val drafts: RoomDraftRepository,
    private val gifts: GiftRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(now: Instant = Instant.now()): CleanupResult {
        val started = System.nanoTime()
        val files = deleteFiles(now)
        val listens = repeatUntilDone { listens.deleteExpired(now, BATCH) }
        val sessions = repeatUntilDone { sessions.deleteExpired(now, BATCH) }
        // YouTube API 정책: 판정 결과(카테고리)는 30일 안에 지운다 (L2-3b)
        val checks = videoChecks.deleteOlderThan(now.minus(java.time.Duration.ofDays(30)))
        // 음악 방: 방장·관리자가 지운 방은 7일 뒤 완전 삭제 (11-13 결정 2)
        // 캐릭터 그림(S3)을 먼저 지운다. 하나라도 실패하면 이번에는 방을 지우지 않는다 (키를 잃으면 그림이 고아로 남음)
        val purgedRooms = if (deleteCharacterImages(now)) rooms.purgeHidden(now) else 0
        val purgedDrafts = drafts.deleteExpired(now)
        // 감사 기록은 1년 보관 (11-13 ⑥ 결정)
        val oldAudit = rooms.deleteOldAudit(now.minus(java.time.Duration.ofDays(365)))
        if (oldAudit > 0) log.info("1년 지난 감사 기록 {}건 삭제", oldAudit)
        val result = CleanupResult(files, listens, sessions, checks, purgedRooms, purgedDrafts)
        log.info("정리 완료: 원본 파일 {}개, 청취 기록 {}건, 세션 {}개, 음악 판정 {}건, 방 {}개, 방 만들기 {}건, {}ms", files, listens, sessions, checks, purgedRooms, purgedDrafts, (System.nanoTime() - started) / 1_000_000)
        return result
    }

    private fun deleteCharacterImages(now: Instant): Boolean {
        for (chunk in gifts.keysOfRoomsToPurge(now).chunked(1000)) {
            val res = s3.deleteObjects { req ->
                req.bucket(props.cover.bucket).delete { d -> d.objects(chunk.map { ObjectIdentifier.builder().key(it).build() }) }
            }
            if (res.errors().isNotEmpty()) {
                log.warn("캐릭터 그림 삭제 실패 {}개, 방 완전 삭제를 다음 실행으로 미룸: {}", res.errors().size, res.errors().first().message())
                return false
            }
        }
        return true
    }

    private fun deleteFiles(now: Instant): Int {
        var total = 0
        while (true) {
            // S3 DeleteObjects는 한 번에 최대 1,000개
            val batch = uploads.findFilesToDelete(now.minus(props.listenTtl), now, 1000)
            if (batch.isEmpty()) return total
            val res = s3.deleteObjects { req ->
                req.bucket(props.uploadBucket).delete { d -> d.objects(batch.map { ObjectIdentifier.builder().key(it.second).build() }) }
            }
            val failed = res.errors().map { it.key() }.toSet()
            if (failed.isNotEmpty()) log.warn("S3 삭제 실패 {}개, 다음 실행에서 다시 시도: {}", failed.size, res.errors().first().message())
            val done = batch.filter { it.second !in failed }.map { it.first }
            uploads.markFilesDeleted(done)
            total += done.size
            if (done.isEmpty()) return total // 전부 실패하면 같은 목록을 무한 반복하지 않게 멈춘다
        }
    }

    private fun repeatUntilDone(step: () -> Int): Int {
        var total = 0
        while (true) {
            val n = step()
            total += n
            if (n < BATCH) return total
        }
    }

    companion object {
        private const val BATCH = 5000
    }
}
