package dev.gyeol.worker.identify

import dev.gyeol.core.identify.AliasMethod
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.RuleIdentifier
import dev.gyeol.core.identify.Target
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.worker.WorkerProperties
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * 식별 묶음 하나를 처리한다. 곡마다: 캐시 다시 확인 → 규칙으로 (가수, 곡) → fetcher(MusicBrainz) → 저장.
 * fetcher 장애는 예외로 던져 메시지를 다시 받는다. 이미 끝난 곡은 건너뛰므로 다시 받아도 결과가 같다.
 */
@Service
class IdentifyService(
    private val repo: IdentifyRepository,
    private val fetcher: FetcherClient,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(msg: IdentifyMessage) {
        val started = System.nanoTime()
        val found = repo.targets(msg.sessionId, msg.keys).filter { it.status == TargetStatus.PENDING }.mapNotNull { identifyOne(it) }
        // 표지를 아직 확인하지 않은 곡은 표지 큐로 (식별과 따로, 늦게 붙어도 곡 이름은 먼저 보인다)
        val needCover = found.distinct().filter { repo.coverTarget(it)?.checkedAt == null }
        if (needCover.isNotEmpty()) sqs.send { it.queue(props.cover.queue).payload(json.writeValueAsString(CoverMessage(needCover))) }
        log.info("식별 묶음: 세션 {}, {}곡 중 찾음 {} (표지 확인 {}), {}ms", msg.sessionId, msg.keys.size, found.size, needCover.size, (System.nanoTime() - started) / 1_000_000)
    }

    /** 찾으면 곡 id */
    private fun identifyOne(t: Target): Long? {
        // 다른 세션이 그사이 같은 곡을 찾았을 수 있다
        val cached = repo.findAlias(t.key)?.takeIf { it.isFresh(Instant.now().minus(props.identify.notFoundRetry)) }
        if (cached != null) {
            finish(t, cached.trackId)
            return cached.trackId
        }
        val query = RuleIdentifier.query(t.title, t.channel, t.source)
        val trackId = if (query == null) {
            null // 규칙으로 못 푸는 일반 영상 제목 → L2-3 LLM
        } else {
            fetcher.identify(query.artist, query.title)?.let { info -> tx.execute { repo.upsertTrack(info) } }
        }
        tx.executeWithoutResult { repo.saveAlias(t.key, trackId, AliasMethod.RULE, query) }
        finish(t, trackId)
        return trackId
    }

    private fun finish(t: Target, trackId: Long?) {
        tx.executeWithoutResult {
            if (repo.finishTarget(t.sessionId, t.key, TargetStatus.DONE) && trackId != null) {
                repo.linkListens(t, trackId)
                repo.linkSeeds(t, trackId)
            }
        }
    }
}
