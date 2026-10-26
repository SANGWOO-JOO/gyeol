package dev.gyeol.core.identify

import java.text.Normalizer

/** 곡 검색용 문자열 (L2-4). 색인과 검색어에 같은 규칙을 쓴다 */
object SearchText {
    private val STRIP = Regex("""[\s\p{Punct}·•′'"「」『』【】♪…–—~～()（）\[\]]+""")
    private const val CHOSUNG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val JAMO_ONLY = Regex("""^[ㄱ-ㅎ]+$""")

    /** NFKC → 소문자 → 공백·문장부호 제거 ("아이유 - 밤편지" → "아이유밤편지") */
    fun norm(s: String?): String = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFKC).lowercase().replace(STRIP, "")

    /**
     * 한글 음절의 초성만 ("아이유 밤편지" → "ㅇㅇㅇㅂㅍㅈ"). 이미 초성인 글자는 그대로, 그 밖의 글자는 버린다.
     * NFKC를 거치지 않는다: NFKC는 초성 글자(호환용 자모 ㅂ U+3142)를 다른 코드(U+1107)로 바꿔 비교가 틀어진다
     */
    fun chosung(s: String?): String = buildString {
        for (ch in s.orEmpty().lowercase()) {
            val c = ch.code - 0xAC00
            when {
                c in 0..11171 -> append(CHOSUNG[c / 588])
                ch in 'ㄱ'..'ㅎ' -> append(ch)
            }
        }
    }

    /** 검색어가 초성만으로 되어 있는지 ("ㅂㅍㅈ") */
    fun isChosungQuery(q: String): Boolean = JAMO_ONLY.matches(q.replace(Regex("""\s+"""), ""))

    /** 띄어 쓴 단어별 정규화 ("태연 rian" → [태연, rian]) */
    fun tokens(q: String): List<String> = q.trim().split(Regex("""\s+""")).map(::norm).filter { it.isNotEmpty() }

    data class Term(val text: String, val kind: String)

    /** 한 곡의 색인 표기: 곡명들, 가수 이름들, 가수+곡·곡+가수 조합 (붙여쓰기 입력 "아이유밤편지" 대비) */
    fun terms(titles: Collection<String>, artists: Collection<String>): List<Term> {
        val ts = titles.map(::norm).filter { it.isNotEmpty() }.distinct()
        val ars = artists.map(::norm).filter { it.isNotEmpty() }.distinct()
        return (ts.map { Term(it, "TITLE") } + ars.map { Term(it, "ARTIST") } +
            ts.flatMap { t -> ars.flatMap { a -> listOf(Term(a + t, "ARTIST_TITLE"), Term(t + a, "ARTIST_TITLE")) } })
            .distinctBy { it.text }
    }
}
