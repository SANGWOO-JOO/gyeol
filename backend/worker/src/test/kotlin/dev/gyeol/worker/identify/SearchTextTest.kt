package dev.gyeol.worker.identify

import dev.gyeol.core.identify.SearchText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** core 검색 문자열 (L2-4) */
class SearchTextTest {

    @Test
    fun `정규화 - 공백·문장부호·대소문자·전각 차이를 없앤다`() {
        assertEquals("아이유밤편지", SearchText.norm("아이유 - 밤편지"))
        assertEquals("tt", SearchText.norm("ＴＴ"))
        assertEquals("뚜두뚜두ddududdudu", SearchText.norm("뚜두뚜두 (DDU-DU DDU-DU)"))
    }

    @Test
    fun `초성 - 음절에서 뽑고, 초성 입력은 그대로, 영문·숫자는 버린다`() {
        assertEquals("ㅇㅇㅇㅂㅍㅈ", SearchText.chosung("아이유 밤편지"))
        assertEquals("ㅂㅍㅈ", SearchText.chosung("ㅂㅍㅈ")) // 호환용 자모 그대로 (NFKC를 거치면 다른 코드가 됨)
        assertEquals("ㅂㄴㄴ", SearchText.chosung("빛나는 IU 2017"))
        assertTrue(SearchText.isChosungQuery("ㅂㅍ ㅈ"))
        assertFalse(SearchText.isChosungQuery("ㅂㅍ지"))
    }

    @Test
    fun `색인 표기 - 곡명·가수·조합 (붙여쓰기 대비)`() {
        val terms = SearchText.terms(listOf("밤편지 (Through the Night)", "밤편지"), listOf("IU", "아이유")).map { it.text to it.kind }.toSet()
        assertTrue("밤편지" to "TITLE" in terms)
        assertTrue("아이유" to "ARTIST" in terms)
        assertTrue("아이유밤편지" to "ARTIST_TITLE" in terms)
        assertTrue("밤편지iu" to "ARTIST_TITLE" in terms)
        assertEquals(listOf("태연", "rian"), SearchText.tokens(" 태연  rian "))
    }
}
