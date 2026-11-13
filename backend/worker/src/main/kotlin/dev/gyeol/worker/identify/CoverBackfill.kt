package dev.gyeol.worker.identify

import dev.gyeol.core.identify.IdentifyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

/**
 * I-31: 표지를 한 번도 확인하지 않은 곡을 worker가 켜질 때 한 번 cover 큐로 보낸다.
 * 방에 바로 담는 경로(201)와 예전 데이터가 cover 큐를 거치지 않아 남은 곡(11-14 로컬 41곡). 이미 확인한 곡은 고르지 않아 여러 번 켜져도 같다.
 * cover 역할 worker에서만, gyeol.worker.cover.backfill-on-start=false면 끈다
 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('cover') and '\${gyeol.worker.cover.backfill-on-start:true}' == 'true'")
class CoverBackfill(private val repo: IdentifyRepository, private val identify: IdentifyService) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val ids = repo.uncheckedCoverTrackIds(2000)
        if (ids.isEmpty()) return
        ids.chunked(50).forEach { identify.requestCovers(it) }
        log.info("표지 확인 안 한 곡 {}개를 cover 큐로 보냄", ids.size)
    }
}
