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
        assertEquals("@x", CommonsAuthor.clean("https://www.youtube.com/user/x/about"))
        assertEquals("Korea.net / Korean Culture and Information Service (Jeon Han)", CommonsAuthor.clean("Korea.net / Korean Culture and Information Service (Jeon Han)"))
        assertEquals("The original uploader was Shuohyun at Chinese Wikipedia.", CommonsAuthor.clean("The original uploader was Shuohyun at Chinese Wikipedia."))
        assertNull(CommonsAuthor.clean("  <span> </span> "))
        assertNull(CommonsAuthor.clean(null))
    }

    @Test
    fun `주소만 오면 표시용 이름으로 줄인다 - YouTube 채널은 @이름, 그 밖은 도메인`() {
        assertEquals("@_TV10", CommonsAuthor.clean("https://www.youtube.com/@_TV10"))
        assertEquals("@kpopit", CommonsAuthor.clean("https://youtube.com/c/kpopit/videos"))
        assertEquals("youtube.com", CommonsAuthor.clean("https://www.youtube.com/channel/UCabc"))
        assertEquals("flickr.com", CommonsAuthor.clean("https://www.flickr.com/photos/12345@N00/"))
        assertEquals("@티비텐", CommonsAuthor.clean("https://www.youtube.com/@%ED%8B%B0%EB%B9%84%ED%85%90"))
        assertEquals("Kim (flickr.com)", CommonsAuthor.clean("Photo: Kim (https://www.flickr.com/people/kim)"))
        assertEquals("TV10 @_TV10.", CommonsAuthor.clean("TV10 https://www.youtube.com/@_TV10."))
        assertEquals("newsen.com", CommonsAuthor.clean("<a href=\"https://x\">www.newsen.com/news</a>"))
    }
}
