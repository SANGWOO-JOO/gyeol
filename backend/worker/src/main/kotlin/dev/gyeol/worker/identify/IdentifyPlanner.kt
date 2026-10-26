package dev.gyeol.worker.identify

import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.PlayGroup
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.worker.WorkerProperties
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

data class IdentifyMessage(val sessionId: Long, val keys: List<String>)
data class CoverMessage(val trackIds: List<Long>)

/**
 * 청취 기록 저장이 끝난 세션에서 바로 식별할 곡을 정한다.
 * 1. (제목, 채널)을 정규화 키로 묶어 재생 상위 N개 → identify_target
 * 2. 전역 캐시(title_alias)에 이미 있으면 바로 연결하고 DONE
 * 3. 나머지를 묶음으로 identify 큐에 넣는다
 * 여러 번 불려도 결과가 같다 (대상은 이미 있으면 그대로, 캐시 적중은 PENDING일 때만 처리)
 */
@Service
class IdentifyPlanner(
    private val repo: IdentifyRepository,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Plan(val targets: Int, val cacheHits: Int, val queued: Int, val messages: Int)

    fun plan(sessionId: Long): Plan {
        val groups = repo.playGroups(sessionId)
            .groupBy { AliasKey.of(it.title, it.channel) }
            // 대소문자만 다른 같은 키는 재생 수를 합치고, 가장 많이 들은 표기를 대표로
            .map { (key, gs) -> key to gs.maxBy { it.plays }.copy(plays = gs.sumOf { it.plays }) }
            .sortedWith(compareByDescending<Pair<String, PlayGroup>> { it.second.plays }.thenBy { it.first })
            .take(props.identify.topN)
        repo.insertTargets(sessionId, groups)

        val retryBefore = Instant.now().minus(props.identify.notFoundRetry)
        var hits = 0
        val pending = repo.pendingKeys(sessionId)
        val queue = mutableListOf<String>()
        val hitTracks = mutableSetOf<Long>()
        for (t in repo.targets(sessionId, pending)) {
            val alias = repo.findAlias(t.key)
            if (alias != null && alias.isFresh(retryBefore)) {
                tx.executeWithoutResult {
                    if (repo.finishTarget(sessionId, t.key, TargetStatus.DONE)) alias.trackId?.let { repo.linkListens(t, it); repo.linkSeeds(t, it) }
                }
                alias.trackId?.let { hitTracks += it }
                hits++
            } else {
                queue += t.key
            }
        }
        // 캐시로 찾은 곡도 표지를 아직 확인하지 않았으면 표지 큐로 (식별만 끝나고 표지 단계가 없던 곡)
        val needCover = hitTracks.filter { repo.coverTarget(it)?.checkedAt == null }
        needCover.chunked(50).forEach { ids -> sqs.send { it.queue(props.cover.queue).payload(json.writeValueAsString(CoverMessage(ids))) } }

        val batches = queue.chunked(props.identify.batch)
        batches.forEach { keys -> sqs.send { it.queue(props.identify.queue).payload(json.writeValueAsString(IdentifyMessage(sessionId, keys))) } }
        log.info("식별 계획: 세션 {}, 대상 {}곡 (캐시 적중 {}, 큐 {}곡 / 메시지 {}개)", sessionId, groups.size, hits, queue.size, batches.size)
        return Plan(groups.size, hits, queue.size, batches.size)
    }
}
