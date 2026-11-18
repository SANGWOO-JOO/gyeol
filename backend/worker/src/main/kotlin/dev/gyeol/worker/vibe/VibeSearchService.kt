package dev.gyeol.worker.vibe

import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.core.room.ArtistEvidence
import dev.gyeol.core.room.CodeMatchRepository
import dev.gyeol.core.room.VibeHit
import dev.gyeol.core.room.VibePick
import dev.gyeol.core.room.VibeSearchRepository
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.BedrockTool
import dev.gyeol.worker.describe.EmbedClient
import dev.gyeol.worker.map.DescriptionGuard
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class VibeSearchMessage(val searchId: Long)

/** LLM이 낸 그대로 (검증 전) */
data class RawVibePick(val artistId: Long, val reason: String?, val evidence: List<Long>)
data class VibeLlmResult(val picks: List<RawVibePick>, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

interface VibeSearchLlm {
    /** [candidates]: 검색 top-k 가수 설명과 근거 번호 (검색 순서) */
    fun pick(query: String, candidates: List<ArtistEvidence>): VibeLlmResult
}

/** 가짜(LLM 없음): 아무도 고르지 않는다 → 검증이 검색 상위 가수를 이유 없이 카드로 채운다 (8절 "검색 결과만") */
class FakeVibeSearchLlm : VibeSearchLlm {
    override fun pick(query: String, candidates: List<ArtistEvidence>) = VibeLlmResult(emptyList(), "fake", 0, 0, 0)
}

/** 질문 한 문장 → 벡터 (테스트에서 바꿔 끼운다) */
fun interface QueryEmbedder {
    fun embed(text: String, prefix: String): FloatArray
}

/** Bedrock Haiku, 도구 스키마로 받는다 (코드 맞추기·가수 설명과 같은 방식, temperature는 BedrockTool 기본) */
class BedrockVibeSearchLlm(private val llm: WorkerProperties.Llm, private val maxPicks: Int, json: JsonMapper) : VibeSearchLlm {
    private val tool = BedrockTool(llm.region, json)

    override fun pick(query: String, candidates: List<ArtistEvidence>): VibeLlmResult {
        val user = buildString {
            appendLine("질문: $query")
            appendLine("후보 가수 (가수 번호, 이름, 음악 설명, 줄 끝 [번호]는 근거 자료 번호):")
            candidates.forEach { a -> appendLine("- 가수 ${a.artistId} · ${a.name}: ${a.description.take(400)} ${a.evidence.joinToString(" ") { "[$it]" }}") }
        }
        val r = tool.call(llm.model, SYSTEM, user, "record_picks", SCHEMA, 800)
        return VibeLlmResult(parsePicks(r.input), r.model, r.inputTokens, r.outputTokens, r.latencyMs)
    }

    private val SYSTEM = """
        너는 음악 방의 "분위기로 가수 찾기"를 돕는다. 사용자의 질문에 가장 잘 맞는 가수를 후보 안에서만 3~${maxPicks}명 골라, 잘 맞는 순서로 적는다.
        가수마다 질문과 닿는 이유를 한국어 한두 문장(해요체)으로 쓴다. 후보 설명에 있는 장르·분위기·소리만 쓰고, 설명에 없는 내용은 추측하지 않는다.
        나라, 국적, 도시, 언어, 출신지는 언급하지 않는다. 다른 가수 이름은 쓰지 않는다.
        artistId에는 후보의 가수 번호를, evidence에는 그 이유의 근거가 된 설명 줄 끝의 [번호]를 적는다.
        질문에 맞는 근거가 없는 가수는 고르지 않는다. 사과하거나 쓸 수 없는 이유를 적지 않는다.
        질문이 음악 분위기와 관계없거나 후보 중 맞는 가수가 없으면 picks를 빈 배열로 둔다.
    """.trimIndent()

    companion object {
        /** SDK Document를 트리로 바꾸면 숫자가 문자열("1700")로 온다. 숫자·숫자 문자열만 받고 나머지는 버린다 (I-83) */
        private fun JsonNode.longOrNull(): Long? = when {
            isNumber -> asLong()
            isString -> asString().trim().toLongOrNull()
            else -> null
        }

        fun parsePicks(input: JsonNode): List<RawVibePick> = input.path("picks").toList().mapNotNull { p ->
            val id = p.path("artistId").longOrNull() ?: return@mapNotNull null
            RawVibePick(id, p.path("reason").asString("").ifEmpty { null }, p.path("evidence").toList().mapNotNull { it.longOrNull() })
        }

        private val SCHEMA = mapOf(
            "type" to "object", "required" to listOf("picks"),
            "properties" to mapOf(
                "picks" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object", "required" to listOf("artistId", "reason", "evidence"),
                        "properties" to mapOf(
                            "artistId" to mapOf("type" to "integer"),
                            "reason" to mapOf("type" to "string"),
                            "evidence" to mapOf("type" to "array", "items" to mapOf("type" to "integer")),
                        ),
                    ),
                ),
            ),
        )
    }
}

/**
 * 6절 출력 검증 (LLM 없이 단위 테스트):
 * - 검색 top-k 밖 가수, 중복은 버리고 [maxPicks]명까지
 * - 근거 번호는 프롬프트에 준 것만. 남은 번호가 0개거나 이유가 비었거나 거절문이면 이유 없이 카드만
 * - [minPicks]명보다 적으면 검색 순서대로 이유 없는 카드로 채운다
 */
object VibeGuard {
    const val MAX_REASON = 300

    fun validate(raw: List<RawVibePick>, hits: List<VibeHit>, allowedChunks: Set<Long>, minPicks: Int, maxPicks: Int): List<VibePick> {
        val hitIds = hits.map { it.artistId }.toSet()
        val picked = raw.filter { it.artistId in hitIds }.distinctBy { it.artistId }.take(maxPicks).map { p ->
            val evidence = p.evidence.filter { it in allowedChunks }.distinct()
            val reason = p.reason?.trim()?.take(MAX_REASON)?.takeIf { it.isNotEmpty() && !DescriptionGuard.refusal(it) && evidence.isNotEmpty() }
            if (reason == null) VibePick(p.artistId, null) else VibePick(p.artistId, reason, evidence)
        }
        val fill = hits.map { it.artistId }.filter { id -> picked.none { it.artistId == id } }
            .take((minPicks - picked.size).coerceAtLeast(0)).map { VibePick(it, null) }
        return picked + fill
    }
}

@Configuration
class VibeSearchConfig {
    @Bean
    fun vibeSearchLlm(props: WorkerProperties, json: JsonMapper): VibeSearchLlm =
        if (props.llm.mode == "bedrock") BedrockVibeSearchLlm(props.llm, props.vibeSearch.maxPicks, json) else FakeVibeSearchLlm()

    @Bean
    fun queryEmbedder(embed: EmbedClient): QueryEmbedder = QueryEmbedder { text, prefix -> embed.embed(listOf(text), prefix).second.single() }
}

/**
 * 분위기로 가수 찾기 (vibe-search 큐, docs/todo/vibe-search-rag.md 3절):
 * 질문 임베딩 → 가수 벡터 top-k (설명·근거 있는 가수만) → 설명·근거 번호를 넣어 LLM이 고르고 이유 → 출력 검증 → DONE.
 * top-1 거리가 max-distance보다 멀거나 LLM이 아무도 고르지 않으면 picks 없이 DONE (notFound). LLM이 실패하면 이유 없이 검색 상위 카드만.
 * 임베딩·DB 실패는 예외 → 큐가 다시 시도, 실패 큐에서 FAILED
 */
@Service
class VibeSearchService(
    private val searches: VibeSearchRepository,
    private val evidence: CodeMatchRepository,
    private val embedder: QueryEmbedder,
    private val llm: VibeSearchLlm,
    private val llmCalls: LlmCallRepository,
    private val json: JsonMapper,
    private val props: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val p get() = props.vibeSearch

    fun process(searchId: Long) {
        val row = searches.get(searchId) ?: return
        if (row.status != "PENDING") return
        val vector = embedder.embed(row.query, p.queryPrefix)
        val hits = searches.search(vector, p.vectorColumn, p.topK, row.mapVersion)
        val hitsJson = json.writeValueAsString(hits)
        if (hits.isEmpty() || hits.first().d > p.maxDistance) {
            searches.finish(searchId, "DONE", hitsJson, "[]", emptyList())
            log.info("분위기 찾기 {}: 가까운 가수 없음 (top-1 거리 {})", searchId, hits.firstOrNull()?.d)
            return
        }
        val byId = evidence.artistEvidence(hits.map { it.artistId }).associateBy { it.artistId }
        val candidates = hits.mapNotNull { byId[it.artistId] }
        val result = runCatching { llm.pick(row.query, candidates) }
            .onFailure { log.warn("분위기 찾기 {}: LLM 실패, 이유 없이 카드만", searchId, it) }.getOrNull()
        if (result != null && result.model != "fake") {
            llmCalls.record("VIBE_SEARCH", LlmUsage(result.model, 1, result.inputTokens, result.outputTokens, result.latencyMs))
        }
        // B안(11-18): 실제 LLM이 아무도 고르지 않으면 "맞는 가수 없음"으로 본다 (거리 기준은 느슨하게, 판단은 LLM)
        if (result != null && result.model != "fake" && result.picks.isEmpty()) {
            searches.finish(searchId, "DONE", hitsJson, "[]", emptyList())
            log.info("분위기 찾기 {}: LLM이 맞는 가수 없음으로 판단 (top-1 거리 {})", searchId, hits.first().d)
            return
        }
        val allowed = candidates.flatMap { it.evidence }.toSet()
        val picks = VibeGuard.validate(result?.picks.orEmpty(), hits, allowed, p.minPicks, p.maxPicks)
        val used = picks.flatMap { it.evidence }.distinct()
        searches.finish(searchId, "DONE", hitsJson, json.writeValueAsString(picks), used)
        log.info("분위기 찾기 {}: 검색 {}명, 고름 {}명 (이유 {}명, LLM 원본 {}명)", searchId, hits.size, picks.size, picks.count { it.reason != null }, result?.picks?.size ?: 0)
    }

    /** 실패 큐: FAILED (화면이 기다리다 멈추지 않게) */
    fun fail(searchId: Long) {
        searches.finish(searchId, "FAILED", null, null, emptyList())
        log.warn("분위기 찾기 {} 실패 큐 → FAILED", searchId)
    }
}

/** vibe-search 큐 (WORKER_ROLE에 room이 있을 때만, 코드 맞추기와 같은 Deployment) */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('room')")
class VibeSearchListener(private val service: VibeSearchService, private val json: JsonMapper) {
    @SqsListener("\${gyeol.worker.vibe-search.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String) = service.process(json.readValue(body, VibeSearchMessage::class.java).searchId)

    @SqsListener("\${gyeol.worker.vibe-search.dlq}", maxConcurrentMessages = "1", maxMessagesPerPoll = "1")
    fun onDeadLetter(body: String) = service.fail(json.readValue(body, VibeSearchMessage::class.java).searchId)
}
