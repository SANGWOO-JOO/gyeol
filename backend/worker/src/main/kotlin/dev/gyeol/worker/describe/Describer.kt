package dev.gyeol.worker.describe

import dev.gyeol.core.knowledge.DescribeTarget
import dev.gyeol.core.knowledge.EntityType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** LLM이 답한 칸 하나: 값과 근거 청크 번호 */
data class Claim(val value: Any?, val evidence: List<Long>)

data class RawDescription(val trackId: Long, val claims: Map<String, Claim>, val summary: Claim)

data class DescribeResult(val items: List<RawDescription>, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

/** 곡 설명 (L3-2). [rag]가 false면 청크 없이 곡·가수 이름만 준다 (RAG 켬/끔 비교용) */
interface TrackDescriber {
    fun describe(targets: List<DescribeTarget>, rag: Boolean, model: String? = null): DescribeResult
}

/** 저장할 칸 하나 */
data class Field(val value: Any?, val evidence: List<Long>, val level: String, val confidence: String)

/**
 * LLM 답을 검증하고 근거 단위·신뢰도를 우리가 정한다 (LLM 주장을 그대로 믿지 않음).
 * - 인용은 그 곡에 실제로 준 청크 번호만 남긴다
 * - 근거 단위는 인용한 청크의 대상 중 가장 가까운 것: 곡·작품 → TRACK(높음), 음반 → RELEASE(보통), 가수 → ARTIST(낮음)
 * - 인용이 없으면 값을 지우고 NONE("근거 부족"). 단 RAG 끔은 근거가 없으므로 값은 두고 NONE으로 표시
 * - 발매 연대(era)는 MusicBrainz 발매일로 직접 채운다
 */
object DescriptorBuilder {
    val FIELDS = listOf("genres", "moods", "tempo", "energy", "vocal")

    fun build(target: DescribeTarget, raw: RawDescription, rag: Boolean): Pair<String?, Map<String, Field>> {
        val byId = target.chunks.associateBy { it.id }
        fun field(c: Claim?): Field {
            val ev = c?.evidence.orEmpty().filter { it in byId }.distinct()
            val empty = c?.value == null || (c.value is List<*> && (c.value as List<*>).isEmpty()) || c.value == "UNKNOWN"
            if (empty) return Field(null, emptyList(), "NONE", "NONE")
            if (ev.isEmpty()) return if (rag) Field(null, emptyList(), "NONE", "NONE") else Field(c!!.value, emptyList(), "NONE", "NONE")
            val nearest = ev.mapNotNull { byId[it]?.entityType }.minBy { rank(it) }
            val (level, conf) = when (nearest) {
                EntityType.TRACK, EntityType.WORK -> "TRACK" to "HIGH"
                EntityType.RELEASE_GROUP -> "RELEASE" to "MEDIUM"
                EntityType.ARTIST -> "ARTIST" to "LOW"
            }
            return Field(c!!.value, ev, level, conf)
        }
        val fields = FIELDS.associateWith { field(raw.claims[it]) } +
            ("era" to (target.releaseDate?.take(4)?.let { Field(it, emptyList(), "TRACK", "HIGH") } ?: Field(null, emptyList(), "NONE", "NONE")))
        val summary = field(raw.summary).value as String?
        return summary to fields
    }

    private fun rank(t: EntityType) = when (t) { EntityType.TRACK -> 0; EntityType.WORK -> 1; EntityType.RELEASE_GROUP -> 2; EntityType.ARTIST -> 3 }

    /** 도구 입력 JSON → 곡별 답 */
    fun parse(input: JsonNode, targets: List<DescribeTarget>): List<RawDescription> {
        val byIndex = input.path("tracks").toList().associateBy { it.path("index").asInt(-1) }
        return targets.mapIndexedNotNull { i, t ->
            val n = byIndex[i] ?: return@mapIndexedNotNull null
            fun claim(name: String): Claim {
                val v = n.path(name)
                val value: Any? = when {
                    v.isArray -> v.toList().map { it.asString() }.filter { it.isNotBlank() }
                    v.isNull || v.isMissingNode -> null
                    else -> v.asString().ifBlank { null }
                }
                return Claim(value, n.path("evidence").path(name).toList().mapNotNull { e -> e.asLong(-1).takeIf { it > 0 } })
            }
            RawDescription(t.trackId, DescriptorBuilder.FIELDS.associateWith(::claim), claim("summary"))
        }
    }

    fun toJson(json: JsonMapper, fields: Map<String, Field>): String = json.writeValueAsString(fields)
}
