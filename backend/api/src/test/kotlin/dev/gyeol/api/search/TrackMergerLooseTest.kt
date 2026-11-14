package dev.gyeol.api.search

import dev.gyeol.core.identify.ArtistNames
import dev.gyeol.core.identify.TrackSearchRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** I-54: 검색 결과의 같은 노래 여러 줄. 가수 조각 묶기와 같은 가수의 넓은 제목 비교 */
class TrackMergerLooseTest {
    private val artists = listOf(
        ArtistNames(7, "윤하", listOf("YOUNHA", "Younha"), 10),
        ArtistNames(1, "태연", listOf("TAEYEON", "Taeyeon"), 10),
        ArtistNames(5, "LUCY", listOf("루시"), 10),
        ArtistNames(10, "Girls' Generation", listOf("소녀시대"), 10),
        ArtistNames(2, "다비치", listOf("DAVICHI", "Davichi"), 10),
    )

    /** 우리 가수 목록에서 이름·별칭(소문자)이 맞는 가수를 돌려주는 가짜 DB */
    private val merger = TrackMerger(
        Mockito.mock(TrackSearchRepository::class.java) { inv ->
            if (inv.method.name == "artistsByNames") {
                @Suppress("UNCHECKED_CAST")
                val wanted = inv.arguments[0] as Collection<String>
                artists.filter { a -> (listOf(a.name) + a.aliases).any { it.lowercase() in wanted } }
            } else {
                Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        },
    )

    private fun resolve(name: String) = merger.resolveArtists(listOf(name)).values.singleOrNull()?.id

    @Test
    fun `가수 조각 - 한글·영문 병기, 군더더기, 글자 체계가 다른 괄호는 우리 가수로 묶는다`() {
        assertEquals(7, resolve("윤하 Younha"))
        assertEquals(1, resolve("Taeyeon 태연"))
        assertEquals(5, resolve("LUCY 루시"))
        assertEquals(1, resolve("TAEYEON Official"))
        assertEquals(2, resolve("다비치 (Davichi)"))
        assertEquals(10, resolve("Girls' Generation 소녀시대"))
    }

    @Test
    fun `가수 조각 - 두 가수와 맞거나 같은 글자 체계 괄호(소속)는 묶지 않는다`() {
        assertNull(resolve("윤하, 태연"))
        assertNull(resolve("김태연 (소녀시대)"))
        assertNull(resolve("모르는 가수"))
        assertFalse("소녀시대" in TrackMerger.artistParts("김태연 (소녀시대)"))
    }

    private fun overlap(a: String, b: String, artist: String) = TrackMerger.looseForms(a, listOf(artist)).any(TrackMerger.looseForms(b, listOf(artist))::contains)

    @Test
    fun `같은 가수의 제목 - 가수 꼬리, 트랙 번호, feat 한영, Original Ver은 같은 곡`() {
        assertTrue(overlap("두사랑 (Feat. 매드클라운) - 다비치", "두사랑", "다비치"))
        assertTrue(overlap("15 연애조건", "연애조건", "YOUNHA"))
        assertTrue(overlap("녹는 중 (Feat. 버벌진트)", "녹는 중 (feat. Verbal Jint)", "DAVICHI"))
        assertTrue(overlap("슬픈 다짐 Original Ver", "슬픈 다짐", "DAVICHI"))
        assertTrue(overlap("슬픈 다짐 (Original Version)", "슬픈 다짐", "DAVICHI"))
        assertTrue(overlap("너랑 나 You & I", "너랑 나", "아이유"), "글자 없는 단어(&)가 끼어도 한글·영문 병기로 나눈다")
    }

    @Test
    fun `같은 가수의 제목 - Inst·Remix·Live·Acoustic·Cover는 다른 녹음`() {
        assertFalse(overlap("밤편지 (Inst.)", "밤편지", "아이유"))
        assertFalse(overlap("슬픈 다짐 Remix Version", "슬픈 다짐", "DAVICHI"))
        assertFalse(overlap("좋은 날 (Live)", "좋은 날", "아이유"))
        assertFalse(overlap("녹는 중 (feat. Verbal Jint) (Live)", "녹는 중", "DAVICHI"))
        assertFalse(overlap("사계 (Acoustic)", "사계", "태연"))
        assertFalse(overlap("사계 (Cover)", "사계", "태연"))
        assertFalse(overlap("7 Rings", "Rings", "가수"), "한 자리 숫자로 시작하는 제목은 트랙 번호로 보지 않는다")
        assertFalse(overlap("너에게 쓰는 편지 Pt. 2", "너에게 쓰는 편지", "MC MONG"), "숫자가 든 병기는 나누지 않는다 (2부는 다른 곡)")
        assertFalse(overlap("시험곡 DB 1", "시험곡 DB 2", "가수"))
        assertTrue(overlap("01 연애조건", "연애조건", "YOUNHA"))
        assertTrue(overlap("3. 연애조건", "연애조건", "YOUNHA"))
    }

    @Test
    fun `dedupe - 같은 가수 변형은 합치고, 같은 제목 다른 가수·리메이크는 둔다`() {
        fun t(title: String, artist: String, aid: Long? = null) = TrackItem(title, artist, null, null, null, null, null, aid)
        val out = merger.dedupe(
            listOf(
                t("두사랑", "다비치", 2), t("두사랑 (Feat. 매드클라운) - 다비치", "다비치", 2),
                t("별", "가수A"), t("별", "가수B"),
                t("Love You Like Crazy", "TAEYEON Official"), t("Love You Like Crazy", "TAEYEON"),
                t("기억을 걷는 시간", "넬"), t("기억을 걷는 시간", "리메이크한 가수"),
            ),
        )
        assertEquals(
            listOf("두사랑|다비치", "별|가수A", "별|가수B", "Love You Like Crazy|TAEYEON Official", "기억을 걷는 시간|넬", "기억을 걷는 시간|리메이크한 가수"),
            out.map { "${it.title}|${it.artist}" },
        )
    }
}
