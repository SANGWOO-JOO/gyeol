package dev.gyeol.core.identify

/**
 * Commons 작성자 칸 정리 (I-44): 화면 저작자 표시용.
 * HTML 태그·엔티티, 앞의 "Upload:"·"Photo:" 같은 칸 이름, 연속 공백을 지운다. 남는 게 없으면 null.
 * 주소는 표시용 이름으로 줄인다: YouTube 채널은 "@채널명", flickr·instagram·x(twitter) 프로필은 사용자 이름, 그 밖은 도메인(www. 뺀 호스트). 원본 링크는 pageUrl에 있다.
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
    /** 끝이 잘린 주소 조각. 대소문자 구분, "http:"·"www." 이상만 (이름 끝 이니셜 "H"는 남긴다) */
    private val CUT_URL = Regex("""\s+(?:https?:/{0,2}|www\.)$""")
    /** "h"·"ht"·"htt" 단독 조각: 원문에 http 주소가 있었을 때만 잘린 흔적으로 본다 */
    private val CUT_H = Regex("""\s+(?:h|ht|htt)$""")
    private val FLICKR_ID = Regex("""^\d+@N\d+$""")
    private val SOCIAL_SKIP = setOf("p", "reel", "reels", "explore", "stories", "tv", "i", "intent", "home", "share", "search", "hashtag", "groups", "photos", "people")
    private const val LONG_PAREN = 20
    private const val MAX = 40
    private const val MAX_WITH_NAME = 60
    private val TAIL_PAREN = Regex("""\s*(\(([^()]+)\))$""")
    private val NUMERIC = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

    fun clean(raw: String?): String? {
        if (raw == null) return null
        var s = TAG.replace(raw, " ")
        s = NUMERIC.replace(s) { m ->
            val v = m.groupValues[1]
            (if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull())?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: " "
        }
        ENTITIES.forEach { (k, v) -> s = s.replace(k, v) }
        s = WIKI_INT.replace(s) { it.groupValues[1] }
        s = WIKI_EXT.replace(s) { m -> m.groupValues[1].ifBlank { m.value.trim('[', ']') } }
        s = URL.replace(s) { shorten(it.value.trimEnd('.', ',', ';', ':')) + it.value.drop(it.value.trimEnd('.', ',', ';', ':').length) }
        s = SPACE.replace(s, " ").trim()
        // 칸 이름이 겹쳐 올 수 있다 ("Photo: Upload: …")
        while (true) { val n = LABEL.replace(s, "").trim(); if (n == s) break; s = n }
        s = SEE.replace(s, "")
        // 칸 전체가 긴 괄호(또는 맨 앞 닫히지 않은 괄호)면 괄호를 빼지 않고 그대로 상한만 적용한다
        s = dropLongParens(s).ifBlank { s }
        val hadUrl = raw.contains("http", ignoreCase = true)
        while (true) {
            var n = CUT_URL.replace(s.trim(), "")
            if (hadUrl) n = CUT_H.replace(n, "")
            if (n == s.trim()) break; s = n
        }
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

    /**
     * 40자(코드포인트) 상한. 뒤쪽 절반에 빈칸이 있으면 단어 경계에서 자른다.
     * 끝에 짧은 괄호(20자 이하, 보통 촬영자 이름)가 있으면 그 괄호는 남기고 앞부분만 줄인다
     * ("Korea.net / Korean Culture… (Jeon Han)"). 앞부분은 최소 20자를 남기고, 이때 전체는 60자를 넘지 않는다.
     */
    private fun cap(s: String): String {
        if (len(s) <= MAX || (s.endsWith("…") && len(s) <= MAX + 1)) return s
        TAIL_PAREN.find(s)?.let { m ->
            val tail = " " + m.groupValues[1]
            val head = s.substring(0, m.range.first).trimEnd()
            if (head.isNotEmpty() && len(m.groupValues[2]) <= LONG_PAREN) {
                val budget = maxOf(MAX / 2, MAX - len(tail) - 1)
                // 이미 줄인 앞부분(…로 끝남)은 다시 자르지 않는다: worker 저장과 api 응답에서 두 번 불려도 같은 값 (멱등)
                val short = if (len(head) <= budget || (head.endsWith("…") && len(head) <= budget + 1)) head else cut(head, budget) + "…"
                if (len(short + tail) <= MAX_WITH_NAME) return short + tail
            }
        }
        return cut(s, MAX) + "…"
    }

    private fun cut(s: String, n: Int): String {
        var c = s.substring(0, s.offsetByCodePoints(0, n))
        val sp = c.lastIndexOf(' ')
        if (sp >= n / 2) c = c.substring(0, sp)
        return c.trimEnd(' ', ',', ';', '-', '·', ':', '(', '/')
    }

    private fun len(s: String) = s.codePointCount(0, s.length)

    /** 주소 하나 → 표시용 이름. 못 읽는 주소는 그대로 둔다 */
    private fun shorten(url: String): String {
        val uri = runCatching { java.net.URI(if (url.contains("://")) url else "https://$url") }.getOrNull() ?: return url
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return url
        val seg = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        // 사진 사이트 프로필 주소는 경로의 사용자 이름 (숫자 id뿐이면 도메인)
        if (host == "flickr.com" && seg.size >= 2 && seg[0] in setOf("photos", "people") && !FLICKR_ID.matches(seg[1])) return seg[1]
        if ((host == "instagram.com" || host == "x.com" || host == "twitter.com") && seg.isNotEmpty() && seg[0].lowercase() !in SOCIAL_SKIP) return seg[0].removePrefix("@")
        if (host == "youtube.com") {
            YT_CHANNEL.find(uri.rawPath ?: "")?.let { m ->
                val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
                return "@" + java.net.URLDecoder.decode(name, Charsets.UTF_8)
            }
        }
        return host
    }
}
