package dev.gyeol.worker.identify

import dev.gyeol.core.identify.AliasMethod
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.Query
import dev.gyeol.core.identify.RuleIdentifier
import dev.gyeol.core.identify.Target
import dev.gyeol.core.identify.TargetStatus
import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.llm.CleanedValidator
import dev.gyeol.worker.llm.RawTitle
import dev.gyeol.worker.llm.TitleCleaner
import dev.gyeol.worker.llm.Version
import io.awspring.cloud.sqs.operations.SqsTemplate
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

/**
 * 식별 묶음 하나를 처리한다. 곡마다: 캐시 다시 확인 → 규칙으로 (가수, 곡) → fetcher(MusicBrainz) → 저장.
 * 규칙으로 못 푸는 일반 영상 제목은 묶음 안에서 한 번에 LLM으로 정리한다 (L2-3a).
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
    private val cleaner: TitleCleaner,
    private val llmCalls: LlmCallRepository,
) {
    private companion object {
        val IDENTIFIABLE = setOf(Version.ORIGINAL, Version.LIVE, Version.REMIX)
    }

    private val log = LoggerFactory.getLogger(javaClass)

    fun process(msg: IdentifyMessage) {
        val started = System.nanoTime()
        val retryBefore = Instant.now().minus(props.identify.notFoundRetry)
        val found = mutableListOf<Long>()
        val forLlm = mutableListOf<Target>()
        for (t in repo.targets(msg.sessionId, msg.keys).filter { it.status == TargetStatus.PENDING }) {
            // 다른 세션이 그사이 같은 곡을 찾았을 수 있다
            val cached = repo.findAlias(t.key)?.takeIf { it.isFresh(retryBefore) }
            if (cached != null) {
                finish(t, cached.trackId); cached.trackId?.let(found::add); continue
            }
            val query = RuleIdentifier.query(t.title, t.channel, t.source)
            if (query == null) { forLlm += t; continue } // 일반 영상 제목 → LLM (L2-3a)
            val trackId = fetcher.identify(query.artist, query.title)?.let { info -> tx.execute { repo.upsertTrack(info) } }
            tx.executeWithoutResult { repo.saveAlias(t.key, trackId, AliasMethod.RULE, query, reason = if (trackId == null) "NOT_IN_MUSICBRAINZ" else null) }
            finish(t, trackId); trackId?.let(found::add)
        }
        if (forLlm.isNotEmpty()) found += identifyWithLlm(forLlm)

        // 표지를 아직 확인하지 않은 곡은 표지 큐로 (식별과 따로, 늦게 붙어도 곡 이름은 먼저 보인다)
        val needCover = found.distinct().filter { repo.coverTarget(it)?.checkedAt == null }
        if (needCover.isNotEmpty()) sqs.send { it.queue(props.cover.queue).payload(json.writeValueAsString(CoverMessage(needCover))) }
        log.info("식별 묶음: 세션 {}, {}곡 중 찾음 {} (LLM {}곡, 표지 확인 {}), {}ms", msg.sessionId, msg.keys.size, found.size, forLlm.size, needCover.size, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * 규칙으로 못 푼 제목을 LLM으로 한 번에 정리하고 검증한 뒤 MusicBrainz로 식별한다.
     * ORIGINAL·LIVE·REMIX는 원곡으로 식별(취향에서는 같은 곡), COVER·MEDLEY·NOT_SONG·INSTRUMENTAL은 식별하지 않는다.
     */
    private fun identifyWithLlm(targets: List<Target>): List<Long> {
        val raws = targets.mapIndexed { i, t -> RawTitle(i, t.title, t.channel) }
        val result = cleaner.clean(raws)
        if (result.model != "fake") llmCalls.record("TITLE_CLEANUP", LlmUsage(result.model, raws.size, result.inputTokens, result.outputTokens, result.latencyMs))
        val byIndex = result.items.associateBy { it.index }
        return targets.mapIndexedNotNull { i, t ->
            val answer = byIndex[i]
            // 식별할 종류일 때만 검증한다 (곡이 아닌 영상은 곡명이 비어 있는 게 정상)
            val cleaned = answer?.takeIf { it.version in IDENTIFIABLE }?.let { CleanedValidator.accept(raws[i], it) }
            val (trackId, query, reason) = when {
                answer != null && answer.version !in IDENTIFIABLE -> Triple(null, null, answer.version.name)
                cleaned == null -> Triple(null, null, "INVALID")
                else -> {
                    // 표기 조합을 차례로 (가수·곡, 다른 가수 표기, 다른 곡 표기). 찾으면 멈춘다
                    val tries = listOf(cleaned.artist to cleaned.title, cleaned.artistAlt to cleaned.title, cleaned.artist to cleaned.titleAlt)
                        .filter { (a, b) -> !a.isNullOrBlank() && !b.isNullOrBlank() }.distinct()
                    var hit: Pair<Long, Query>? = null
                    for ((a, b) in tries) {
                        val info = fetcher.identify(a!!, b!!) ?: continue
                        hit = tx.execute { repo.upsertTrack(info) }!! to Query(a, b, byChannelName = false)
                        break
                    }
                    Triple(hit?.first, hit?.second ?: tries.firstOrNull()?.let { Query(it.first!!, it.second!!, false) }, if (hit == null) "NOT_IN_MUSICBRAINZ" else null)
                }
            }
            tx.executeWithoutResult { repo.saveAlias(t.key, trackId, AliasMethod.LLM, query, titleAlt = cleaned?.titleAlt, reason = reason) }
            finish(t, trackId)
            trackId
        }
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
