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
        assertEquals("Kim (kim)", CommonsAuthor.clean("Photo: Kim (https://www.flickr.com/people/kim)"))
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
        assertEquals("x", CommonsAuthor.clean("[https://www.flickr.com/photos/x]"))
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

    @Test
    fun `I-47 점검 - 범위 밖 숫자 엔티티는 빈칸, 예외 없음`() {
        assertEquals("A B", CommonsAuthor.clean("A&#99999999;B"))
        assertEquals("A B", CommonsAuthor.clean("A&#x110000;B"))
    }

    @Test
    fun `I-47 점검 - flickr·instagram·x 프로필 주소는 사용자 이름, 숫자 id만이면 도메인`() {
        assertEquals("jsmith", CommonsAuthor.clean("https://www.flickr.com/photos/jsmith/12345"))
        assertEquals("jsmith", CommonsAuthor.clean("https://flickr.com/people/jsmith"))
        assertEquals("flickr.com", CommonsAuthor.clean("https://www.flickr.com/people/94031922@N06"))
        assertEquals("jsmith", CommonsAuthor.clean("https://www.instagram.com/jsmith/"))
        assertEquals("instagram.com", CommonsAuthor.clean("https://www.instagram.com/p/AbC123/"))
        assertEquals("jsmith", CommonsAuthor.clean("https://x.com/jsmith"))
        assertEquals("jsmith", CommonsAuthor.clean("https://twitter.com/jsmith/status/1"))
        assertEquals("Angela George at sharongraphics", CommonsAuthor.clean("Angela George at https://www.flickr.com/photos/sharongraphics/"))
    }

    @Test
    fun `I-47 점검 - 칸 전체가 긴 괄호면 괄호를 빼지 않고 상한만`() {
        assertEquals("(Photo by Kim Minsu)", CommonsAuthor.clean("(Photo by Kim Minsu)"))
        assertEquals("(Photo by Kim Minsu for the Korea…", CommonsAuthor.clean("(Photo by Kim Minsu for the Korea Culture and Information Service)"))
        assertEquals("(Kim Minsu, Korean Culture and…", CommonsAuthor.clean("(Kim Minsu, Korean Culture and Information Service"))
    }

    @Test
    fun `I-47 점검 - 이름 끝 이니셜은 남기고 http 계열 조각만 지운다`() {
        assertEquals("Park H", CommonsAuthor.clean("Park H"))
        assertEquals("Park h", CommonsAuthor.clean("Park h"))
        assertEquals("Park", CommonsAuthor.clean("Park http:/"))
        assertEquals("Park", CommonsAuthor.clean("Park https:"))
        assertEquals("Park HTTP:/", CommonsAuthor.clean("Park HTTP:/"))
    }

    @Test
    fun `I-47 점검 - 두 번 정리해도 같다 (worker 저장 + api 응답)`() {
        val inputs = listOf(
            "Upload:  dramatized", "Photo: <a href=\"//x\">Jeon&nbsp;Han</a>", "https://www.youtube.com/user/x/about",
            "Korea.net / Korean Culture and Information Service (Jeon Han)", "The original uploader was Shuohyun at Chinese Wikipedia.",
            "TV10 https://www.youtube.com/@_TV10.", "<a href=\"https://x\">www.newsen.com/news</a>",
            "李 承儒, AKB48-Taiwan Clubs (ATC) 社長 ([https://www.facebook.com/MaedaFantomo 李 承儒's facebook page, flickr account link scre",
            "Danny Choo (site owner of www.culturejapan.jp (=dannychoo.com))",
            "Erika_Rodriguez (Kika, a Major staff(owner, regarding to http://about.me/erikarodriguez) of Retornoanime.com, see http:/",
            "Selena Gomez and the Scene.jpg https://www.flickr.com/photos/canoblesfamily/6508513391/ Selena Gomez and the Scene.jpg h",
            "Jeon Han (Korea.net), see also http://x", "Ministry of Culture, Sports and Tourism of the Republic of Korea (Hong Gil-dong)",
            // 끝 괄호 18~20자: 예전에는 두 번째 정리에서 앞부분이 한 단어 더 잘렸다
            "Ministry of Culture, Sports and Tourism of the Republic of Korea (Photographer Kim Lee)",
            "Office of the Vice President of the United States (Official White House)",
            "(Photo by Kim Minsu for the Korea Culture and Information Service)", "(Kim Minsu, Korean Culture and Information Service",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz", "Park H", "https://www.flickr.com/photos/jsmith/1",
        )
        inputs.forEach { x ->
            val once = CommonsAuthor.clean(x)
            assertEquals(once, CommonsAuthor.clean(once), x)
        }
    }
}
