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

/** 제목 정리 (L2-3a). 구현: [FakeTitleCleaner](테스트·부하 실험), [BedrockTitleCleaner](실제) */
interface TitleCleaner {
    fun clean(items: List<RawTitle>, model: String? = null): CleanResult
}

/**
 * LLM 답을 그대로 믿지 않는다 (지어낸 곡 정보 막기).
 * - 곡명이나 다른 표기 중 하나가 원래 제목 안에 실제로 있어야 한다
 * - 가수(또는 다른 표기)는 제목이나 채널 이름 안에 있어야 한다
 * 통과하지 못하면 null → "못 찾음(INVALID)"
 */
object CleanedValidator {
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
