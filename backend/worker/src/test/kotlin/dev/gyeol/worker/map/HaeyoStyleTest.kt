package dev.gyeol.worker.map

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HaeyoStyleTest {
    private fun e(w: String) = HaeyoStyle.ending(w)

    @Test
    fun `있다·과거·명사+이다·받침 없는 명사`() {
        assertEquals("펼치고 있어요", e("펼치고 있"))
        assertEquals("구축했어요", e("구축했"))
        assertEquals("창조해왔어요", e("창조해왔"))
        assertEquals("선보였어요", e("선보였"))
        assertEquals("특징이에요", e("특징이"))
        assertEquals("싱어송라이터예요", e("싱어송라이터"))
        assertEquals("가수예요", e("가수"))
        assertEquals("포크예요", e("포크"))
        assertEquals("프로듀서예요", e("프로듀서이"), "받침 없는 명사 + 이다 → 예요")
        assertEquals("B이에요", e("B이"), "한글 아닌 앞 글자는 이에요")
        assertNull(e("두었습니"), "이미 존댓말(습니다)은 손으로")
    }

    @Test
    fun `현재형 ㄴ다·는다 활용`() {
        assertEquals("보여요", e("보인"))
        assertEquals("추구해요", e("추구한"))
        assertEquals("보여줘요", e("보여준"))
        assertEquals("만들어내요", e("만들어낸"))
        assertEquals("이어가요", e("이어간"))
        assertEquals("받아요", e("받는"))
        assertEquals("담아요", e("담는"))
        assertEquals("평가돼요", e("평가된"))
        assertNull(e("DJ"), "한글이 아닌 끝말은 바꾸지 않는다 (손으로)")
    }

    @Test
    fun `ㄹ 탈락·ㅂ 불규칙·하다 형용사·불규칙`() {
        assertEquals("만들어요", e("만든"))
        assertEquals("넘나들어요", e("넘나든"))
        assertEquals("이끌어요", e("이끈"))
        assertEquals("다채로워요", e("다채롭"))
        assertEquals("다양해요", e("다양하"))
        assertEquals("깊어요", e("깊"))
        assertEquals("달라요", e("다르"))
        assertEquals("아니에요", e("아니"))
        assertEquals("아울러요", e("아우른"))
        assertEquals("불러요", e("부른"))
        assertEquals("빨라요", e("빠르"))
        assertEquals("채워요", e("채운"), "한 글자 ㄹ 어간(울)과 헷갈리지 않는다")
        assertEquals("들어요", e("든"))
    }

    @Test
    fun `설명 전체 - 끝 문장만 바꾸고 못 바꾼 문장은 그대로 목록에`() {
        val r = HaeyoStyle.convert("록과 팝을 결합한 밴드다. 강렬한 사운드가 특징이며, 무대에서 에너지를 보여준다. 감성을 담은 곡을 노래했다.")
        assertEquals("록과 팝을 결합한 밴드예요. 강렬한 사운드가 특징이며, 무대에서 에너지를 보여줘요. 감성을 담은 곡을 노래했어요.", r.text)
        assertEquals(3, r.converted)
        assertEquals(emptyList(), r.unchanged)
        assertNull(HaeyoStyle.sentence("다."), "끝말이 없으면 바꾸지 않는다")
        assertEquals("이미 해요체예요.", HaeyoStyle.convert("이미 해요체예요.").text)
    }
}
