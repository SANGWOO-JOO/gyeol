package dev.gyeol.worker.guess

import dev.gyeol.worker.llm.CleanedTitle
import dev.gyeol.worker.llm.Version
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistPlacementTest {
    private fun guess(artist: String?, alt: String? = null) = CleanedTitle(0, artist, alt, "곡", null, Version.LIVE)

    @Test
    fun `괄호 안 프로그램 이름에만 있는 이름은 가수로 보지 않는다`() {
        assertFalse(ArtistPlacement.ok("쿨(Cool) - All for you [윤도현의 러브레터]", "방송국", guess("윤도현")))
    }

    @Test
    fun `괄호 밖·채널 이름·맨 앞 괄호 뒤 구분자는 인정한다`() {
        assertTrue(ArtistPlacement.ok("윤하 - 오르트구름 [유희열의 스케치북]", "KBS Kpop", guess("윤하")))
        assertTrue(ArtistPlacement.ok("Rain Song", "YOUNHA OFFICIAL", guess("YOUNHA")))
        assertTrue(ArtistPlacement.ok("[ZARD]-Don't you see! [KOR/Lyrics]", "팬 채널", guess("ZARD")))
        assertTrue(ArtistPlacement.ok("TWICE [트와이스] - What is Love?", "KBS Kpop", guess("트와이스", "TWICE")))
    }

    @Test
    fun `가수가 비어 있으면 인정하지 않는다`() {
        assertFalse(ArtistPlacement.ok("어떤 영상", "채널", guess(null)))
    }
}
