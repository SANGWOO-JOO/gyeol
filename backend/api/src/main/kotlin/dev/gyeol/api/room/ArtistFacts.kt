package dev.gyeol.api.room

import java.util.Locale

/** 가수 판 사실 자료 (LLM 설명 대신 MusicBrainz 사실 줄을 보여 줄 때). 날짜는 넣지 않는다 */
data class FactsView(val area: String? = null, val tags: List<String>? = null)

/**
 * MusicBrainz 사실 줄 해석 (fetcher KnowledgeService.artist의 mbSentence가 만든다):
 * "가수 'BoA' (MusicBrainz): 유형 Person; 지역 South Korea; 활동 시작 1986-11-05; 장르·태그 j-pop, k-pop, pop, …"
 */
object ArtistFacts {
    /** 설명 문자열이 이 사실 줄이면 FACTS (LLM 설명이 없어 사실 줄을 그대로 둔 경우: 로컬 fake 설명 등) */
    private val FACTS_LINE = Regex("""^가수 '.*' \(MusicBrainz\): """)
    private val AREA = Regex("""(?:^|; )지역 ([^;.]+)""")
    private val TAGS = Regex("""장르·태그 ([^;]+?)\.?$""")
    private const val MAX_TAGS = 5

    fun isFacts(description: String?): Boolean = description != null && FACTS_LINE.containsMatchIn(description)

    /** 영어 나라 이름 → 한국어 표시. 나라가 아니면(도시 등) null */
    private val countryKo: Map<String, String> by lazy {
        Locale.getISOCountries().associate { code ->
            Locale.of("", code).getDisplayCountry(Locale.ENGLISH).lowercase() to Locale.of("", code).getDisplayCountry(Locale.KOREAN)
        } + mapOf("south korea" to "한국", "korea, republic of" to "한국", "north korea" to "북한", "united states" to "미국", "united kingdom" to "영국")
    }

    fun parse(line: String?): FactsView? {
        if (line == null) return null
        val area = AREA.find(line)?.groupValues?.get(1)?.trim()?.let { countryKo[it.lowercase()] }
        val tags = TAGS.find(line)?.groupValues?.get(1)?.split(",")?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.none(Char::isDigit) } // "1st gen" 같은 세대 표시는 뺀다
            ?.map(::tidy)?.distinctBy { it.lowercase() }?.take(MAX_TAGS)?.ifEmpty { null }
        return if (area == null && tags == null) null else FactsView(area, tags)
    }

    /** 표기 정리: k-pop → K-pop, r&b → R&B, 나머지는 첫 글자만 대문자 */
    private fun tidy(tag: String): String {
        val t = tag.lowercase()
        return when {
            t == "r&b" -> "R&B"
            Regex("""^[a-z]-pop$""").matches(t) -> t[0].uppercaseChar() + t.substring(1)
            else -> t.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
