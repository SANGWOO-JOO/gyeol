package dev.gyeol.core.identify

/**
 * Commons 작성자 칸 정리 (I-44): 화면 저작자 표시용.
 * HTML 태그·엔티티, 앞의 "Upload:"·"Photo:" 같은 칸 이름, 연속 공백을 지운다. 남는 게 없으면 null.
 * 주소는 표시용 이름으로 줄인다: YouTube 채널은 "@채널명", 그 밖은 도메인(www. 뺀 호스트). 원본 링크는 pageUrl에 있다.
 * 긴 설명형(I-47): 위키 링크 "[주소 텍스트]"·"[[문서|텍스트]]"는 텍스트만, 끝이 잘린 "http:/"·"h" 조각과 "see …"는 지우고,
 * 20자 넘는 괄호 설명(닫히지 않은 괄호는 끝까지)은 빼며, 그래도 40자를 넘으면 자르고 "…"를 붙인다.
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
    private val WIKI_EXT = Regex("""\[(?:https?://|www\.)[^\s\]]*(?:\s+([^\]]*))?]?""", RegexOption.IGNORE_CASE)
    private val WIKI_INT = Regex("""\[\[(?:[^|\]]*\|)?([^\]]*)]]""")
    private val SEE = Regex("""[,;]\s*see\s.*$""", RegexOption.IGNORE_CASE)
    private val CUT_URL = Regex("""\s+(?:h|ht|htt|https?:?/?/?|www\.?)$""", RegexOption.IGNORE_CASE)
    private const val LONG_PAREN = 20
    private const val MAX = 40
    private val NUMERIC = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

    fun clean(raw: String?): String? {
        if (raw == null) return null
        var s = TAG.replace(raw, " ")
        s = NUMERIC.replace(s) { m ->
            val v = m.groupValues[1]
            (if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull())?.let { String(Character.toChars(it)) } ?: " "
        }
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v) }
        s = WIKI_INT.replace(s) { it.groupValues[1] }
        s = WIKI_EXT.replace(s) { m -> m.groupValues[1].ifBlank { m.value.trim('[', ']') } }
        s = URL.replace(s) { shorten(it.value.trimEnd('.', ',', ';', ':')) + it.value.drop(it.value.trimEnd('.', ',', ';', ':').length) }
        s = SPACE.replace(s, " ").trim()
        // 칸 이름이 겹쳐 올 수 있다 ("Photo: Upload: …")
        while (true) { val n = LABEL.replace(s, "").trim(); if (n == s) break; s = n }
        s = SEE.replace(s, "")
        s = dropLongParens(s)
        while (true) { val n = CUT_URL.replace(s.trim(), ""); if (n == s.trim()) break; s = n }
        s = SPACE.replace(s, " ").trim(' ', ',', ';', '-', '·', ':')
        return cap(s).ifEmpty { null }
    }

    /** 괄호 안 설명이 길면 괄호째 뺀다. 중첩은 바깥 괄호 기준, 닫히지 않으면 끝까지 */
    private fun dropLongParens(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '(' && c != '（') { out.append(c); i++; continue }
            var depth = 0; var j = i
            while (j < s.length) {
                if (s[j] == '(' || s[j] == '（') depth++
                if (s[j] == ')' || s[j] == '）') { depth--; if (depth == 0) break }
                j++
            }
            val inner = s.substring(i + 1, minOf(j, s.length))
            if (j >= s.length || inner.codePointCount(0, inner.length) > LONG_PAREN) i = j + 1
            else { out.append(s, i, j + 1); i = j + 1 }
        }
        return out.toString()
    }

    /** 40자(코드포인트) 상한. 뒤쪽 절반에 빈칸이 있으면 단어 경계에서 자른다 */
    private fun cap(s: String): String {
        if (s.codePointCount(0, s.length) <= MAX) return s
        var cut = s.substring(0, s.offsetByCodePoints(0, MAX))
        val sp = cut.lastIndexOf(' ')
        if (sp >= MAX / 2) cut = cut.substring(0, sp)
        return cut.trimEnd(' ', ',', ';', '-', '·', ':', '(') + "…"
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
