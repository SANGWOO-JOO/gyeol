package dev.gyeol.fetcher.musicbrainz

import java.text.Normalizer

/** 곡명·가수 이름 비교 (docs/l1-design.md 4-3 규칙, tools/cover-art/measure.mjs와 같은 기준) */
object TitleMatch {
    // 하이픈·가운뎃점·프라임의 유니코드 변형도 같게 본다 (Good‐bye의 U+2010, マイ・フレンド의 U+30FB)
    private val STRIP = Regex("""[\s()\[\]~～\-'’.,!?:&‐‑‒–—―・･′]""")
    private val SPLIT = Regex("""\s*[/(（]\s*|\s*[)）]\s*""")
    private val HANGUL = Regex("""[가-힣]""")
    private val DASH = Regex("""\s+[-–]\s+""")
    private val MIXED = Regex("""^([가-힣][가-힣\s\d.,!?…]*?)\s+([A-Za-z].*)$""")
    private val FEAT_TAIL =Regex("""\s+(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val NOT_TITLE = Regex(
        """^(?:feat\.?|ft\.?|with|prod\.?)(?:\s|$)|^bonus\s*track$|^(?:live|remix|inst\.?|instrumental|acoustic|mr)$|\b(?:ver\.?|version)$""",
        RegexOption.IGNORE_CASE,
    )

    fun norm(s: String?): String = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFKC).lowercase().replace(STRIP, "")

    /** 비교 후보: 전체, 괄호 앞뒤, "/" 앞뒤 (예: "뚜두뚜두 (DDU-DU DDU-DU)", "Everything / 걱정하지마") */
    fun parts(s: String): Set<String> = (listOf(s) + s.split(SPLIT)).map(::norm).filter { it.isNotEmpty() }.toSet()

    /**
     * 찾는 제목에서 곡명으로 볼 부분들 (예: "Good day (좋은 날)" → Good day, 좋은 날).
     * 피처링·버전 표시는 곡명이 아니므로 빼고, 여러 부분이면 한 글자 조각도 뺀다. 남는 게 없으면 원문 그대로.
     */
    fun searchParts(s: String): List<String> {
        val pieces = s.split(SPLIT).flatMap(::alternatives).map { it.replace(FEAT_TAIL, "").trim() }.filter { norm(it).isNotEmpty() && !NOT_TITLE.containsMatchIn(it) }
        val named = if (pieces.size > 1) pieces.filter { norm(it).length >= 2 } else pieces
        return named.distinctBy(::norm).ifEmpty { listOf(s) }
    }

    /** 괄호 없이 병기한 제목을 나눈다 ("앨범 - 곡", "한글 제목 English Title") */
    private fun alternatives(piece: String): List<String> = piece.split(DASH).flatMap { part ->
        MIXED.find(part.trim())?.destructured?.toList() ?: listOf(part)
    }

    private val DUET = Regex("""\s*(?:&|,|×|\s[xX]\s|\bfeat\.?\s|\bwith\s)\s*""", RegexOption.IGNORE_CASE)

    /** "서인국 & 정은지" 같은 합쳐진 이름의 첫 사람. 하나뿐이면 null */
    fun firstOfDuet(name: String): String? = name.split(DUET).map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size > 1 }?.first()

    /**
     * 잘린 제목 ("일도 맑은 하늘처럼" ⊂ "내일도 맑은 하늘 처럼"). 찾는 제목이 5글자 이상이고, 후보 안에 통째로 들어 있으며,
     * 후보 글자 수의 80% 이상일 때만. 다른 방법으로 못 찾았을 때 마지막에만 쓴다.
     */
    fun partialTitle(candidate: String, wanted: String): Boolean {
        val w = norm(wanted)
        val c = norm(candidate)
        return w.length >= 5 && w != c && w in c && w.length * 10 >= c.length * 8
    }

    /** 이름 순서만 다른 표기를 같게 본다 (Jong-Kook Kim = Kim Jong-kook) */
    fun nameWords(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.sorted()

    /** MusicBrainz 제목 [candidate]가 찾는 제목 [wanted]와 같은 곡인지. 한글 제목은 로마자 표기도 비교 (난춘 → NAN CHUN) */
    fun sameTitle(candidate: String, wanted: String): Boolean {
        val p = parts(candidate)
        val whole = wanted.split(SPLIT).filter { !NOT_TITLE.containsMatchIn(it.trim()) }.map(::norm).filter { it.isNotEmpty() } // "꼬마 - I Cry"처럼 " - "까지가 곡명인 경우
        return (searchParts(wanted).map(::norm) + whole + norm(wanted)).any { it in p || (HANGUL.containsMatchIn(it) && norm(roman(it)) in p) }
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
