package dev.gyeol.core.identify

/**
 * 원곡이 아닌 항목을 이름으로 거른다 (I-36·I-41). 가수 화면 인기순(Last.fm)과 최신순 수집(MusicBrainz)이 함께 쓴다.
 * 1차 거르기라 보수적으로 잡는다: 리믹스·밴드 버전·Live·Japanese version 같은 실제 음원 판과,
 * 곡 제목일 수 있는 단어("Cover Me", "Lyrics", "Performance")는 앞뒤 표기가 있을 때만 거른다.
 * 녹음 목록 대조(2차)는 api ArtistSongsService가 저장된 결과로 한다.
 */
object TitleNoise {
    private val PATTERNS = listOf(
        // 직캠·팬캠·4K
        """직캠""", """팬캠""", """fan\s*cam""", """full\s*cam""", """\b4k\b""",
        // 커버: "cover by", "(Acoustic Cover)", "… Cover 주은", "piano cover"
        """cover\s+by\b""", """[(\[][^)\]]*\bcover\b[^)\]]*[)\]]""", """\bcover\s+[가-힣]""",
        """\b(acoustic|piano|band|vocal|guitar|male|female|korean|english)\s+cover\b""", """커버""",
        // 1시간 반복·가사 영상
        """1\s*시간""", """\b1\s*hours?\b""", """가사\s*lyrics""", """[/|]\s*lyrics\b""", """lyrics?\s+video""", """[(\[]\s*lyrics\s*[)\]]""",
        // 퍼포먼스·연습·편집 영상, 뮤직비디오
        """performance\s+video""", """dance\s+practice""", """안무\s*영상""", """교차\s*편집""", """리액션""",
        """\bm/v\b""", """official\s+(music\s+)?video""", """music\s+video""",
        // 방송 녹화: "MBC 250129 방송", 예능 영상: 파이프로 이어진 제목, "@ 아이유의 팔레트"
        """\b\d{6}\s*방송""", """\b(mbc|kbs|sbs|mnet|jtbc)\s*\d{6}\b""", """\s\|\s.*\s\|\s""", """\s@\s*\S""",
        // 반주(instrumental·inst·MR): 인기순에서 원곡을 가리지 않게
        """\binstrumental\b""", """[(\[\s-]\s*inst\.?\s*[)\]]?\s*$""", """[(\[]\s*mr\s*[)\]]""",
        // 팬 편집: flip·bootleg·장르 리믹스
        """\bflip\b""", """\bbootleg\b""", """jersey\s*club""", """future\s*funk""", """\bareia\b""", """kpop\s+remix""",
        """\b(trap|uk\s*garage|garage|city\s*pop|citypop|jazz|r&b|lo-?fi|phonk|drill|hardstyle|nightcore)\s+(remix|ver\.?|version|flip|edit)\b""",
        // 무대 보조: VCR·stage talk, 멤버 스킷 "[MINJI] …"
        """\bvcr\b""", """stage\s*talk""", """^\s*\[[A-Z]{2,}]""",
        // 시상식 무대판·팬 편집 판·데모 (I-41 NewJeans 표본: "(MMA ver.)", "(KGMA Remix)", "(Cut Ver.)", "데모곡")
        """\b(mma|mama|kgma|gda|sma|aaa)\b\s*(ver|remix|version|stage)""", """[(\[]\s*(kgma|mma|mama)\b""", """\bcut\s*ver""", """데모곡""",
        // 뉴스 문장
        """전속\s*계약""", """속보""", """\[단독]""", """기자회견""",
    )
    private val REGEX = Regex(PATTERNS.joinToString("|"), RegexOption.IGNORE_CASE)

    /** 라이브 공연 사이 부분 (최신순 수집에서만): 제목 전체가 opening·ending·band session·mc·talk(+번호) */
    private val LIVE_SEGMENT = Regex("""^\s*(opening|ending|band\s*session|mc|talk)(\s*#?\d+)?\s*$""", RegexOption.IGNORE_CASE)

    fun isNoise(title: String): Boolean = REGEX.containsMatchIn(title) || oddSymbols(title) >= 3 || hasEmoji(title)

    fun isLiveSegment(title: String): Boolean = LIVE_SEGMENT.matches(title)

    /**
     * 제목 안에 가수 이름이 들어간 영상식 제목 ("NewJeans (뉴진스) 'Zero' Official", "Message from NewJeans (KR)").
     * 제목이 가수 이름과 같은 곡("New Jeans")은 남긴다. [names]는 이 가수의 이름·별칭
     */
    fun mentionsArtist(title: String, names: Collection<String>): Boolean {
        val t = SearchText.norm(title)
        return names.map(SearchText::norm).filter { it.length >= 3 }.any { n -> t != n && t.contains(n) }
    }

    /** 글자·숫자·공백·흔한 문장부호가 아닌 기호 수 (".✭.・✫・" → 많음, "삐빅 (♥beats)" → 1) */
    fun oddSymbols(title: String): Int = title.count { c ->
        !c.isLetterOrDigit() && !c.isWhitespace() && c !in """.,'"!?&()[]-_/:;+~*#%°’‘“”…·""" && !Character.isSurrogate(c)
    }

    private fun hasEmoji(title: String): Boolean = title.codePoints().anyMatch { cp ->
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x26FF && cp != 0x2665 || cp in 0x2700..0x27BF
    }

    /**
     * Last.fm 결과의 가수 이름이 영상 제목처럼 보인다 (검색에서 거름): 한글 조사 뒤 띄어쓰기가 있는 12자 이상 문장,
     * 또는 파이프·@·이모지. "성시경(Sungsikyung)의 킬링보이스를 라이브로!"는 걸리고 "P!nk"·"Clover!"는 남는다
     */
    fun isVideoLikeArtist(artist: String): Boolean =
        (artist.length >= 12 && Regex("""[가-힣](의|를|을|에서|으로)\s""").containsMatchIn(artist)) ||
            artist.contains('|') || Regex("""\s@\s*\S""").containsMatchIn(artist) || hasEmoji(artist)
}
