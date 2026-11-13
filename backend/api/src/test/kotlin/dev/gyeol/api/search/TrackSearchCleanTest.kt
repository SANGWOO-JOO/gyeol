package dev.gyeol.api.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TrackSearchCleanTest {
    private fun t(title: String, artist: String, mbid: String? = null, listeners: Long? = null) = TrackItem(title, artist, mbid, listeners, null)

    @Test
    fun `가수명의 점 뒤 잡음을 자르고 같은 곡은 하나로, mbid 있는 쪽·청취자 많은 쪽을 남기고 순서는 처음 자리`() {
        val out = TrackSearchController.cleanRemote(
            listOf(
                t("Bags", "Clairo • Lossless", listeners = 3),
                t("Juna", "Clairo", listeners = 50),
                t("Bags", "Clairo", mbid = "m1", listeners = 1),
                t("Bags", "Clairo • From Smart Shuffle", listeners = 99),
                t("Juna", "Clairo • Recommended for you", listeners = 80),
            ),
        )
        assertEquals(listOf("Bags" to "Clairo", "Juna" to "Clairo"), out.map { it.title to it.artist })
        assertEquals("m1", out[0].mbid)
        assertEquals(80, out[1].listeners)
    }

    @Test
    fun `점이 붙어 있거나 점만 있는 가수명은 그대로`() {
        assertEquals("M•A•G", TrackSearchController.cleanRemote(listOf(t("곡", "M•A•G"))).single().artist)
        assertEquals("가수 •", TrackSearchController.cleanRemote(listOf(t("곡", "가수 •"))).single().artist)
    }
}
