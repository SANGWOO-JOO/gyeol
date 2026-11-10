package dev.gyeol.worker.llm

import dev.gyeol.core.identify.SearchText

/** LLM에 보내는 한 줄: YouTube 제목과 채널 이름만 (사용자 정보는 보내지 않는다) */
data class RawTitle(val index: Int, val title: String, val channel: String?)

enum class Version { ORIGINAL, LIVE, REMIX, COVER, INSTRUMENTAL, MEDLEY, NOT_SONG }

data class CleanedTitle(
    val index: Int,
    val artist: String?,
    val artistAlt: String?,
    val title: String?,
    val titleAlt: String?,
    val version: Version,
)

data class CleanResult(val items: List<CleanedTitle>, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

data class MedleyItem(val index: Int, val songs: List<Pair<String?, String>>)
data class MedleyResult(val items: List<MedleyItem>, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

data class Spelling(val index: Int, val artists: List<String>, val titles: List<String>)
data class SpellResult(val items: List<Spelling>, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

/** 제목 정리 (L2-3a). 구현: [FakeTitleCleaner](테스트·부하 실험), [BedrockTitleCleaner](실제) */
interface TitleCleaner {
    fun clean(items: List<RawTitle>, model: String? = null): CleanResult

    /**
     * 못 찾은 Topic 곡의 다른 표기 후보 (TIME CAPSULE → 타임캡슐, Seo InGuk → 서인국).
     * [RawTitle]의 title은 곡명, channel은 가수. 후보는 MusicBrainz에서 제목이 정확히 일치할 때만 쓴다.
     */
    fun spellings(items: List<RawTitle>, model: String? = null): SpellResult = SpellResult(emptyList(), "fake", 0, 0, 0)

    /** 메들리 영상 제목에 적힌 곡들 (가수, 곡명). 제목에 곡명이 없으면 빈 목록 */
    fun medleySongs(items: List<RawTitle>, model: String? = null): MedleyResult = MedleyResult(emptyList(), "fake", 0, 0, 0)
}

/**
 * LLM 답을 그대로 믿지 않는다 (지어낸 곡 정보 막기).
 * - 곡명이나 다른 표기 중 하나가 원래 제목 안에 실제로 있어야 한다
 * - 가수(또는 다른 표기)는 제목이나 채널 이름 안에 있어야 한다
 * 통과하지 못하면 null → "못 찾음(INVALID)"
 */
object CleanedValidator {
    /** 메들리 곡 중 제목에 실제로 적힌 곡만. 가수는 제목이나 채널에 있을 때만 남긴다 (최대 6곡) */
    fun medley(raw: RawTitle, songs: List<Pair<String?, String>>): List<Pair<String?, String>> {
        val haystack = SearchText.norm(raw.title)
        val withChannel = haystack + SearchText.norm(raw.channel)
        return songs.mapNotNull { (artist, song) ->
            val s = SearchText.norm(song)
            if (s.isEmpty() || s !in haystack) return@mapNotNull null
            artist?.takeIf { a -> SearchText.norm(a).let { it.isNotEmpty() && it in withChannel } } to song.trim()
        }.distinctBy { SearchText.norm(it.second) }.take(15)
    }

    /**
     * 연주·커버 영상의 정리된 곡명과 원곡 가수. 제목 안에 실제로 있는 표기만 쓴다 (곡명이 없으면 null).
     * 채널 이름에 있는 사람은 연주·커버를 한 사람일 수 있어 원곡 가수로 보지 않는다.
     */
    fun performanceNames(raw: RawTitle, c: CleanedTitle): Pair<String, String?>? {
        val haystack = SearchText.norm(raw.title)
        val channel = SearchText.norm(raw.channel)
        fun inTitle(s: String?) = !s.isNullOrBlank() && SearchText.norm(s).let { it.isNotEmpty() && it in haystack }
        fun original(s: String?) = s?.takeIf { inTitle(it) && SearchText.norm(it) !in channel }
        val song = c.title?.takeIf { inTitle(it) } ?: c.titleAlt?.takeIf { inTitle(it) } ?: return null
        return song to (original(c.artist) ?: original(c.artistAlt))
    }

    fun accept(raw: RawTitle, c: CleanedTitle): CleanedTitle? {
        val haystack = SearchText.norm(raw.title)
        val withChannel = haystack + SearchText.norm(raw.channel)
        fun inTitle(s: String?) = !s.isNullOrBlank() && SearchText.norm(s).let { it.isNotEmpty() && it in haystack }
        fun inTitleOrChannel(s: String?) = !s.isNullOrBlank() && SearchText.norm(s).let { it.isNotEmpty() && it in withChannel }
        if (!inTitle(c.title) && !inTitle(c.titleAlt)) return null
        if (!inTitleOrChannel(c.artist) && !inTitleOrChannel(c.artistAlt)) return null
        // 제목에 실제로 있는 표기만 남기고, 곡명으로 올린 표기는 다른 표기에서 뺀다
        val title = c.title?.takeIf { inTitle(it) } ?: c.titleAlt
        val artist = c.artist?.takeIf { inTitleOrChannel(it) } ?: c.artistAlt
        return c.copy(
            title = title,
            titleAlt = c.titleAlt?.takeIf { inTitle(it) && it != title },
            artist = artist,
            artistAlt = c.artistAlt?.takeIf { inTitleOrChannel(it) && it != artist },
        )
    }
}
