package dev.gyeol.core.identify

import java.text.Normalizer

/**
 * 전역 캐시 키: 정규화한 "제목|채널".
 * 전각·반각과 합성 문자(NFKC), 대소문자, 연속 공백 차이를 없앤다. 문장부호는 곡을 가르는 정보일 수 있어 그대로 둔다.
 */
object AliasKey {
    private val SPACES = Regex("""\s+""")

    fun of(title: String, channel: String?): String = "${norm(title)}|${norm(channel.orEmpty())}"

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().replace(SPACES, " ").trim()
}
