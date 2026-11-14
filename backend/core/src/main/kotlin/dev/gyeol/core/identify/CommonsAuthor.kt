package dev.gyeol.core.identify

/**
 * Commons 작성자 칸 정리 (I-44): 화면 저작자 표시용.
 * HTML 태그·엔티티, 앞의 "Upload:"·"Photo:" 같은 칸 이름, 연속 공백을 지운다. 남는 게 없으면 null.
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
    private val NUMERIC = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

    fun clean(raw: String?): String? {
        if (raw == null) return null
        var s = TAG.replace(raw, " ")
        s = NUMERIC.replace(s) { m ->
            val v = m.groupValues[1]
            (if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull())?.let { String(Character.toChars(it)) } ?: " "
        }
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v) }
        s = SPACE.replace(s, " ").trim()
        // 칸 이름이 겹쳐 올 수 있다 ("Photo: Upload: …")
        while (true) { val n = LABEL.replace(s, "").trim(); if (n == s) break; s = n }
        return s.trim(' ', ',', ';', '-', '·').ifEmpty { null }
    }
}
