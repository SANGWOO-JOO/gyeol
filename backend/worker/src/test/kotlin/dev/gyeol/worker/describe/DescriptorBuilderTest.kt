package dev.gyeol.worker.describe

import dev.gyeol.core.knowledge.DescribeTarget
import dev.gyeol.core.knowledge.EntityType
import dev.gyeol.core.knowledge.EvidenceChunk
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 곡 설명 검증 (L3-2): 근거 단위·신뢰도는 인용한 청크로 우리가 정한다 */
class DescriptorBuilderTest {
    private fun chunk(id: Long, type: EntityType) = EvidenceChunk(id, type, "WIKIPEDIA", "ko", "https://x", "text $id")
    private val target = DescribeTarget(1, "밤편지", "아이유", "2017-03-24", listOf(chunk(10, EntityType.WORK), chunk(20, EntityType.RELEASE_GROUP), chunk(30, EntityType.ARTIST)))

    private fun raw(vararg claims: Pair<String, Claim>, summary: Claim = Claim(null, emptyList())) = RawDescription(1, claims.toMap(), summary)

    @Test
    fun `근거 단위는 인용한 청크 중 가장 가까운 대상 - 곡·작품 높음, 음반 보통, 가수 낮음`() {
        val (_, f) = DescriptorBuilder.build(target, raw(
            "genres" to Claim(listOf("발라드"), listOf(30, 10)),
            "moods" to Claim(listOf("잔잔함"), listOf(20)),
            "vocal" to Claim("여성 솔로", listOf(30)),
        ), rag = true)
        assertEquals("TRACK" to "HIGH", f.getValue("genres").level to f.getValue("genres").confidence)
        assertEquals("RELEASE" to "MEDIUM", f.getValue("moods").level to f.getValue("moods").confidence)
        assertEquals("ARTIST" to "LOW", f.getValue("vocal").level to f.getValue("vocal").confidence)
        assertEquals("2017", f.getValue("era").value) // 발매일로 직접
    }

    @Test
    fun `주지 않은 청크 번호는 버리고, 인용이 없으면 근거 부족으로 값을 지운다 (RAG 켬)`() {
        val (summary, f) = DescriptorBuilder.build(target, raw(
            "genres" to Claim(listOf("록"), listOf(999)), // 지어낸 번호
            "tempo" to Claim("SLOW", emptyList()),
            "energy" to Claim("UNKNOWN", listOf(10)),
            summary = Claim("근거 없는 소개", emptyList()),
        ), rag = true)
        assertNull(f.getValue("genres").value)
        assertEquals("NONE", f.getValue("genres").level)
        assertNull(f.getValue("tempo").value)
        assertNull(f.getValue("energy").value) // UNKNOWN은 값 없음
        assertNull(summary)
    }

    @Test
    fun `RAG 끔은 근거가 없으므로 값은 두고 근거 부족으로 표시`() {
        val (summary, f) = DescriptorBuilder.build(target, raw("tempo" to Claim("SLOW", emptyList()), summary = Claim("소개", emptyList())), rag = false)
        assertEquals("SLOW", f.getValue("tempo").value)
        assertEquals("NONE", f.getValue("tempo").confidence)
        assertEquals("소개", summary)
    }

    @Test
    fun `도구 입력을 곡별 답으로 읽는다`() {
        val json = JsonMapper.builder().build()
        val input = json.readTree("""{"tracks":[{"index":0,"genres":["발라드"],"moods":[],"tempo":"SLOW","energy":"LOW","vocal":null,"summary":"소개","evidence":{"genres":[10],"tempo":[10]}}]}""")
        val r = DescriptorBuilder.parse(input, listOf(target)).single()
        assertEquals(Claim(listOf("발라드"), listOf(10L)), r.claims["genres"])
        assertEquals(Claim(emptyList<String>(), emptyList()), r.claims["moods"])
        assertEquals(Claim("SLOW", listOf(10L)), r.claims["tempo"])
        assertNull(r.claims.getValue("vocal").value)
    }
}
