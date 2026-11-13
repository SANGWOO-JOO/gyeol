package dev.gyeol.api.search

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleNoiseTest {
    @Test
    fun `직캠·커버·4K·1시간·가사·퍼포먼스·연습·방송 녹화는 거른다 (11-14 실제 Last fm 인기곡에서 본 표기)`() {
        listOf(
            "Hype Boy 4K 직캠", "Hype Boy' cover by 정유빈 / male ver.", "Ditto (1시간) / 가사 Lyrics", "OMG (1시간) / 가사 Lyrics",
            "NewJeans (뉴진스) 'Zero' Performance Video", "NewJeans (뉴진스) 'OMG' Dance Practice", "Right Now (Jazz Ver.) (NewJeans Fullcam)",
            "어텐션 (NewJeans - Attention), MBC 250129 방송", "일레븐 (IVE - ELEVEN), MBC 211204 방송", "밤편지 팬캠", "좋은 날 (Cover)",
            "Love Dive 교차편집", "Ditto M/V", "Ditto Official Music Video", "OMG 1 hour loop", "Hype Boy fancam",
        ).forEach { assertTrue(TitleNoise.isNoise(it), it) }
    }

    @Test
    fun `원곡·실제 음원 판·곡 제목에 들어갈 수 있는 단어는 거르지 않는다`() {
        listOf(
            "Ditto", "Tell me (FRNK Remix)", "Ditto (Band ver.)", "Bubble Gum (Inst.)", "Attention (Band Live)", "Stage talk",
            "Cover Me", "Lyrics", "Hour Glass", "4 Seasons", "Love Story (Taylor's Version)", "LOVE DIVE -Japanese version-",
            "1분 1초", "Performance", "Video Games", "Reaction Time Song", "Full Stop", "커피", "Hype Boy (Performance ver.1)",
        ).forEach { assertFalse(TitleNoise.isNoise(it), it) }
    }
}
