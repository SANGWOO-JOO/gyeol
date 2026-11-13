package dev.gyeol.core.knowledge

/**
 * 화면에 보이는 글에서 대괄호 숫자를 지운다 (I-34).
 * - 근거 조각: 위키백과 각주 "[1][2]"가 Last.fm 소개글에 그대로 따라온다
 * - 가수 설명(LLM): 근거 번호를 evidence 칸과 별도로 본문 끝에 "[564][565]"처럼 적는 경우가 있다(번호는 evidence에 이미 있음)
 */
object Citations {
    /** 위키 각주: 1~3자리 숫자 [12]. 각주 앞 공백까지 */
    private val FOOTNOTE = Regex("""\s*\[\d{1,3}]""")
    /** LLM이 적은 근거 번호: 자리 수 제한 없음 */
    private val ANY_NUMBER = Regex("""\s*\[\d+]""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([.,!?;:)])""")

    fun stripFootnotes(text: String): String = tidy(text.replace(FOOTNOTE, ""))

    fun stripEvidenceNumbers(text: String): String = tidy(text.replace(ANY_NUMBER, ""))

    private fun tidy(s: String) = s.replace(SPACE_BEFORE_PUNCT, "$1").replace(Regex("""[ \t]{2,}"""), " ").trim()
}
