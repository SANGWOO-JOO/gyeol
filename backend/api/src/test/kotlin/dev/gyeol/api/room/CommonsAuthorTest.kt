package dev.gyeol.api.room

import dev.gyeol.core.identify.CommonsAuthor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** I-44 저작자 표시: Commons 작성자 칸 정리 (실제 저장값 기준) */
class CommonsAuthorTest {
    @Test
    fun `칸 이름·HTML·엔티티·연속 공백을 지우고 이름·주소는 남긴다`() {
        assertEquals("dramatized", CommonsAuthor.clean("Upload:  dramatized"))
        assertEquals("K-POPit 티비텐", CommonsAuthor.clean("Upload:  K-POPit 티비텐"))
        assertEquals("Republic of Korea", CommonsAuthor.clean("Republic of  Korea"))
        assertEquals("Jeon Han", CommonsAuthor.clean("Photo: <a href=\"//x\">Jeon&nbsp;Han</a>"))
        assertEquals("A & B", CommonsAuthor.clean("Photograph: Uploaded by: A &amp; B"))
        assertEquals("Kim", CommonsAuthor.clean("Photographer : Kim"))
        assertEquals("https://www.youtube.com/user/x/about", CommonsAuthor.clean("https://www.youtube.com/user/x/about"))
        assertEquals("Korea.net / Korean Culture and Information Service (Jeon Han)", CommonsAuthor.clean("Korea.net / Korean Culture and Information Service (Jeon Han)"))
        assertEquals("The original uploader was Shuohyun at Chinese Wikipedia.", CommonsAuthor.clean("The original uploader was Shuohyun at Chinese Wikipedia."))
        assertNull(CommonsAuthor.clean("  <span> </span> "))
        assertNull(CommonsAuthor.clean(null))
    }
}
