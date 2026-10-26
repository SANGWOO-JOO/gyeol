package dev.gyeol.worker.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** LLM 답 검증 (지어낸 곡 정보 막기)과 가짜 LLM */
class CleanedValidatorTest {
    private val raw = RawTitle(0, "[MV] IU(아이유) _ Through the Night(밤편지)", "1theK (원더케이)")
    private fun c(artist: String?, title: String?, titleAlt: String? = null, artistAlt: String? = null) =
        CleanedTitle(0, artist, artistAlt, title, titleAlt, Version.ORIGINAL)

    @Test
    fun `제목에 실제로 있는 곡명·가수면 통과`() {
        val ok = CleanedValidator.accept(raw, c("아이유", "밤편지", "Through the Night", "IU"))!!
        assertEquals("밤편지", ok.title)
        assertEquals("Through the Night", ok.titleAlt)
        assertEquals("IU", ok.artistAlt)
    }

    @Test
    fun `제목에 없는 곡명은 거절 (지어낸 답)`() {
        assertNull(CleanedValidator.accept(raw, c("아이유", "좋은날")))
    }

    @Test
    fun `가수는 채널 이름에 있어도 된다, 어디에도 없으면 거절`() {
        val official = RawTitle(1, "Love wins all (Official MV)", "IU Official")
        assertEquals("IU", CleanedValidator.accept(official, c("IU", "Love wins all"))!!.artist)
        assertNull(CleanedValidator.accept(official, c("태연", "Love wins all")))
    }

    @Test
    fun `곡명이 틀려도 다른 표기가 제목에 있으면 그 표기를 곡명으로`() {
        val ok = CleanedValidator.accept(raw, c("아이유", "Through The Night!!", titleAlt = "밤편지"))!!
        assertEquals("Through The Night!!", ok.title) // 정규화하면 제목에 있음
        val swapped = CleanedValidator.accept(raw, c("아이유", "夜の手紙", titleAlt = "밤편지"))!!
        assertEquals("밤편지", swapped.title)
        assertNull(swapped.titleAlt)
    }

    @Test
    fun `가짜 LLM - 흔한 형태만 흉내`() {
        val r = FakeTitleCleaner().clean(
            listOf(
                RawTitle(0, "[MV] 가수A _ 곡1", "1theK"),
                RawTitle(1, "가수B - 곡2 (Official Audio)", "가수B"),
                RawTitle(2, "가수C - 곡3 LIVE", "KBS"),
                RawTitle(3, "새벽에 듣기 좋은 노래 모음 playlist", "누군가"),
                RawTitle(4, "브이로그", "어떤 채널"),
            ),
        ).items
        assertEquals(listOf("가수A" to "곡1", "가수B" to "곡2"), r.take(2).map { it.artist to it.title })
        assertEquals(listOf(Version.ORIGINAL, Version.ORIGINAL, Version.LIVE, Version.NOT_SONG, Version.NOT_SONG), r.map { it.version })
    }
}
