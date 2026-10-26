package dev.gyeol.fetcher.coverart

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 표지 규칙 (docs/l1-design.md 4-3): 그 음반 → 같은 이름의 다른 판 → 없음. 다른 음반 표지로 대신하지 않는다 */
class CoverFinderTest {
    private val json = JsonMapper.builder().build()
    private fun group(vararg releases: Triple<String, String, Boolean>): JsonNode =
        json.valueToTree(mapOf("releases" to releases.map { (id, title, front) -> mapOf("id" to id, "title" to title, "cover-art-archive" to mapOf("front" to front)) }))

    private fun finder(fronts: Set<String>, browse: JsonNode = group()) = CoverFinder({ it in fronts }, { browse })
    private val req = CoverRequest("ynwa-kr", "rg", "You Never Walk Alone")

    @Test
    fun `그 음반에 표지가 있으면 그대로`() {
        val r = finder(setOf("ynwa-kr")).find(req)
        assertEquals("https://coverartarchive.org/release/ynwa-kr/front-250", r.url)
        assertEquals("RELEASE", r.from)
    }

    @Test
    fun `없으면 같은 이름의 다른 판 - 문장부호 차이는 무시`() {
        val browse = group(Triple("wings", "WINGS", true), Triple("ynwa-jp", "You Never Walk Alone.", true))
        val r = finder(setOf("wings", "ynwa-jp"), browse).find(req)
        assertEquals("SAME_TITLE_RELEASE", r.from)
        assertEquals("https://coverartarchive.org/release/ynwa-jp/front-250", r.url)
    }

    @Test
    fun `이름이 다른 음반 표지로는 대신하지 않는다 (봄날 → WINGS 사례)`() {
        val r = finder(setOf("wings"), group(Triple("wings", "WINGS", true))).find(req)
        assertNull(r.url)
    }

    @Test
    fun `음반 정보가 없으면 찾지 않는다`() {
        assertNull(finder(setOf("x")).find(CoverRequest(null, "rg", "t")).url)
    }
}
