package dev.gyeol.core.identify

import dev.gyeol.core.listen.Source

/** MusicBrainz에 물어볼 (가수, 곡). [byChannelName]이면 채널 이름을 가수로 추정한 것이라 덜 확실하다 */
data class Query(val artist: String, val title: String, val byChannelName: Boolean)

/**
 * LLM 없이 제목·채널만으로 (가수, 곡)을 정한다.
 * - "가수 - Topic" 채널: YouTube가 음원 유통사 정보로 만든 채널이라 가수·곡명이 정확하다
 * - 뮤직 앱인데 Topic이 아닌 채널: 공식 채널일 가능성이 높아 채널 이름을 가수로 한 번 시도한다
 * - 그 밖(일반 영상): 제목에 가수·장식이 섞여 있어 규칙으로 풀지 않는다 → L2-3 LLM
 */
object RuleIdentifier {
    private const val TOPIC = " - Topic"
    // 공식 채널 이름에 붙는 꼬리 (예: "IU Official", "BTS OFFICIAL", "NewJeansVEVO")
    private val CHANNEL_SUFFIX = Regex("""\s*(official|vevo|공식)\s*$""", RegexOption.IGNORE_CASE)

    fun query(title: String, channel: String?, source: Source): Query? {
        val t = title.trim()
        val ch = channel?.trim().orEmpty()
        if (t.isEmpty() || ch.isEmpty()) return null
        if (ch.endsWith(TOPIC)) {
            val artist = ch.removeSuffix(TOPIC).trim()
            return if (artist.isEmpty()) null else Query(artist, t, byChannelName = false)
        }
        if (source == Source.MUSIC_APP) {
            val artist = ch.replace(CHANNEL_SUFFIX, "").trim()
            return if (artist.isEmpty()) null else Query(artist, t, byChannelName = true)
        }
        return null
    }
}
