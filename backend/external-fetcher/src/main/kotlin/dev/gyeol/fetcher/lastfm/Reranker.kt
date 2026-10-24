package dev.gyeol.fetcher.lastfm

import dev.gyeol.fetcher.TrackHit

/**
 * Last.fm 검색 결과 다시 정렬하기.
 *
 * Last.fm 곡 이름에는 유튜브 영상 제목이 그대로 쌓여 있어 1위에 MV·댄스 연습·라이브·커버·가사 영상,
 * 업로더 이름이 자주 올라온다 (33개 표본에서 원곡 1위 52%, docs/l1-design.md 4-2장).
 * 그래서 결과를 넉넉히 받아 아래 규칙으로 점수를 매겨 다시 줄 세우고, 같은 곡은 하나로 합친다.
 *
 * - 가수 칸이 검색어와 맞으면 올림
 * - 제목에 검색어(가수 부분을 뺀 나머지)가 있으면 올림
 * - 제목에 변형 표시(MV, Official, Dance Practice, live, cover, lyrics, remix, Inst., MR, 커버, 무대 …)가 있으면 내림
 * - 가수 칸이 [unknown]이거나 채널·레이블 이름 같으면 내림
 * - 점수가 같으면 Last.fm 원래 순서를 따른다
 * - 보여 줄 제목은 앞 번호("045. ")와 "가수 - " 머리말을 떼어 정리한다
 */
object Reranker {

    private val VARIANT = Regex(
        """(?i)(\bm/?v\b|official|\bvideo\b|dance\s*practice|practice|\blive\b|\bcover\b|lyrics?|\bremix\b|\binst\.?\b|instrumental|\bmr\b|karaoke|reaction|fancam|performance|\b4k\b|\bteaser\b|\bshow\b|""" +
            """커버|라이브|가사|노래방|무대|직캠|안무|릴레이|반주|리액션|교차편집|1시간|모음|골든디스크|【|】)""",
    )
    private val CHANNEL_LIKE = Regex(
        """(?i)(labels?\b|records?\b|entertainment|\bent\.?\b|smtown|1thek|\bm2\b|\bmnet\b|\bkbs\b|\bsbs\b|\bmbc\b|jtbc|awards?|blog|\bhybe\b|\bmusic\b)""",
    )
    private val UNKNOWN = Regex("""(?i)^[\[<(]?unknown[\]>)]?$""")
    private val LEADING_NUMBER = Regex("""^\s*\d{1,4}\s*[.)]\s*""")
    private val PREFIX_SEPARATOR = Regex("""\s+[-–—]\s+|\s*[-–—]\s*(?=\S)""")

    /** 비교용: 소문자, 공백·문장부호 제거 (한글·가나·한자·숫자는 남김) */
    fun norm(s: String): String = s.lowercase().replace(Regex("""[\s\p{Punct}·•′'"「」『』【】♪…–—]+"""), "")

    /** "아이유(IU)", "BTS (방탄소년단)", "크러쉬 CRUSH" → 비교할 이름들 */
    fun artistNames(artist: String): List<String> {
        val base = artist.replace(Regex("""\s*[(\[].*?[)\]]\s*"""), " ").trim()
        val inside = Regex("""[(\[](.*?)[)\]]""").findAll(artist).map { it.groupValues[1] }.toList()
        val words = base.split(Regex("""\s+""")).filter { it.length >= 2 }
        return (listOf(base) + inside + words).map(::norm).filter { it.length >= 2 }.distinct()
    }

    /**
     * 보여 줄 제목 정리: 앞 번호 떼기, 검색어에 있는 머리말("아이유 - ") 떼기,
     * 가수 이름이 공백만 두고 붙은 경우("coldplay yellow", 가수 Coldplay)도 뗀다.
     */
    fun cleanTitle(title: String, queryNorm: String, artist: String? = null): String {
        var t = title.replace(LEADING_NUMBER, "").trim()
        val parts = t.split(PREFIX_SEPARATOR, limit = 2)
        if (parts.size == 2) {
            val head = norm(parts[0])
            if (head.isNotEmpty() && head.length < queryNorm.length + 1 && queryNorm.contains(head)) t = parts[1].trim()
        }
        if (artist != null) {
            val base = artist.replace(Regex("""\s*[(\[].*?[)\]]\s*"""), " ").trim()
            if (base.length >= 2 && t.length > base.length + 1 && t.startsWith("$base ", ignoreCase = true) && queryNorm.contains(norm(base))) {
                t = t.substring(base.length).trim()
            }
        }
        return t
    }

    fun isVariant(title: String) = VARIANT.containsMatchIn(title)
    fun isUnknownArtist(artist: String) = UNKNOWN.matches(artist.trim())
    fun isChannelLike(artist: String) = CHANNEL_LIKE.containsMatchIn(artist)

    data class Scored(val hit: TrackHit, val score: Int, val originalRank: Int)

    fun rerank(query: String, candidates: List<TrackHit>, limit: Int): List<TrackHit> {
        val qNorm = norm(query)
        val tokens = query.trim().split(Regex("""\s+""")).map(::norm).filter { it.isNotEmpty() }

        val scored = candidates.mapIndexed { i, c ->
            val title = cleanTitle(c.title, qNorm, c.artist)
            val tNorm = norm(title)
            val names = artistNames(c.artist)

            val artistTokens = tokens.filter { tok -> names.any { n -> n.contains(tok) || (tok.length >= 2 && tok.contains(n)) } }
            val titleTokens = tokens - artistTokens.toSet()

            var score = 0
            if (artistTokens.isNotEmpty()) score += 4
            if (titleTokens.isNotEmpty() && titleTokens.all { tNorm.contains(it) }) score += 3
            else if (titleTokens.any { tNorm.contains(it) }) score += 1
            if (isVariant(c.title)) score -= 4
            if (isUnknownArtist(c.artist)) score -= 3
            if (isChannelLike(c.artist)) score -= 2
            if (title != c.title.trim()) score -= 1 // 원래 제목이 지저분했으면(번호·머리말) 깔끔한 항목보다 살짝 아래

            Scored(c.copy(title = title), score, i)
        }

        // 같은 곡(괄호를 뗀 제목 + 대표 가수 이름)은 점수가 가장 높은 하나로 합치되,
        // 버려지는 항목의 곡 ID(mbid)와 청취자 수는 넘겨받는다 (표기만 다른 같은 곡에 ID가 붙어 있는 경우가 많음)
        val key = { s: Scored -> norm(s.hit.title.replace(Regex("""\s*[(\[].*?[)\]]\s*"""), "")) + "|" + (artistNames(s.hit.artist).firstOrNull() ?: "") }
        val ordered = scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.originalRank })
        return ordered
            .groupBy(key)
            .values
            .map { group ->
                val best = group.first()
                best.copy(
                    hit = best.hit.copy(
                        mbid = best.hit.mbid ?: group.firstNotNullOfOrNull { it.hit.mbid },
                        listeners = group.mapNotNull { it.hit.listeners }.maxOrNull(),
                    ),
                )
            }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.originalRank })
            .take(limit)
            .map { it.hit }
    }
}
