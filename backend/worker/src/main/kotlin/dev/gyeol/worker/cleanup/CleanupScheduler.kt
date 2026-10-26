package dev.gyeol.worker.cleanup

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 하루 한 번 정리 작업을 돌린다 (기본 매일 04:00 한국 시간).
 * EKS에서는 이 스케줄을 끄고(`CLEANUP_ENABLED=false`) CronJob으로 옮길 수 있다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = ["gyeol.worker.cleanup.enabled"], havingValue = "true", matchIfMissing = true)
class CleanupScheduler(private val service: CleanupService) {

    @Scheduled(cron = "\${gyeol.worker.cleanup.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    fun daily() {
        service.run()
    }
}
