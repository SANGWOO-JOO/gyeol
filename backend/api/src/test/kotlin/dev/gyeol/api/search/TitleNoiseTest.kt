package dev.gyeol.api.search

import dev.gyeol.core.identify.TitleNoise
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleNoiseTest {
    @Test
    fun `거른다 - 11-14 실제 Last fm·MusicBrainz에서 본 잡음 (I-36·I-41)`() {
        listOf(
            // I-36
            "Hype Boy 4K 직캠", "Hype Boy' cover by 정유빈 / male ver.", "Ditto (1시간) / 가사 Lyrics", "OMG (1시간) / 가사 Lyrics",
            "NewJeans (뉴진스) 'Zero' Performance Video", "NewJeans (뉴진스) 'OMG' Dance Practice", "Right Now (Jazz Ver.) (NewJeans Fullcam)",
            "어텐션 (NewJeans - Attention), MBC 250129 방송", "일레븐 (IVE - ELEVEN), MBC 211204 방송", "밤편지 팬캠", "좋은 날 (Cover)",
            "Love Dive 교차편집", "Ditto M/V", "Ditto Official Music Video", "OMG 1 hour loop", "Hype Boy fancam",
            // I-41 NewJeans 인기순 59~100위·최신순 상단
            "[MINJI] Find the Gigantic Spotibunini", "[HAERIN] Spoiler on Bunnyland #2", "OPENING VCR", "Stage talk",
            "Cookie+Hype Boy Cover 주은 🍪", "Hype boy (Acoustic Cover)", "OMG | 1theKILLPO | 원더킬포 | 킬포인트 | 퍼포먼스",
            "ASAP (에이셉) @ 아이유의 팔레트🎨", "어도어 전속계약, 오늘 자정 해지", "Ditto (ric3show3r Jersey flip)", "SUPER ARRUNSHAI .✭.・✫・",
            "Our Night is more beautiful than your Day (Instrumental)", "Bubble Gum (Inst.)", "OMG (DJ Amaya's Trance Enthusiasts Bootleg Remix) (remaster)",
            "ETA (Foxela Jersey Club Remix)", "Get Up (City Pop Remix)", "뉴진스 NewJeans \"OMG\" (Future Funk Remix)", "OMG (Areia Kpop Remix)",
            "ETA (trap remix)", "NewJeans 뉴진스 'How Sweet (UK Garage Remix)'", "omg ^flip。",
            "NewJeans (뉴진스) - ASAP (250 Remix) (MMA ver.)", "NewJeans (뉴진스) - Supernatural (Dance Break ver.) (KGMA Remix)", "Ditto (Cut Ver.)", "데모곡",
        ).forEach { assertTrue(TitleNoise.isNoise(it), it) }
    }

    @Test
    fun `남긴다 - 원곡·실제 음원 판·곡 제목일 수 있는 단어`() {
        listOf(
            "Ditto", "Hype Boy", "Super Shy", "ETA", "How Sweet", "Tell me (FRNK Remix)", "Ditto (Band ver.)", "Attention (Band Live)",
            "Cover Me", "Lyrics", "Hour Glass", "4 Seasons", "Love Story (Taylor's Version)", "LOVE DIVE -Japanese version-",
            "1분 1초", "Performance", "Video Games", "Reaction Time Song", "Full Stop", "커피", "Hype Boy (Performance ver.1)",
            "삐빅 (♥beats)", "Supernova Love - Extended Mix", "Intro_ Bunnyland", "Undercover", "Opening Night", "Talk Talk",
        ).forEach { assertFalse(TitleNoise.isNoise(it), it) }
    }

    @Test
    fun `라이브 공연 사이 부분과 영상 제목 같은 가수 이름`() {
        listOf("Opening", "Band Session", "MC 2", "Talk", "ending").forEach { assertTrue(TitleNoise.isLiveSegment(it), it) }
        listOf("Opening Night", "Talk That Talk", "Band of Brothers").forEach { assertFalse(TitleNoise.isLiveSegment(it), it) }
        assertTrue(TitleNoise.isVideoLikeArtist("성시경(Sungsikyung)의 킬링보이스를 라이브로!"))
        assertTrue(TitleNoise.isVideoLikeArtist("NewJeans | 원더킬포"))
        listOf("P!nk", "Clover!", "Panic! at the Disco", "성시경", "이달의 소녀 김립", "Florence + the Machine", "Sung Si Kyung").forEach {
            assertFalse(TitleNoise.isVideoLikeArtist(it), it)
        }
    }

    @Test
    fun `제목 안에 가수 이름이 들어간 영상식 제목, 가수 이름과 같은 곡은 남김`() {
        val nj = listOf("NewJeans", "뉴진스")
        listOf("NewJeans (뉴진스) 'Zero' Official", "Message from NewJeans (KR)", "NewJeans 2nd Debut Anniversary", "New Jeans (뉴진스) - Hurt (SOQI remix)")
            .forEach { assertTrue(TitleNoise.mentionsArtist(it, nj), it) }
        listOf("New Jeans", "Ditto", "Hype Boy").forEach { assertFalse(TitleNoise.mentionsArtist(it, nj), it) }
        assertFalse(TitleNoise.mentionsArtist("IU", listOf("IU")), "짧은 이름(3자 미만)은 보지 않음")
    }
}
