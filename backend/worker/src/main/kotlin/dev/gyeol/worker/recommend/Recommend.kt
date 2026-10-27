package dev.gyeol.worker.recommend

import dev.gyeol.core.identify.AliasKey
import dev.gyeol.core.identify.AliasMethod
import dev.gyeol.core.identify.IdentifyRepository
import dev.gyeol.core.identify.Query
import dev.gyeol.core.identify.SearchText
import dev.gyeol.core.knowledge.DescriptorRepository
import dev.gyeol.core.knowledge.VectorRepository
import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.core.recommend.Candidate
import dev.gyeol.core.recommend.RecRow
import dev.gyeol.core.recommend.RecommendRepository
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.enrich.EnrichService
import dev.gyeol.worker.identify.FetcherClient
import dev.gyeol.worker.identify.retrySoonIfBusy
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.listener.Visibility
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

/** PROFILE: 프로필·후보 만들기, CANDIDATES: 후보 식별·보강, RANK: 점수·재정렬·저장 */
data class RecommendMessage(val type: String, val sessionId: Long, val keys: List<String> = emptyList())

/** 세션 보강이 끝나면 추천을 시작한다 (L3-4) */
@Component
class RecommendTrigger(private val sqs: SqsTemplate, private val json: JsonMapper, private val props: WorkerProperties) {
    fun start(sessionId: Long) = send(RecommendMessage("PROFILE", sessionId))
    fun send(m: RecommendMessage) {
        sqs.send { it.queue(props.recommend.queue).payload(json.writeValueAsString(m)) }
    }
}

@Service
class RecommendService(
    private val repo: RecommendRepository,
    private val identify: IdentifyRepository,
    private val descriptors: DescriptorRepository,
    private val vectors: VectorRepository,
    private val fetcher: FetcherClient,
    // 보강 서비스가 추천 시작 트리거를 들고 있어 순환을 피한다
    @Lazy private val enrich: EnrichService,
    private val reranker: Reranker,
    private val llmCalls: LlmCallRepository,
    private val trigger: RecommendTrigger,
    private val json: JsonMapper,
    private val props: WorkerProperties,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val p get() = props.recommend

    private fun key(title: String, artist: String) = SearchText.norm(title) + "|" + SearchText.norm(artist)

    /** 프로필 벡터·취향 집계, 후보 모으기 (Last.fm 두 경로 + DB 벡터 이웃) */
    fun profile(sessionId: Long) {
        val seeds = repo.seeds(sessionId)
        if (seeds.isEmpty()) return log.info("추천: 세션 {} 들은 곡 없음", sessionId)
        val profile = Scoring.profile(seeds)
        val facets = repo.facets(sessionId, seeds.associate { it.trackId to it.weight })
        repo.saveProfile(sessionId, profile, json.writeValueAsString(facets.mapValues { (_, v) -> v.map { it.first } }), null)

        val heard = repo.heardNames(sessionId).map { key(it.first, it.second) }.toHashSet()
        val seedIds = seeds.map { it.trackId }.toSet()
        val found = LinkedHashMap<String, Candidate>()
        fun add(title: String, artist: String, trackId: Long?, source: String, match: Double) {
            if (title.isBlank() || artist.isBlank()) return
            val k = key(title, artist)
            if (k in heard || trackId in seedIds) return
            val old = found[k]
            found[k] = when {
                old == null -> Candidate(k, title, artist, trackId, source, match, "PENDING")
                else -> old.copy(trackId = old.trackId ?: trackId, source = if (old.source == source) source else "BOTH", lastfmMatch = maxOf(old.lastfmMatch, match))
            }
        }
        // ① 곡 단위 (한국 곡은 비어 있는 경우가 많다)
        seeds.take(10).forEach { s ->
            fetcher.lastfm("similar", mapOf("artist" to s.artist, "track" to s.title, "limit" to 20)).path("items").forEach {
                add(it.path("title").asString(""), it.path("artist").asString(""), null, "LASTFM", it.path("match").asDouble(0.0))
            }
        }
        // ② 가수 단위: 비슷한 가수의 인기곡 (순위가 낮을수록 깎는다)
        seeds.groupBy { it.artist }.entries.sortedByDescending { e -> e.value.sumOf { it.weight } }.take(5).forEach { (artist, _) ->
            fetcher.lastfm("similar-artists", mapOf("artist" to artist, "limit" to 5)).path("items").forEach { a ->
                val name = a.path("artist").asString("")
                val match = a.path("match").asDouble(0.0)
                fetcher.lastfm("top-tracks", mapOf("artist" to name, "limit" to 3)).path("items").forEachIndexed { i, t ->
                    add(t.path("title").asString(""), name, null, "LASTFM", match * (1 - 0.15 * i))
                }
            }
        }
        // ③ DB 벡터 이웃 (이미 보강된 곡)
        if (profile != null) {
            val near = repo.vectorNeighbors(profile, 30, seedIds)
            val names = repo.trackNames(near.map { it.first })
            near.forEach { (id, _) -> names[id]?.let { (t, a) -> add(t, a, id, "VECTOR", 0.0) } }
        }
        // 식별 캐시로 이미 아는 곡은 연결해 둔다
        val resolved = found.values.map { c -> c.trackId?.let { c } ?: identify.findAlias(AliasKey.of(c.title, "${c.artist} - Topic"))?.trackId?.let { c.copy(trackId = it) } ?: c }
            .filter { it.trackId == null || it.trackId !in seedIds }
        // 미리 점수: Last.fm 유사도, 같은 곡이 두 경로에서 나오면 가산 → 상위 N
        val top = resolved.sortedByDescending { it.lastfmMatch + (if (it.source == "BOTH") 0.2 else 0.0) + (if (it.source == "VECTOR") 0.3 else 0.0) }.take(p.candidates)
        val ready = repo.withVector(top.mapNotNull { it.trackId })
        val cands = top.map { if (it.trackId in ready) it.copy(status = "READY") else it }
        repo.replaceCandidates(sessionId, cands)
        val pending = cands.filter { it.status == "PENDING" }.map { it.key }
        log.info("추천 후보: 세션 {}, {}곡 (보강 필요 {}곡), 프로필 벡터 {}", sessionId, cands.size, pending.size, profile != null)
        if (pending.isEmpty()) trigger.send(RecommendMessage("RANK", sessionId))
        else pending.chunked(p.batch).forEach { trigger.send(RecommendMessage("CANDIDATES", sessionId, it)) }
    }

    /** 후보 식별(MusicBrainz) → 보강(지식·설명·벡터) */
    fun candidates(sessionId: Long, keys: List<String>) {
        val byKey = repo.candidates(sessionId, "PENDING").associateBy { it.key }
        val ids = HashMap<String, Long>()
        keys.mapNotNull { byKey[it] }.forEach { c ->
            val aliasKey = AliasKey.of(c.title, "${c.artist} - Topic")
            val cached = identify.findAlias(aliasKey)
            val id = cached?.trackId ?: if (cached != null) null else {
                val info = fetcher.identify(c.artist, c.title)
                val tid = info?.let { tx.execute { _ -> identify.upsertTrack(it) } }
                identify.saveAlias(aliasKey, tid, AliasMethod.RULE, Query(c.artist, c.title, false), reason = if (tid == null) "NOT_IN_MUSICBRAINZ" else null)
                tid
            }
            if (id == null) repo.finishCandidate(sessionId, c.key, null, "FAILED") else ids[c.key] = id
        }
        if (ids.isNotEmpty()) enrich.enrichTracks(ids.values.distinct())
        // 식별된 곡은 벡터가 없어도(근거 부족) 후보로 남긴다: 점수에서 벡터 항만 0
        ids.forEach { (k, id) -> repo.finishCandidate(sessionId, k, id, "READY") }
        afterCandidates(sessionId)
    }

    /** 후보가 다 끝났으면 순위 매기기 (실패 큐에서도 부른다) */
    fun afterCandidates(sessionId: Long) {
        val (done, total) = repo.candidateProgress(sessionId)
        if (total > 0 && done == total) trigger.send(RecommendMessage("RANK", sessionId))
    }

    fun failCandidates(sessionId: Long, keys: List<String>) {
        keys.forEach { repo.finishCandidate(sessionId, it, null, "FAILED") }
        afterCandidates(sessionId)
    }

    /** 재정렬 입력: 점수 순 후보(곡 설명·근거 청크 포함)와 들은 곡, 취향 집계. 평가(RecommendEvalController)도 쓴다 */
    data class RankInput(val ranked: List<Ranked>, val seeds: List<RerankSeed>, val candidates: List<RerankCandidate>, val facets: String)

    fun rankInput(sessionId: Long): RankInput {
        val seeds = repo.seeds(sessionId)
        val profile = repo.profileVector(sessionId)
        val ranked = Scoring.rank(repo.scoringInputs(sessionId, seeds.map { it.trackId }), profile, Triple(p.wVector, p.wLastfm, p.wCredit), p.perArtist, p.candidates)
        val rSeeds = seeds.take(15).mapIndexed { i, s -> RerankSeed(i + 1, s.trackId, s.title, s.artist) }
        val rCands = ranked.mapIndexed { i, r ->
            val t = descriptors.target(r.input.trackId, 3)
            RerankCandidate(i + 1, r.input.trackId, r.input.title, r.input.artist, vectors.descriptorText(r.input.trackId)?.text,
                t?.chunks.orEmpty().map { it.id to it.text }, r.score)
        }
        val facets = repo.facets(sessionId, seeds.associate { it.trackId to it.weight })
            .entries.joinToString("\n") { (k, v) -> "$k: " + v.joinToString(", ") { it.first } }
        return RankInput(ranked, rSeeds, rCands, facets)
    }

    /** 점수 → 가수당 제한 → LLM 재정렬과 이유 → 검증 → 저장 */
    fun rank(sessionId: Long) {
        val (ranked, rSeeds, rCands, facets) = rankInput(sessionId)
        if (ranked.isEmpty()) return log.info("추천: 세션 {} 점수 매길 후보 없음", sessionId)
        val result = reranker.rerank(facets, rSeeds, rCands, p.picks)
        if (result.model != "fake") llmCalls.record("RECOMMEND", LlmUsage(result.model, rCands.size, result.inputTokens, result.outputTokens, result.latencyMs))
        val finals = RerankValidator.validate(result, rSeeds, rCands, p.picks)
        // L3-5: 절반은 이유를 근거 없이 다시 쓴다 (블라인드 비교). 세션마다 같은 곡이 고르도록 세션 id로 섞는다.
        // 근거가 없어 이유를 비운 곡은 비교에서 빼야 두 쪽이 같은 조건이 된다
        val withReason = finals.indices.filter { finals[it].reason != null }
        val noRag = withReason.shuffled(java.util.Random(sessionId)).take(withReason.size / 2).toSet()
        val plain = if (noRag.isEmpty()) null else reranker.reasonsWithoutEvidence(facets, rSeeds, noRag.map { finals[it].candidate })
        if (plain != null && plain.model != "fake") llmCalls.record("RECOMMEND_NORAG", LlmUsage(plain.model, noRag.size, plain.inputTokens, plain.outputTokens, plain.latencyMs))
        val scoreOf = ranked.associate { it.input.trackId to it.score }
        val sources = repo.candidateSources(sessionId)
        tx.executeWithoutResult {
            repo.replaceRecommendations(sessionId, finals.mapIndexed { i, f ->
                val c = f.candidate
                val base = RecRow(i + 1, c.trackId, scoreOf[c.trackId] ?: 0.0, f.reason, f.evidence, f.basedOn, result.model, "RAG", c.index, sources[c.trackId])
                // 근거 없는 이유를 못 받은 곡은 RAG 쪽 그대로 둔다 (비교군에서 빠진다)
                val r = plain?.reasons?.get(c.index)
                if (i in noRag && r != null) base.copy(reason = r, evidence = emptyList(), reasonVariant = "NORAG", model = plain.model) else base
            })
            repo.setSummary(sessionId, result.tasteSummary)
        }
        log.info("추천 저장: 세션 {}, 후보 {}곡 → {}곡 (이유 있음 {}곡), 모델 {}", sessionId, rCands.size, finals.size, finals.count { it.reason != null }, result.model)
    }
}

/** SQS recommend 큐 (WORKER_ROLE에 recommend가 있을 때만) */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('recommend')")
class RecommendListener(private val service: RecommendService, private val json: JsonMapper, private val props: WorkerProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${gyeol.worker.recommend.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String, visibility: Visibility) = retrySoonIfBusy(visibility, props.busyRetry.toSeconds().toInt()) {
        val m = json.readValue(body, RecommendMessage::class.java)
        when (m.type) {
            "PROFILE" -> service.profile(m.sessionId)
            "CANDIDATES" -> service.candidates(m.sessionId, m.keys)
            "RANK" -> service.rank(m.sessionId)
        }
    }

    /** 실패 큐: 후보 묶음은 FAILED로 두고 나머지로 순위를 매긴다 */
    @SqsListener("\${gyeol.worker.recommend.dlq}")
    fun onDeadLetter(body: String) {
        val m = json.readValue(body, RecommendMessage::class.java)
        log.error("recommend 실패 큐: 세션 {}, {}", m.sessionId, m.type)
        if (m.type == "CANDIDATES") service.failCandidates(m.sessionId, m.keys)
    }
}
