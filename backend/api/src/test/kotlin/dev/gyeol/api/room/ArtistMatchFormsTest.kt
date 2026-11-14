package dev.gyeol.api.room

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistMatchFormsTest {
    private fun matches(lastfm: String, mb: String) = ArtistSongsService.matchForms(lastfm).any(ArtistSongsService.matchForms(mb)::contains)

    @Test
    fun `언어판과 feat는 원곡 제목으로도 맞추고, 팬 리믹스는 맞추지 않는다`() {
        assertTrue(matches("Take It -Japanese version-", "Take It"))
        assertTrue(matches("REBEL HEART -Japanese ver.-", "REBEL HEART (Japanese ver.)"))
        assertTrue(matches("UNDEAD - English Version", "UNDEAD"))
        assertTrue(matches("사랑과 전쟁 (Feat. 하하)", "사랑과 전쟁"))
        assertFalse(matches("OMG (BRLLNT Remix)", "OMG"), "리믹스는 원곡으로 맞추지 않음")
        assertFalse(matches("Ditto (Band ver.)", "Ditto"), "언어판이 아닌 판도 그대로")
    }
}
