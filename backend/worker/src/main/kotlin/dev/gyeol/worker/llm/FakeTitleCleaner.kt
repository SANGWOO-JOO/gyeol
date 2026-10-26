package dev.gyeol.worker.llm

/**
 * 가짜 LLM (gyeol.worker.llm.mode=fake). 흔한 형태만 흉내 낸다: "[MV] 가수 _ 곡", "가수 - 곡 (Official …)".
 * 비용 없이 파이프라인 전체를 시험하고, 부하 실험에서 LLM 비용을 0으로 만드는 용도. 정확도 측정에는 쓰지 않는다.
 */
class FakeTitleCleaner : TitleCleaner {
    private val decoration = Regex("""\[[^\]]*]|【[^】]*】|\((official|lyrics?|audio|m/?v)[^)]*\)""", RegexOption.IGNORE_CASE)
    private val separator = Regex("""\s+[-_|/]\s+""")
    private val live = Regex("""live|라이브|직캠|fancam|concert""", RegexOption.IGNORE_CASE)
    private val cover = Regex("""cover|커버""", RegexOption.IGNORE_CASE)
    private val playlist = Regex("""playlist|플레이리스트|모음|1시간|1 hour""", RegexOption.IGNORE_CASE)

    override fun clean(items: List<RawTitle>, model: String?): CleanResult = CleanResult(
        items.map { raw ->
            val version = when {
                playlist.containsMatchIn(raw.title) -> Version.NOT_SONG
                cover.containsMatchIn(raw.title) -> Version.COVER
                live.containsMatchIn(raw.title) -> Version.LIVE
                else -> Version.ORIGINAL
            }
            val parts = raw.title.replace(decoration, " ").trim().split(separator, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.isNotEmpty() }) {
                CleanedTitle(raw.index, parts[0], null, parts[1], null, version)
            } else {
                CleanedTitle(raw.index, null, null, null, null, Version.NOT_SONG)
            }
        },
        model = "fake", inputTokens = 0, outputTokens = 0, latencyMs = 0,
    )
}
