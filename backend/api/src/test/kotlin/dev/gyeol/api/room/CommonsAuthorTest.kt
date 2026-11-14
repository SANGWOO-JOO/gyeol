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
        assertEquals("Korea.net / Korean Culture… (Jeon Han)", CommonsAuthor.clean("Korea.net / Korean Culture and Information Service (Jeon Han)"))
        assertEquals("The original uploader was Shuohyun at…", CommonsAuthor.clean("The original uploader was Shuohyun at Chinese Wikipedia."))
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

    @Test
    fun `I-47 긴 설명형 - 위키 링크·잘린 주소 조각·see·긴 괄호를 지우고 40자 상한`() {
        assertEquals("李 承儒, AKB48-Taiwan Clubs (ATC) 社長",
            CommonsAuthor.clean("李 承儒, AKB48-Taiwan Clubs (ATC) 社長 ([https://www.facebook.com/MaedaFantomo 李 承儒's facebook page, flickr account link scre"))
        assertEquals("Danny Choo", CommonsAuthor.clean("Danny Choo (site owner of www.culturejapan.jp (=dannychoo.com))"))
        assertEquals("Erika_Rodriguez",
            CommonsAuthor.clean("Erika_Rodriguez (Kika, a Major staff(owner, regarding to http://about.me/erikarodriguez) of Retornoanime.com, see http:/"))
        assertEquals("Selena Gomez and the Scene.jpg…",
            CommonsAuthor.clean("Selena Gomez and the Scene.jpg https://www.flickr.com/photos/canoblesfamily/6508513391/ Selena Gomez and the Scene.jpg h"))
        assertEquals("Kim Lee", CommonsAuthor.clean("[[User:Kim|Kim Lee]]"))
        assertEquals("flickr.com", CommonsAuthor.clean("[https://www.flickr.com/photos/x]"))
        assertEquals("Jeon Han (Korea.net)", CommonsAuthor.clean("Jeon Han (Korea.net), see also http://x"))
        assertEquals("Park", CommonsAuthor.clean("Park http:/"))
        assertEquals("Lee See-hoon", CommonsAuthor.clean("Lee See-hoon"))
    }

    @Test
    fun `40자를 넘을 때 끝의 짧은 괄호(촬영자 이름)는 남기고 앞부분만 줄인다`() {
        assertEquals("Korea.net / Korean Culture… (Jeon Han)",
            CommonsAuthor.clean("Korea.net / Korean Culture and Information Service (Jeon Han)"))
        assertEquals("Cheongwadae / Blue House (Kim Yong-wi)", CommonsAuthor.clean("Cheongwadae / Blue House (Kim Yong-wi)"))
        val r = CommonsAuthor.clean("Ministry of Culture, Sports and Tourism of the Republic of Korea (Hong Gil-dong)")!!
        assert(r.endsWith("(Hong Gil-dong)") && r.contains("…") && r.codePointCount(0, r.length) <= 60) { r }
        assertEquals("The original uploader was Shuohyun at…", CommonsAuthor.clean("The original uploader was Shuohyun at Chinese Wikipedia."))
    }
}
