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

/** [apiDerived]: I-48 4-2로 지운 판정 경로 행 수 (별칭·추측·메들리·식별 대상 합) */
data class CleanupResult(
    val files: Int, val listens: Int, val sessions: Int, val videoChecks: Int = 0, val rooms: Int = 0, val drafts: Int = 0, val apiDerived: Int = 0,
    /** I-56: 이번에 숨긴 쓰지 않는 방 수 */
    val unusedRooms: Int = 0,
    /** I-57: 지운 메일 발송 기록 + IP를 지운 관리자 세션 수 */
    val oldIps: Int = 0,
)

/**
 * 보관 기간이 지난 데이터를 지운다. 기간은 대상마다 다르다: 업로드 세션(결과 화면)은 30일, 청취 기록·업로드 원본은 7일
 * (원본은 S3 버킷 수명 규칙이 먼저 1일에 지운다, infra/terraform/base/s3.tf).
 * 1. S3 원본 파일: 업로드 후 7일 또는 세션 만료 → 지우고 file_deleted_at 표시
 * 2. 청취 기록: listen_event.expires_at(7일) 지남
 * 3. 세션: expires_at(30일) 지남, 원본 파일을 다 지운 세션만 (연쇄 삭제)
 * 4. 일반 영상 음악 판정 캐시: [VideoCheckRepository.RETENTION](29일) 지남 (YouTube API 정책 30일, 하루 한 번 정리라 하루 여유)
 * 5. 음악 방: 숨긴 지 7일 지난 방 완전 삭제(참가자·노래·방장 이메일·선물 캐릭터 연쇄, 캐릭터 그림은 S3에서 먼저, 감사 기록은 IP·닉네임만 지우고 남김),
 *    인증 안 끝난 방 만들기 내용(1시간), 1년 지난 감사 기록
 * 6. 쓰지 않는 방(I-56): 마지막 사용 1년 뒤 숨김(감사 기록 SYSTEM ROOM_EXPIRE_HIDE) → 5번 흐름으로 7일 뒤 완전 삭제
 * 7. IP(I-57): 메일 발송 기록 90일 뒤 삭제, 관리자 세션 IP 90일 뒤 지움
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
    private val identify: dev.gyeol.core.identify.IdentifyRepository,
    private val search: dev.gyeol.core.identify.TrackSearchRepository,
    private val guessesRepo: dev.gyeol.core.identify.TitleGuessRepository,
    private val admins: dev.gyeol.core.admin.AdminRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(now: Instant = Instant.now()): CleanupResult {
        val started = System.nanoTime()
        val files = deleteFiles(now)
        val listens = repeatUntilDone { listens.deleteExpired(now, BATCH) }
        val sessions = repeatUntilDone { sessions.deleteExpired(now, BATCH) }
        // YouTube API 정책: 판정 결과(카테고리)는 30일 안에 지운다 (L2-3b). 기준은 조회와 같은 29일 (I-48)
        val checks = videoChecks.deleteOlderThan(now.minus(VideoCheckRepository.RETENTION))
        val derived = deleteApiDerived(now)
        // 음악 방: 방장·관리자가 지운 방은 7일 뒤 완전 삭제 (11-13 결정 2)
        // 캐릭터 그림(S3)을 먼저 지운다. 하나라도 실패하면 이번에는 방을 지우지 않는다 (키를 잃으면 그림이 고아로 남음)
        val expiredRooms = hideUnusedRooms(now)
        val purgedRooms = if (deleteCharacterImages(now)) rooms.purgeHidden(now) else 0
        val purgedDrafts = drafts.deleteExpired(now)
        // 감사 기록은 1년 보관 (11-13 ⑥ 결정)
        val oldAudit = rooms.deleteOldAudit(now.minus(java.time.Duration.ofDays(365)))
        if (oldAudit > 0) log.info("1년 지난 감사 기록 {}건 삭제", oldAudit)
        // I-57: 메일 발송 기록(IP·이메일 해시)과 관리자 세션 IP는 90일
        val ipBefore = now.minus(props.room.ipRetention)
        val mails = drafts.deleteOldMailLogs(ipBefore)
        val adminIps = admins.clearOldSessionIps(ipBefore)
        if (mails + adminIps > 0) log.info("90일 지난 IP 정리: 메일 발송 기록 {}건 삭제, 관리자 세션 IP {}건", mails, adminIps)
        val result = CleanupResult(files, listens, sessions, checks, purgedRooms, purgedDrafts, derived, expiredRooms, mails + adminIps)
        log.info("정리 완료: 원본 파일 {}개, 청취 기록 {}건, 세션 {}개, 음악 판정 {}건, 방 {}개, 방 만들기 {}건, {}ms", files, listens, sessions, checks, purgedRooms, purgedDrafts, (System.nanoTime() - started) / 1_000_000)
        return result
    }

    /**
     * I-48 4-2: YouTube 카테고리 판정을 거쳐서만 들어온 제목에서 나온 전역 행을 판정 뒤 [VideoCheckRepository.RETENTION](29일)에 지운다.
     * 지운 별칭의 YouTube 표기가 검색 색인에 남지 않게 그 곡들만 색인을 다시 만든다. 곡·가수·방 노래는 지우지 않는다
     */
    private fun deleteApiDerived(now: Instant): Int {
        val before = now.minus(VideoCheckRepository.RETENTION)
        val tracks = identify.deleteExpiredAliases(before)
        tracks.forEach(search::rebuild)
        val (guesses, medleySongs) = guessesRepo.deleteExpired(before)
        val targets = identify.deleteExpiredTargets(before)
        val total = tracks.size + guesses + medleySongs + targets
        if (total > 0) log.info("판정 경로 행 정리: 별칭 곡 {}개 색인 다시, 추측 {}건, 메들리 곡 {}건, 식별 대상 {}건", tracks.size, guesses, medleySongs, targets)
        return total
    }

    /** I-56: 마지막 사용 1년 지난 방을 숨기고(7일 뒤 완전 삭제) 감사 기록을 남긴다. 숨긴 방 수 */
    private fun hideUnusedRooms(now: Instant): Int {
        val unused = rooms.unusedRooms(now.minus(props.room.unusedHideAfter))
        var hidden = 0
        unused.forEach { (id, lastUsed) ->
            if (rooms.hide(id, now, now.plus(props.room.purgeAfter)) == 1) {
                rooms.audit("SYSTEM", null, id, "ROOM_EXPIRE_HIDE", detailJson = """{"lastUsedAt":"$lastUsed"}""")
                hidden++
            }
        }
        if (hidden > 0) log.info("쓰지 않는 방 {}개 숨김 (마지막 사용 {} 전), {} 뒤 완전 삭제", hidden, props.room.unusedHideAfter, props.room.purgeAfter)
        return hidden
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
