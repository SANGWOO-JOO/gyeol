package dev.gyeol.worker.codematch

import dev.gyeol.core.llm.LlmCallRepository
import dev.gyeol.core.llm.LlmUsage
import dev.gyeol.core.room.ArtistEvidence
import dev.gyeol.core.room.CodeMatchParts
import dev.gyeol.core.room.CodeMatchRepository
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.BedrockTool
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

data class CodeMatchMessage(val matchId: Long)

/** 이유 재료: 두 사람 이름, 점수, 겹친 구역, 가수 설명(근거 번호) */
data class ReasonInput(val nameA: String, val nameB: String, val score: Int, val sharedGalaxies: List<String>, val artists: List<ArtistEvidence>, val sharedArtists: List<String>)
data class Reason(val text: String?, val evidence: List<Long>, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

interface CodeMatchLlm {
    fun reason(input: ReasonInput): Reason
}

/** 가짜: 첫 가수 설명의 근거 번호를 단 정해진 문장 (재료가 없으면 이유 없음) */
class FakeCodeMatchLlm : CodeMatchLlm {
    override fun reason(input: ReasonInput): Reason {
        val first = input.artists.firstOrNull() ?: return Reason(null, emptyList(), "fake", 0, 0, 0)
        val where = input.sharedGalaxies.firstOrNull()?.let { "$it 쪽에서 " } ?: ""
        return Reason("${input.nameA}와 ${input.nameB}는 ${where}취향이 닿아요.", listOf(first.evidence.first()), "fake", 0, 0, 0)
    }
}

class BedrockCodeMatchLlm(private val llm: WorkerProperties.Llm, json: JsonMapper) : CodeMatchLlm {
    private val tool = BedrockTool(llm.region, json)

    override fun reason(input: ReasonInput): Reason {
        val user = buildString {
            appendLine("두 사람: ${input.nameA}, ${input.nameB} (코드 점수 ${input.score}%)")
            if (input.sharedGalaxies.isNotEmpty()) appendLine("함께 있는 구역: ${input.sharedGalaxies.joinToString(", ")}")
            if (input.sharedArtists.isNotEmpty()) appendLine("둘 다 담은 가수: ${input.sharedArtists.joinToString(", ")}")
            appendLine("가수 설명 (각 줄 끝 [번호]는 근거 자료 번호):")
            input.artists.forEach { a -> appendLine("- ${a.name}: ${a.description.take(400)} ${a.evidence.joinToString(" ") { "[$it]" }}") }
        }
        val r = tool.call(llm.model, SYSTEM, user, "record_reason", SCHEMA, 400)
        val allowed = input.artists.flatMap { it.evidence }.toSet()
        val text = r.input.path("reason").asString("").trim().take(300).ifEmpty { null }
        val evidence = r.input.path("evidence").toList().map { it.asLong() }.filter { it in allowed }.distinct()
        return Reason(text?.takeIf { evidence.isNotEmpty() }, evidence, r.model, r.inputTokens, r.outputTokens, r.latencyMs)
    }

    companion object {
        private val SYSTEM = """
            너는 음악 방의 "코드 맞추기" 결과에 짧은 이유를 쓴다. 두 사람의 음악 취향이 어디서 닿고 어디서 갈리는지 한국어 2~3문장으로 쓴다.
            주어진 구역 이름과 가수 설명에 있는 내용만 쓴다. 성격·연애·관계를 추측하지 않는다. 가수 이름은 써도 된다.
            쓴 내용의 근거가 된 설명 줄의 [번호]를 evidence에 적는다. 근거로 쓸 내용이 없으면 reason을 빈 문자열로 둔다.
        """.trimIndent()
        private val SCHEMA = mapOf(
            "type" to "object", "required" to listOf("reason", "evidence"),
            "properties" to mapOf(
                "reason" to mapOf("type" to "string"),
                "evidence" to mapOf("type" to "array", "items" to mapOf("type" to "integer")),
            ),
        )
    }
}

@Configuration
class CodeMatchConfig {
    @Bean
    fun codeMatchLlm(props: WorkerProperties, json: JsonMapper): CodeMatchLlm =
        if (props.llm.mode == "bedrock") BedrockCodeMatchLlm(props.llm, json) else FakeCodeMatchLlm()
}

/**
 * ⑤ 코드 맞추기 이유 (code-match 큐). 재료는 둘 다 담은 가수의 설명, 없으면 각자 많이 담은 가수 2명씩.
 * 근거 번호를 대지 못한 이유는 버리고 NO_REASON (화면은 점수·항목만)
 */
@Service
class CodeMatchReasonService(
    private val matches: CodeMatchRepository,
    private val rooms: RoomRepository,
    private val llm: CodeMatchLlm,
    private val llmCalls: LlmCallRepository,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun write(matchId: Long) {
        val row = matches.get(matchId) ?: return
        if (row.status != "REASONING") return
        val a = rooms.member(row.memberA) ?: return
        val b = rooms.member(row.memberB) ?: return
        val parts = json.readValue(row.partsJson, CodeMatchParts::class.java)
        val pa = matches.songPoints(a.id)
        val pb = matches.songPoints(b.id)
        val shared = pa.map { it.artistId }.toSet() intersect pb.map { it.artistId }.toSet()
        val pick = shared.ifEmpty { (top(pa) + top(pb)).toSet() }
        val artists = matches.artistEvidence(pick.take(6))
        val r = llm.reason(ReasonInput(a.nickname, b.nickname, row.score, parts.sharedGalaxies.take(5), artists, parts.sharedArtists.take(10)))
        if (r.model != "fake") llmCalls.record("CODE_MATCH", LlmUsage(r.model, 1, r.inputTokens, r.outputTokens, r.latencyMs))
        matches.finish(matchId, r.text, if (r.text == null) emptyList() else r.evidence)
        log.info("코드 맞추기 {} 이유 {} (근거 {}개)", matchId, if (r.text == null) "없음" else "씀", r.evidence.size)
    }

    /** 실패 큐: 이유 없이 끝낸다 (화면이 "이유 쓰는 중"에서 멈추지 않게) */
    fun fail(matchId: Long) {
        matches.finish(matchId, null, emptyList())
        log.warn("코드 맞추기 {} 실패 큐 → NO_REASON", matchId)
    }

    private fun top(points: List<dev.gyeol.core.room.SongPoint>): List<Long> =
        points.groupingBy { it.artistId }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
}

/** code-match 큐 (WORKER_ROLE에 room이 있을 때만) */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('room')")
class CodeMatchListener(private val service: CodeMatchReasonService, private val json: JsonMapper) {
    @SqsListener("\${gyeol.worker.code-match.queue}", maxConcurrentMessages = "2", maxMessagesPerPoll = "2")
    fun onMessage(body: String) = service.write(json.readValue(body, CodeMatchMessage::class.java).matchId)

    @SqsListener("\${gyeol.worker.code-match.dlq}", maxConcurrentMessages = "1", maxMessagesPerPoll = "1")
    fun onDeadLetter(body: String) = service.fail(json.readValue(body, CodeMatchMessage::class.java).matchId)
}
