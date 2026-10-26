package dev.gyeol.fetcher.musicbrainz

import java.text.Normalizer

/** 곡명·가수 이름 비교 (docs/l1-design.md 4-3 규칙, tools/cover-art/measure.mjs와 같은 기준) */
object TitleMatch {
    private val STRIP = Regex("""[\s()\[\]~～\-'’.,!?:&]""")
    private val SPLIT = Regex("""\s*[/(（]\s*|\s*[)）]\s*""")
    private val HANGUL = Regex("""[가-힣]""")

    fun norm(s: String?): String = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFKC).lowercase().replace(STRIP, "")

    /** 비교 후보: 전체, 괄호 앞뒤, "/" 앞뒤 (예: "뚜두뚜두 (DDU-DU DDU-DU)", "Everything / 걱정하지마") */
    fun parts(s: String): Set<String> = (listOf(s) + s.split(SPLIT)).map(::norm).filter { it.isNotEmpty() }.toSet()

    /** MusicBrainz 제목 [candidate]가 찾는 제목 [wanted]와 같은 곡인지. 한글 제목은 로마자 표기도 비교 (난춘 → NAN CHUN) */
    fun sameTitle(candidate: String, wanted: String): Boolean {
        val p = parts(candidate)
        return norm(wanted) in p || (HANGUL.containsMatchIn(wanted) && norm(roman(wanted)) in p)
    }

    // 국어의 로마자 표기법 (음운 변화는 무시)
    private val I = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val V = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val F = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "l", "l", "l", "l", "l", "l", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")

    fun roman(s: String): String = buildString {
        for (ch in s) {
            val c = ch.code - 0xAC00
            if (c < 0 || c > 11171) append(ch) else append(I[c / 588]).append(V[c % 588 / 28]).append(F[c % 28])
        }
    }
}
