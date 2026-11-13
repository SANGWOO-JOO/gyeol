package dev.gyeol.api.search

/**
 * Last.fm 인기곡에 섞이는 원곡이 아닌 항목 (I-36): 직캠·팬캠·커버·4K·1시간 반복·가사 영상·퍼포먼스·연습 영상·방송 녹화.
 * 정상 곡을 잘못 거르지 않게 보수적으로 잡는다: 리믹스·밴드 버전·Inst.처럼 실제 음원인 판은 거르지 않는다(인기순에는 보인다).
 * "Lyrics"·"Cover"처럼 곡 제목일 수 있는 단어는 앞뒤 표기("cover by", "(cover)", "/ 가사 Lyrics")가 있을 때만 거른다
 */
object TitleNoise {
    private val REGEX = Regex(
        listOf(
            """직캠""", """팬캠""", """fan\s*cam""", """full\s*cam""", """\b4k\b""",
            """cover\s+by\b""", """[(\[]\s*cover\s*[)\]]""", """커버""",
            """1\s*시간""", """\b1\s*hours?\b""",
            """가사\s*lyrics""", """[/|]\s*lyrics\b""", """lyrics?\s+video""", """[(\[]\s*lyrics\s*[)\]]""",
            """performance\s+video""", """dance\s+practice""", """안무\s*영상""", """교차\s*편집""", """리액션""",
            """\bm/v\b""", """official\s+(music\s+)?video""", """music\s+video""",
            """\b\d{6}\s*방송""", """\b(mbc|kbs|sbs|mnet|jtbc)\s*\d{6}\b""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun isNoise(title: String): Boolean = REGEX.containsMatchIn(title)
}
