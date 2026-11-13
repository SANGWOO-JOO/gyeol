package dev.gyeol.api.search

import dev.gyeol.core.knowledge.Citations
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CitationsTest {
    @Test
    fun `가수 설명 본문의 근거 번호는 자리 수와 상관없이 지운다`() {
        assertEquals("록 음악을 선보인다.", Citations.stripEvidenceNumbers("록 음악을 선보인다. [653][654][655]"))
        assertEquals("사랑받았다.", Citations.stripEvidenceNumbers("사랑받았다.[744][746][748]"))
        assertEquals("근거 없음", Citations.stripEvidenceNumbers("근거 없음"))
    }

    @Test
    fun `근거 조각의 위키 각주는 1~3자리만 지우고 문장부호 앞 공백을 정리한다`() {
        assertEquals("Best Female Vocalist in 2005. Lee hosted", Citations.stripFootnotes("Best Female Vocalist in 2005.[1] Lee hosted"))
        assertEquals("(born October 14, 1962) is a South Korean", Citations.stripFootnotes("(born October 14, 1962)[1][2] is a South Korean"))
        assertEquals("born June 15, 1993), better known", Citations.stripFootnotes("born June 15, 1993),[1] better known"))
        assertEquals("ID [12345] stays", Citations.stripFootnotes("ID [12345] stays"), "4자리 이상은 각주가 아님")
    }
}
