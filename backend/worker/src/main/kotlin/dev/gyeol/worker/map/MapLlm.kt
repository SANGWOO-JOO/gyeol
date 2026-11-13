package dev.gyeol.worker.map

import dev.gyeol.core.map.ArtistChunk
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.BedrockTool
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

data class LlmUsageInfo(val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

/** 가수 설명: 근거 조각 번호(id)만 evidence로 */
data class ArtistDescription(val text: String?, val evidence: List<Long>, val usage: LlmUsageInfo)

/** 구역 이름: 소속 가수 설명의 근거 번호를 그대로 인용 */
data class GalaxyName(val name: String?, val reason: String?, val evidence: List<Long>, val usage: LlmUsageInfo)

data class GalaxyMemberText(val description: String, val evidence: List<Long>)

/** 음악 지도용 LLM (가수 설명·구역 이름). gyeol.worker.llm.mode=bedrock이면 실제 호출, 아니면 가짜 */
interface MapLlm {
    fun describeArtist(name: String, chunks: List<ArtistChunk>): ArtistDescription
    fun nameGalaxy(members: List<GalaxyMemberText>): GalaxyName
}

/** 테스트·부하 실험용: 첫 근거 조각 앞부분을 설명으로, 구역은 "구역 N" */
class FakeMapLlm : MapLlm {
    private var n = 0
    override fun describeArtist(name: String, chunks: List<ArtistChunk>) =
        ArtistDescription(chunks.firstOrNull()?.text?.take(120), listOfNotNull(chunks.firstOrNull()?.id), LlmUsageInfo("fake", 0, 0, 0))

    override fun nameGalaxy(members: List<GalaxyMemberText>) =
        GalaxyName("구역 ${++n}", "가짜 이름", members.flatMap { it.evidence }.take(2), LlmUsageInfo("fake", 0, 0, 0))
}

/**
 * Bedrock Haiku. 프롬프트는 11-11 검증(room-plan.md 8절)과 같다: 근거만 쓰고 나라·국적·도시·언어·출신지·가수 이름은 쓰지 않는다
 * (나라로 뭉치는 지도를 줄이려고). 근거 번호를 함께 받아, 준 번호 밖은 버린다.
 */
class BedrockMapLlm(private val props: WorkerProperties.Llm, json: JsonMapper) : MapLlm {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tool = BedrockTool(props.region, json)

    override fun describeArtist(name: String, chunks: List<ArtistChunk>): ArtistDescription {
        val user = "가수: $name\n근거 자료:\n" + chunks.joinToString("\n") { "[${it.id}] (${it.source}, ${it.lang}) ${it.text.take(900)}" }
        val r = tool.call(props.model, DESCRIBE_SYSTEM, user, "record_artist", DESCRIBE_SCHEMA, 600)
        val allowed = chunks.map { it.id }.toSet()
        val text = r.input.path("description").asString("").trim().ifEmpty { null }
        val evidence = r.input.path("evidence").toList().map { it.asLong() }.filter { it in allowed }.distinct()
        log.info("가수 설명: {}, 근거 {}개, 토큰 {}/{}, {}ms", name, evidence.size, r.inputTokens, r.outputTokens, r.latencyMs)
        // 근거 번호를 하나도 대지 못한 설명, 거절문·음악 내용 없는 설명은 쓰지 않는다
        return ArtistDescription(DescriptionGuard.usable(text?.takeIf { evidence.isNotEmpty() }), evidence, LlmUsageInfo(r.model, r.inputTokens, r.outputTokens, r.latencyMs))
    }

    override fun nameGalaxy(members: List<GalaxyMemberText>): GalaxyName {
        val user = "이 구역에 모인 가수들의 음악 설명이다. 각 줄 끝의 [번호]는 그 설명의 근거 자료 번호다.\n" +
            members.joinToString("\n") { m -> "- ${m.description} ${m.evidence.joinToString(" ") { "[$it]" }}" }
        val r = tool.call(props.model, GALAXY_SYSTEM, user, "record_galaxy", GALAXY_SCHEMA, 300)
        val allowed = members.flatMap { it.evidence }.toSet()
        val name = r.input.path("name").asString("").trim().take(20).ifEmpty { null }
        val evidence = r.input.path("evidence").toList().map { it.asLong() }.filter { it in allowed }.distinct()
        log.info("구역 이름: {}, 근거 {}개, 토큰 {}/{}", name, evidence.size, r.inputTokens, r.outputTokens)
        return GalaxyName(name, r.input.path("reason").asString("").ifEmpty { null }, evidence, LlmUsageInfo(r.model, r.inputTokens, r.outputTokens, r.latencyMs))
    }

    companion object {
        private val DESCRIBE_SYSTEM = """
            너는 음악 지식 편집자다. 주어진 근거 자료에 있는 내용만 사용해 가수의 음악을 한국어로 설명한다.
            나라, 국적, 도시, 언어, 출신지는 절대 언급하지 않는다. 가수 이름과 다른 가수 이름도 쓰지 않는다.
            장르와 세부 장르, 분위기, 악기 편성, 템포와 에너지, 보컬 특징, 시대적 감성을 중심으로 3~4문장으로 쓴다.
            근거에 없는 내용은 추측하지 않는다. 설명에 쓴 근거 자료의 [번호]를 evidence에 적는다.
            근거에 장르·분위기·소리에 관한 내용이 없으면 description을 빈 문자열로 둔다. 사과하거나 쓸 수 없는 이유를 적지 않는다.
        """.trimIndent()

        private val GALAXY_SYSTEM = """
            너는 음악 지도 편집자다. 비슷한 가수들이 모인 구역에 붙일 짧은 한국어 이름을 짓는다.
            이름은 2~12자, 장르·분위기·감성을 담는다 (예: 새벽 감성 발라드, 몽환 인디 록, 청량 걸그룹 팝).
            나라·도시·언어·가수 이름은 쓰지 않는다. 주어진 설명에 없는 내용은 쓰지 않는다.
            reason에 한 문장으로 이유를 쓰고, 이름의 근거가 된 설명 줄의 [번호]를 evidence에 적는다.
        """.trimIndent()

        private val ids = mapOf("type" to "array", "items" to mapOf("type" to "integer"))
        private val DESCRIBE_SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object", "required" to listOf("description", "evidence"),
            "properties" to mapOf("description" to mapOf("type" to "string"), "evidence" to ids),
        )
        private val GALAXY_SCHEMA: Map<String, Any?> = mapOf(
            "type" to "object", "required" to listOf("name", "reason", "evidence"),
            "properties" to mapOf("name" to mapOf("type" to "string"), "reason" to mapOf("type" to "string"), "evidence" to ids),
        )
    }
}

/**
 * 지도에 쓸 수 없는 가수 설명을 걸러낸다 (11-13 지도 판 3: 거절문·활동 연도만 있는 설명이 한 구역으로 뭉침).
 * 걸러진 가수는 설명 없이 원문 벡터만으로 자리를 잡는다 ([MapService.mix]).
 */
object DescriptionGuard {
    /** 설명 대신 "근거 자료에 … 정보가 없어 설명할 수 없습니다" 같은 거절문 */
    private val REFUSAL = Regex("설명(을)? ?(작성|드릴|할) 수 없|정보가 (없|부족|제한)|근거 자료에(는)? |죄송")

    /** 음악 내용이 하나라도 있는지 (장르·소리·보컬·분위기 낱말) */
    private val MUSIC = Regex(
        "장르|팝|록|힙합|랩|래퍼|래프|발라드|댄스|일렉|전자|재즈|R&B|알앤비|포크|인디|메탈|펑크|소울|어쿠스틱|사운드|비트|멜로디|기타|피아노|신스|트로트|" +
            "가스펠|컨트리|오케스트|밴드|pop|rock|보컬|프로듀|싱어송|작곡|트랙|감성|서정|에너지|리듬|클래식",
        RegexOption.IGNORE_CASE,
    )

    fun usable(text: String?): String? = text?.takeIf { !REFUSAL.containsMatchIn(it) && MUSIC.containsMatchIn(it) }
}
