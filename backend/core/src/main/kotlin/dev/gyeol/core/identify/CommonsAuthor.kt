package dev.gyeol.core.identify

/**
 * Commons 작성자 칸 정리 (I-44): 화면 저작자 표시용.
 * HTML 태그·엔티티, 앞의 "Upload:"·"Photo:" 같은 칸 이름, 연속 공백을 지운다. 남는 게 없으면 null.
 * 주소는 표시용 이름으로 줄인다: YouTube 채널은 "@채널명", 그 밖은 도메인(www. 뺀 호스트). 원본 링크는 pageUrl에 있다.
 * worker가 저장할 때와 api가 응답할 때 둘 다 부른다 (이미 저장된 행도 응답에서 정리된다)
 */
object CommonsAuthor {
    private val TAG = Regex("<[^>]*>")
    private val LABEL = Regex(
        """^(?:uploaded\s+by|upload(?:er)?|photo(?:graph(?:er)?)?|author|credit|source|by|own\s+work\s+by|사진|촬영)\s*[:：]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val SPACE = Regex("""\s+""")
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")
    private val URL = Regex("""(?:https?://|www\.)[^\s<>"'()]+""", RegexOption.IGNORE_CASE)
    private val YT_CHANNEL = Regex("""^/(?:@([^/?#]+)|(?:c|user)/([^/?#]+))""")
    private val NUMERIC = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

    fun clean(raw: String?): String? {
        if (raw == null) return null
        var s = TAG.replace(raw, " ")
        s = NUMERIC.replace(s) { m ->
            val v = m.groupValues[1]
            (if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull())?.let { String(Character.toChars(it)) } ?: " "
        }
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v) }
        s = URL.replace(s) { shorten(it.value.trimEnd('.', ',', ';', ':')) + it.value.drop(it.value.trimEnd('.', ',', ';', ':').length) }
        s = SPACE.replace(s, " ").trim()
        // 칸 이름이 겹쳐 올 수 있다 ("Photo: Upload: …")
        while (true) { val n = LABEL.replace(s, "").trim(); if (n == s) break; s = n }
        return s.trim(' ', ',', ';', '-', '·').ifEmpty { null }
    }

    /** 주소 하나 → 표시용 이름. 못 읽는 주소는 그대로 둔다 */
    private fun shorten(url: String): String {
        val uri = runCatching { java.net.URI(if (url.contains("://")) url else "https://$url") }.getOrNull() ?: return url
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return url
        if (host == "youtube.com") {
            YT_CHANNEL.find(uri.rawPath ?: "")?.let { m ->
                val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
                return "@" + java.net.URLDecoder.decode(name, Charsets.UTF_8)
            }
        }
        return host
    }
}
