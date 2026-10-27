package dev.gyeol.fetcher.knowledge

/** 청크 본문 다듬기: HTML 제거, 출처 링크 문구 제거, 문장 단위로 길이 제한 */
object Chunks {
    const val MAX = 1200
    private val TAG = Regex("""<[^>]+>""")
    private val SPACES = Regex("""\s+""")
    // Last.fm 위키 끝에 붙는 "Read more on Last.fm" 링크와 사용자 기여 안내
    private val LASTFM_TAIL = Regex("""\s*(Read more on Last\.fm|User-contributed text is available under).*$""", RegexOption.DOT_MATCHES_ALL)
    private val SENTENCE_END = Regex("""(?<=[.!?。！？])\s+|(?<=다\.)\s*""")

    fun clean(raw: String?): String =
        raw.orEmpty().replace(LASTFM_TAIL, "").replace(TAG, " ").replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")
            .replace(SPACES, " ").trim()

    /** [max]자를 넘으면 그 안에서 끝나는 마지막 문장까지만 (문장이 너무 길면 그냥 자른다) */
    fun limit(text: String, max: Int = MAX): String {
        if (text.length <= max) return text
        val head = text.take(max)
        val cut = SENTENCE_END.findAll(head).lastOrNull()?.range?.first
        return if (cut != null && cut > max / 3) head.take(cut).trim() else head.trim()
    }
}
