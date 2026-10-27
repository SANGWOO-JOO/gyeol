package dev.gyeol.fetcher.knowledge

import tools.jackson.databind.JsonNode

data class ChunkOut(val source: String, val lang: String, val url: String, val license: String, val text: String)
data class CreditOut(val role: String, val artistMbid: String?, val name: String)
data class KnowledgeResult(val chunks: List<ChunkOut>, val credits: List<CreditOut> = emptyList(), val workIds: List<String> = emptyList())

/**
 * 곡·작품·음반·가수의 지식 청크 (docs/l3-design.md L3-1). 외부 호출은 함수로 받아 시험에서 바꿔 끼운다.
 * - MusicBrainz(CC0): 크레딧·장르·태그를 문장 하나로, 위키데이터 링크로 Wikipedia를 찾는다
 * - Wikipedia(CC BY-SA 4.0): 한·영·일 첫 요약 단락
 * - Last.fm: 위키 요약과 상위 태그 (출처 표시)
 * 가사·리뷰는 가져오지 않는다.
 */
class KnowledgeService(
    private val mb: (path: String, params: Map<String, Any>) -> JsonNode,
    private val sitelinks: (qid: String, langs: List<String>) -> Map<String, String>,
    private val wiki: (lang: String, title: String) -> WikiSummary?,
    private val lastfm: (method: String, params: Map<String, String>) -> JsonNode?,
    private val langs: List<String> = listOf("ko", "en", "ja"),
) {
    private val creditRoles = mapOf("composer" to "COMPOSER", "lyricist" to "LYRICIST", "writer" to "WRITER", "arranger" to "ARRANGER", "producer" to "PRODUCER")
    private val roleKo = mapOf("COMPOSER" to "작곡", "LYRICIST" to "작사", "WRITER" to "작사·작곡", "ARRANGER" to "편곡", "PRODUCER" to "프로듀서")

    fun recording(mbid: String, artist: String?, title: String?): KnowledgeResult {
        val r = mb("/recording/$mbid", mapOf("inc" to "work-rels+artist-rels+tags+genres"))
        val credits = credits(r)
        val works = r.path("relations").toList().filter { it.path("type").asString("") == "performance" }
            .mapNotNull { it.path("work").path("id").asString("").ifEmpty { null } }.distinct()
        val chunks = mutableListOf<ChunkOut>()
        mbSentence("녹음", credits, tagNames(r))?.let { chunks += ChunkOut("MUSICBRAINZ", "ko", "https://musicbrainz.org/recording/$mbid", "CC0", it) }
        if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
            lastfm("track.getInfo", mapOf("artist" to artist, "track" to title))?.path("track")?.let { t ->
                lastfmChunk(t.path("wiki").path("summary").asString(""), t.path("toptags").path("tag"), t.path("url").asString(""))?.let { chunks += it }
            }
        }
        return KnowledgeResult(chunks, credits, works)
    }

    fun work(mbid: String): KnowledgeResult {
        val w = mb("/work/$mbid", mapOf("inc" to "artist-rels+url-rels+tags+genres"))
        val credits = credits(w)
        val chunks = mutableListOf<ChunkOut>()
        mbSentence("곡", credits, tagNames(w))?.let { chunks += ChunkOut("MUSICBRAINZ", "ko", "https://musicbrainz.org/work/$mbid", "CC0", it) }
        chunks += wikipedia(w)
        return KnowledgeResult(chunks, credits)
    }

    fun releaseGroup(mbid: String, artist: String?, album: String?): KnowledgeResult {
        val g = mb("/release-group/$mbid", mapOf("inc" to "url-rels+tags+genres"))
        val facts = listOfNotNull(
            g.path("primary-type").asString("").ifEmpty { null }?.let { "종류 $it" },
            g.path("first-release-date").asString("").ifEmpty { null }?.let { "처음 발매 $it" },
        )
        val chunks = mutableListOf<ChunkOut>()
        mbSentence("음반 '${g.path("title").asString("")}'", emptyList(), tagNames(g), facts)?.let { chunks += ChunkOut("MUSICBRAINZ", "ko", "https://musicbrainz.org/release-group/$mbid", "CC0", it) }
        chunks += wikipedia(g)
        if (!artist.isNullOrBlank() && !album.isNullOrBlank()) {
            lastfm("album.getInfo", mapOf("artist" to artist, "album" to album))?.path("album")?.let { a ->
                lastfmChunk(a.path("wiki").path("summary").asString(""), a.path("tags").path("tag"), a.path("url").asString(""))?.let { chunks += it }
            }
        }
        return KnowledgeResult(chunks)
    }

    fun artist(mbid: String, name: String?): KnowledgeResult {
        val a = mb("/artist/$mbid", mapOf("inc" to "url-rels+tags+genres"))
        val facts = listOfNotNull(
            a.path("type").asString("").ifEmpty { null }?.let { "유형 $it" },
            a.path("area").path("name").asString("").ifEmpty { null }?.let { "지역 $it" },
            a.path("life-span").path("begin").asString("").ifEmpty { null }?.let { "활동 시작 $it" },
        )
        val chunks = mutableListOf<ChunkOut>()
        mbSentence("가수 '${a.path("name").asString(name.orEmpty())}'", emptyList(), tagNames(a), facts)?.let { chunks += ChunkOut("MUSICBRAINZ", "ko", "https://musicbrainz.org/artist/$mbid", "CC0", it) }
        chunks += wikipedia(a)
        lastfm("artist.getInfo", mapOf("mbid" to mbid) + (name?.let { mapOf("artist" to it) } ?: emptyMap()))?.path("artist")?.let { x ->
            lastfmChunk(x.path("bio").path("summary").asString(""), x.path("tags").path("tag"), x.path("url").asString(""))?.let { chunks += it }
        }
        return KnowledgeResult(chunks)
    }

    // ── 공통 ──

    private fun credits(node: JsonNode): List<CreditOut> =
        node.path("relations").toList().mapNotNull { r ->
            val role = creditRoles[r.path("type").asString("")] ?: return@mapNotNull null
            val artist = r.path("artist")
            val name = artist.path("name").asString("").ifEmpty { return@mapNotNull null }
            CreditOut(role, artist.path("id").asString("").ifEmpty { null }, name)
        }.distinct()

    /** 장르를 앞에, 그다음 많이 붙은 태그 (최대 8개) */
    private fun tagNames(node: JsonNode): List<String> =
        (node.path("genres").toList() + node.path("tags").toList().sortedByDescending { it.path("count").asInt(0) })
            .map { it.path("name").asString("") }.filter { it.isNotBlank() }.distinct().take(8)

    private fun mbSentence(subject: String, credits: List<CreditOut>, tags: List<String>, facts: List<String> = emptyList()): String? {
        val parts = facts.toMutableList()
        credits.groupBy { it.role }.forEach { (role, cs) -> parts += "${roleKo[role]} ${cs.joinToString(", ") { it.name }}" }
        if (tags.isNotEmpty()) parts += "장르·태그 ${tags.joinToString(", ")}"
        return if (parts.isEmpty()) null else "$subject (MusicBrainz): ${parts.joinToString("; ")}."
    }

    /** MusicBrainz url 관계의 위키데이터 항목 → 언어별 Wikipedia 요약 */
    private fun wikipedia(node: JsonNode): List<ChunkOut> {
        val qid = node.path("relations").toList()
            .firstOrNull { it.path("type").asString("") == "wikidata" }
            ?.path("url")?.path("resource")?.asString("")?.substringAfterLast('/')?.takeIf { it.startsWith("Q") } ?: return emptyList()
        return sitelinks(qid, langs).mapNotNull { (lang, title) -> wiki(lang, title)?.let { ChunkOut("WIKIPEDIA", lang, it.url, "CC BY-SA 4.0", it.text) } }
    }

    private fun lastfmChunk(summary: String, tags: JsonNode, url: String): ChunkOut? {
        val tagList = (if (tags.isArray) tags.toList() else listOf(tags)).map { it.path("name").asString("") }.filter { it.isNotBlank() }.take(8)
        val text = Chunks.clean(summary)
        val body = listOfNotNull(tagList.takeIf { it.isNotEmpty() }?.let { "Last.fm 태그: ${it.joinToString(", ")}." }, text.ifEmpty { null }).joinToString(" ")
        if (body.isBlank() || url.isBlank()) return null
        return ChunkOut("LASTFM", "en", url, "CC BY-SA (Last.fm wiki)", Chunks.limit(body))
    }
}
