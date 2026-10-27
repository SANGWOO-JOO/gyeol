package dev.gyeol.worker.enrich

import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.knowledge.Chunk
import dev.gyeol.core.knowledge.Credit
import dev.gyeol.core.knowledge.DescribeTarget
import dev.gyeol.core.knowledge.DescriptorRepository
import dev.gyeol.core.knowledge.EntityType
import dev.gyeol.core.knowledge.KnowledgeRepository
import dev.gyeol.core.knowledge.TrackRefs
import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.Claim
import dev.gyeol.worker.describe.DescriptorBuilder
import dev.gyeol.worker.describe.EmbedClient
import dev.gyeol.worker.describe.RawDescription
import dev.gyeol.worker.describe.TrackDescriber
import dev.gyeol.worker.identify.FetcherClient
import dev.gyeol.worker.identify.retrySoonIfBusy
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.listener.Visibility
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** PLAN: 세션의 보강 대상을 정해 TRACKS 메시지로 나눈다. TRACKS: 곡들을 보강한다 */
data class EnrichMessage(val type: String, val sessionId: Long, val trackIds: List<Long> = emptyList())

/**
 * 식별이 끝난 세션의 보강을 시작한다 (L3-1). 식별과 MusicBrainz 제한을 함께 쓰므로 식별이 끝난 뒤에만.
 * 여러 번 불려도 된다: 대상은 이미 있으면 그대로, 보강은 대상·출처별 90일 캐시를 본다.
 */
@Component
class EnrichTrigger(private val identify: IdentifyRepository, private val sqs: SqsTemplate, private val json: JsonMapper, private val props: WorkerProperties) {
    fun afterIdentify(sessionId: Long) {
        val p = identify.progress(sessionId)
        if (p.target == 0 || p.done + p.failed < p.target) return
        sqs.send { it.queue(props.enrich.queue).payload(json.writeValueAsString(EnrichMessage("PLAN", sessionId))) }
    }
}

@Service
class EnrichService(
    private val knowledge: KnowledgeRepository,
    private val fetcher: FetcherClient,
    private val sqs: SqsTemplate,
    private val json: JsonMapper,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
    private val descriptors: DescriptorRepository,
    private val describer: TrackDescriber,
    private val llmCalls: LlmCallRepository,
    private val embed: EmbedClient,
    private val recommendTrigger: dev.gyeol.worker.recommend.RecommendTrigger,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun plan(sessionId: Long) {
        val tracks = knowledge.candidateTracks(sessionId, props.enrich.topN)
        knowledge.insertTargets(sessionId, tracks)
        val pending = knowledge.pendingTargets(sessionId)
        pending.chunked(props.enrich.batch).forEach { ids ->
            sqs.send { it.queue(props.enrich.queue).payload(json.writeValueAsString(EnrichMessage("TRACKS", sessionId, ids))) }
        }
        log.info("보강 계획: 세션 {}, 대상 {}곡 (남은 {}곡)", sessionId, tracks.size, pending.size)
    }

    fun enrich(sessionId: Long, trackIds: List<Long>) {
        val started = System.nanoTime()
        val r = enrichTracks(trackIds)
        trackIds.forEach { knowledge.finishTarget(sessionId, it, "DONE") }
        log.info("보강 묶음: 세션 {}, {}곡, 새로 가져온 대상 {}개, 곡 설명 {}곡, 벡터 {}곡, {}ms", sessionId, trackIds.size, r.fetched, r.described, r.embedded, (System.nanoTime() - started) / 1_000_000)
        // 세션 보강이 다 끝났으면 추천 시작 (L3-4)
        val p = knowledge.progress(sessionId)
        if (p.target > 0 && p.done + p.failed == p.target) recommendTrigger.start(sessionId)
    }

    data class EnrichResult(val fetched: Int, val described: Int, val embedded: Int)

    /** 지식 인입 → 곡 설명 → 벡터. 세션 보강과 추천 후보 보강(L3-4)이 함께 쓴다 */
    fun enrichTracks(trackIds: List<Long>): EnrichResult {
        val since = Instant.now().minus(props.enrich.refresh)
        var fetched = 0
        for (id in trackIds) {
            val t = knowledge.trackRefs(id) ?: continue
            fetched += enrichTrack(t, since)
        }
        // 곡 설명 (L3-2): 설명이 없거나 지식이 바뀐 곡만, 한 번에 묶어서
        val toDescribe = trackIds.filter { descriptors.needsDescribe(it) }.mapNotNull { descriptors.target(it) }
        if (toDescribe.isNotEmpty()) describe(toDescribe)
        // 곡 설명 → 벡터 (L3-3). 문장이 바뀐 곡만
        val embedded = embed.embedTracks(trackIds)
        return EnrichResult(fetched, toDescribe.size, embedded)
    }

    /** 곡 하나: 녹음 → 작품들 → 음반 묶음 → 가수. 90일 안에 가져온 대상은 건너뛴다. 새로 가져온 대상 수 */
    private fun enrichTrack(t: TrackRefs, since: Instant): Int {
        var n = 0
        val artistNames = listOfNotNull(t.displayArtist, t.artistName).distinct()
        var works = t.workMbids
        if (!knowledge.isFresh(EntityType.TRACK, t.recordingMbid, since)) {
            // Last.fm 곡 조회는 사용자가 본 이름("아이유", "밤편지")을 먼저 쓴다 (MusicBrainz 이름 "IU"로는 안 나오는 곡이 있음)
            val r = fetcher.knowledge("recording", t.recordingMbid.toString(), artistNames.first(), t.displayTitle ?: t.title)
            works = r.path("workIds").toList().map { UUID.fromString(it.asString()) }
            tx.executeWithoutResult {
                knowledge.saveEntity(EntityType.TRACK, t.recordingMbid, chunks(r))
                knowledge.saveCredits(t.trackId, credits(r))
                knowledge.setWorks(t.trackId, works)
            }
            n++
        }
        for (w in works) {
            if (knowledge.isFresh(EntityType.WORK, w, since)) {
                knowledge.copyWorkCredits(t.trackId, w)
                continue
            }
            val r = fetcher.knowledge("work", w.toString())
            tx.executeWithoutResult {
                knowledge.saveEntity(EntityType.WORK, w, chunks(r))
                knowledge.saveCredits(t.trackId, credits(r))
            }
            n++
        }
        t.releaseGroupMbid?.takeUnless { knowledge.isFresh(EntityType.RELEASE_GROUP, it, since) }?.let { rg ->
            val r = fetcher.knowledge("release-group", rg.toString(), artistNames.last(), t.releaseTitle)
            tx.executeWithoutResult { knowledge.saveEntity(EntityType.RELEASE_GROUP, rg, chunks(r)) }
            n++
        }
        if (!knowledge.isFresh(EntityType.ARTIST, t.artistMbid, since)) {
            val r = fetcher.knowledge("artist", t.artistMbid.toString(), t.artistName)
            tx.executeWithoutResult { knowledge.saveEntity(EntityType.ARTIST, t.artistMbid, chunks(r)) }
            n++
        }
        return n
    }

    /** 곡 설명을 만들고 검증해 저장한다. 근거 청크가 하나도 없는 곡은 LLM을 부르지 않고 "근거 부족"으로 둔다 */
    fun describe(targets: List<DescribeTarget>) {
        val (withEvidence, without) = targets.partition { it.chunks.isNotEmpty() }
        without.forEach { t ->
            val (summary, fields) = DescriptorBuilder.build(t, RawDescription(t.trackId, emptyMap(), Claim(null, emptyList())), rag = true)
            descriptors.save(t.trackId, "ko", summary, DescriptorBuilder.toJson(json, fields), "none", rag = true)
        }
        // Bedrock이 큰 요청(곡 5개면 입력 약 5,400토큰)에 503을 자주 줘서 2곡씩 부른다 (L3-2 측정)
        withEvidence.chunked(DESCRIBE_BATCH).forEach { group ->
            val r = describer.describe(group, rag = true)
            if (r.model != "fake") llmCalls.record("DESCRIBE", LlmUsage(r.model, group.size, r.inputTokens, r.outputTokens, r.latencyMs))
            val byTrack = r.items.associateBy { it.trackId }
            group.forEach { t ->
                val raw = byTrack[t.trackId] ?: RawDescription(t.trackId, emptyMap(), Claim(null, emptyList()))
                val (summary, fields) = DescriptorBuilder.build(t, raw, rag = true)
                descriptors.save(t.trackId, "ko", summary, DescriptorBuilder.toJson(json, fields), r.model, rag = true)
            }
        }
    }

    private companion object {
        const val DESCRIBE_BATCH = 2
    }

    private fun chunks(r: JsonNode) = r.path("chunks").toList().map {
        Chunk(it.path("source").asString(), it.path("lang").asString(), it.path("url").asString(), it.path("license").asString(), it.path("text").asString())
    }

    private fun credits(r: JsonNode) = r.path("credits").toList().map {
        Credit(it.path("role").asString(), it.path("artistMbid").asString("").ifEmpty { null }?.let(UUID::fromString), it.path("name").asString())
    }
}

/** SQS enrich 큐 (WORKER_ROLE에 enrich가 있을 때만). 외부 503은 30초 뒤, 3번 실패하면 실패 큐 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('enrich')")
class EnrichListener(private val service: EnrichService, private val json: JsonMapper, private val props: WorkerProperties) {
    @SqsListener("\${gyeol.worker.enrich.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String, visibility: Visibility) = retrySoonIfBusy(visibility, props.busyRetry.toSeconds().toInt()) {
        val m = json.readValue(body, EnrichMessage::class.java)
        when (m.type) {
            "PLAN" -> service.plan(m.sessionId)
            "TRACKS" -> service.enrich(m.sessionId, m.trackIds)
        }
    }
}
