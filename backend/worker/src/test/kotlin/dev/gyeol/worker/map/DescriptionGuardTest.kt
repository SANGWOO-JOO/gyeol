package dev.gyeol.worker.map

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DescriptionGuardTest {
    @Test
    fun `음악 내용이 있는 설명은 그대로 둔다`() {
        val ok = "J-pop과 애니메이션 음악을 중심으로 활동하는 솔로 프로젝트다."
        assertEquals(ok, DescriptionGuard.usable(ok))
        assertEquals("보컬로이드 프로듀서로 곡을 발표한다.", DescriptionGuard.usable("보컬로이드 프로듀서로 곡을 발표한다."))
    }

    @Test
    fun `거절문은 지운다`() {
        assertNull(DescriptionGuard.usable("근거 자료에 음악 장르, 분위기, 보컬 특징 등에 대한 구체적인 정보가 없어 설명을 작성할 수 없습니다."))
        assertNull(DescriptionGuard.usable("1988년부터 활동한 가수다. 제공된 근거 자료에는 음악 장르 정보가 부족하다."))
        assertNull(DescriptionGuard.usable("죄송하지만 힙합 관련 정보를 찾을 수 없습니다."))
    }

    @Test
    fun `활동 연도만 있는 설명은 지운다`() {
        assertNull(DescriptionGuard.usable("2003년부터 활동을 시작한 가수로, 다양한 음악 활동을 펼쳐온 아티스트입니다."))
        assertNull(DescriptionGuard.usable(null))
    }

    @Test
    fun `해요체 - 거절문은 지우고 음악 설명은 둔다 (11-14 프롬프트 해요체)`() {
        assertNull(DescriptionGuard.usable("근거 자료에 장르 정보가 없어서 설명을 작성할 수 없어요."))
        assertNull(DescriptionGuard.usable("죄송해요, 이 가수의 음악 정보가 부족해요."))
        assertNull(DescriptionGuard.usable("설명을 드릴 수 없어요."))
        val ok = "몽환적인 신스 팝을 들려주는 밴드예요. 부드러운 보컬이 특징이에요."
        assertEquals(ok, DescriptionGuard.usable(ok))
    }
}
