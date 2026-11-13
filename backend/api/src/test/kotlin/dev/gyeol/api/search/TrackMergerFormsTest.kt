package dev.gyeol.api.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackMergerFormsTest {
    @Test
    fun `괄호 양쪽 표기는 둘 다 같은 곡 표기로, 판 표시 괄호는 나누지 않는다`() {
        val f = TrackMerger.titleForms("너의 모든 순간 (Every Moment of You)")
        assertTrue("너의모든순간" in f && "everymomentofyou" in f, f.toString())
        assertEquals(setOf("밤편지inst"), TrackMerger.titleForms("밤편지 (Inst.)"))
        assertEquals(setOf("좋은날live"), TrackMerger.titleForms("좋은 날 (Live)"))
        assertEquals(setOf("lilac"), TrackMerger.titleForms("LILAC"))
    }

    @Test
    fun `한글·로마자로 한 번 나뉜 제목은 양쪽 모두, 섞인 단어나 여러 번 바뀌면 그대로`() {
        assertEquals(setOf("nanchun난춘", "nanchun", "난춘"), TrackMerger.titleForms("NAN CHUN 난춘"))
        assertEquals(setOf("난춘nanchun", "난춘", "nanchun"), TrackMerger.titleForms("난춘 NAN CHUN"))
        assertEquals(setOf("mylove내사랑my"), TrackMerger.titleForms("My Love 내사랑 My"))
        assertEquals(setOf("8282"), TrackMerger.titleForms("8282"))
    }

    @Test
    fun `dedupe - 같은 곡 id, 같은 가수의 겹치는 표기는 처음 것만, 다른 가수는 둔다`() {
        fun t(title: String, artist: String, id: Long? = null, aid: Long? = null) = TrackItem(title, artist, null, null, null, id, null, aid)
        val out = TrackMerger(org.mockito.Mockito.mock(dev.gyeol.core.identify.TrackSearchRepository::class.java)).dedupe(
            listOf(
                t("난춘", "새소년"), t("NAN CHUN 난춘", "새소년"), t("난춘", "다른 가수"),
                t("8282", "DAVICHI", 1, 9), t("8282", "다비치", 1, 9), t("8282 (Live)", "DAVICHI", null, 9),
            ),
        )
        assertEquals(listOf("난춘|새소년", "난춘|다른 가수", "8282|DAVICHI", "8282 (Live)|DAVICHI"), out.map { "${it.title}|${it.artist}" })
    }
}
