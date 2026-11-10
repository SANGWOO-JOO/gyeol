package dev.gyeol.fetcher.musicbrainz

import tools.jackson.databind.JsonNode

data class IdentifyRequest(val artist: String?, val title: String?)

data class IdArtist(val mbid: String, val name: String, val sortName: String?, val aliases: List<String>)
/** [lengthMs]는 녹음 길이 (같은 곡의 다른 표기인지 확인할 때 쓴다). 없을 수 있다 */
data class IdRecording(val mbid: String, val title: String, val lengthMs: Long? = null)
data class IdRelease(val mbid: String, val groupMbid: String, val title: String, val date: String, val type: String?)

data class IdentifyResult(
    val status: String, // MATCHED | NOT_FOUND
    val reason: String? = null, // ARTIST | RECORDING | RELEASE
    val recording: IdRecording? = null,
    val artist: IdArtist? = null,
    val release: IdRelease? = null,
    val calls: Int = 0,
    val ms: Long = 0,
)

/**
 * (가수, 곡) → MusicBrainz 녹음과 원곡이 처음 실린 공식 음반 (docs/l1-design.md 4-3, 표본 29곡 중 28곡 일치).
 * 1. 가수 확정: 이름·정렬 이름·별칭이 정확히 같은 가수만 (동명 가수 배제)
 * 2. 녹음: 그 가수의 같은 제목(괄호·"/" 앞뒤, 로마자). 제목 검색 → Last.fm 표기 → 가수 녹음 목록 훑기
 * 3. 음반: 공식, 컴필레이션·라이브·리믹스 제외, 버전 음반(ver.·inst…)은 뒤로, 가장 이른 것
 * 외부 호출은 함수로 받아 시험에서 바꿔 끼운다.
 */
class MusicBrainzIdentifier(
    private val mb: (path: String, params: Map<String, Any>) -> JsonNode,
    private val spellings: (artist: String, title: String) -> List<String>,
) {
    private val excludedTypes = setOf("Compilation", "Live", "Remix", "DJ-mix", "Mixtape/Street", "Demo")
    private val variant = Regex("""ver\.|version|inst|remix|acoustic|mr\b|karaoke""", RegexOption.IGNORE_CASE)
    // 녹음이 원곡이 아닌 판인지: 괄호 안 표시나 MusicBrainz 설명(disambiguation)에 나온다 (예: "Yellow (The Alpha remix)").
    // "Korean version"·"Japanese ver." 같은 언어판은 원곡일 수 있어 판으로 보지 않는다 (뚜두뚜두의 SQUARE UP 녹음 설명이 "Korean version")
    private val recordingVariant = Regex("""\b(live|remix|mix|edit|inst(rumental)?|acoustic|demo|karaoke|mr|remaster(ed)?)\b""", RegexOption.IGNORE_CASE)
    private val parenthetical = Regex("""[(\[（](.*?)[)\]）]""")
    // 같은 날 나온 음반이면 대표 표지가 되는 쪽을 먼저
    private val typeOrder = mapOf("Album" to 0, "EP" to 1, "Single" to 2)
    private val luceneSpecial = Regex("""[()\[\]/:"!?~^\\\-{}+*&|]""")

    fun identify(artistName: String, title: String): IdentifyResult {
        val started = System.nanoTime()
        var calls = 0
        val call = { path: String, params: Map<String, Any> -> calls++; mb(path, params) }
        fun done(r: IdentifyResult) = r.copy(calls = calls, ms = (System.nanoTime() - started) / 1_000_000)

        // "지오디 (god)"처럼 괄호로 병기한 가수는 전체로 못 찾으면 부분으로 다시 찾는다
        val artists = findArtists(artistName, call).ifEmpty {
            TitleMatch.searchParts(artistName).filter { it != artistName }.firstNotNullOfOrNull { findArtists(it, call).ifEmpty { null } }.orEmpty()
        }

        var alt: String? = null
        var altChecked = false
        var withoutRelease: Pair<IdArtist, List<JsonNode>>? = null
        val near = mutableMapOf<IdArtist, MutableList<JsonNode>>() // 잘린 제목 후보 (마지막 수단)
        fun tryArtists(candidates: List<IdArtist>): IdentifyResult? {
            for (artist in candidates) {
                val n = near.getOrPut(artist) { mutableListOf() }
                var recs = recordings(artist.mbid, title, null, call, n)
                if (recs.isEmpty() && !altChecked) {
                    altChecked = true
                    alt = spellings(artistName, title).firstOrNull { TitleMatch.norm(it) == TitleMatch.norm(title) && it != title }
                    if (alt != null) recs = recordings(artist.mbid, title, alt, call, n)
                }
                if (recs.isEmpty()) recs = scanArtist(artist.mbid, title, call, n)
                if (recs.isEmpty()) continue

                val best = earliestRelease(recs)
                if (best == null) {
                    if (withoutRelease == null) withoutRelease = artist to recs
                    continue
                }
                return done(IdentifyResult("MATCHED", recording = best.first, artist = artist, release = best.second))
            }
            return null
        }

        tryArtists(artists)?.let { return it }
        // 흔한 이름은 필드 검색 상위 10명에 실제 가수가 없을 수 있다 ("Buzz"의 버즈). 넓은 검색으로 이름이 정확히 같은 다른 후보를 한 번 더 본다
        val wider = findArtists(artistName, call, wide = true).filter { w -> artists.none { it.mbid == w.mbid } }
        tryArtists(wider)?.let { return it }
        // 합쳐진 이름("서인국 & 정은지")은 MusicBrainz에 가수로 없다. 전체로 못 찾았을 때만 첫 사람으로 찾는다
        val lead = TitleMatch.firstOfDuet(artistName)?.let { first -> findArtists(first, call).filter { l -> (artists + wider).none { it.mbid == l.mbid } } }.orEmpty()
        tryArtists(lead)?.let { return it }
        if (artists.isEmpty() && wider.isEmpty() && lead.isEmpty()) return done(IdentifyResult("NOT_FOUND", reason = "ARTIST"))
        // 원곡 음반이 없으면(라이브·베스트뿐, 발매일 없음) 그 곡이 실린 공식 음반을 차선으로 쓴다. 공식 음반도 없으면 음반 없이 곡으로 인정한다
        withoutRelease?.let { (artist, recs) ->
            val fallback = earliestRelease(recs, relaxed = true)
            val rec = recs.minBy { isVariantRecording(it) }
            return done(IdentifyResult("MATCHED", recording = fallback?.first ?: idRecording(rec), artist = artist, release = fallback?.second))
        }
        // 마지막 수단: 잘린 제목. 한 가수에서 후보 제목이 하나로 모일 때만 인정한다
        val partial = near.entries.mapNotNull { (artist, recs) ->
            recs.distinctBy { it.path("id").asString() }.groupBy { TitleMatch.norm(it.path("title").asString("")) }.values.singleOrNull()?.let { artist to it }
        }.singleOrNull()
        if (partial != null) {
            val (artist, recs) = partial
            val best = earliestRelease(recs) ?: earliestRelease(recs, relaxed = true)
            val rec = recs.minBy { isVariantRecording(it) }
            return done(IdentifyResult("MATCHED", reason = "PARTIAL_TITLE", recording = best?.first ?: idRecording(rec), artist = artist, release = best?.second))
        }
        return done(IdentifyResult("NOT_FOUND", reason = "RECORDING"))
    }

    private fun findArtists(name: String, call: (String, Map<String, Any>) -> JsonNode, wide: Boolean = false): List<IdArtist> {
        val q = esc(name)
        val query = if (wide) name.replace(luceneSpecial, " ").trim() else "artist:\"$q\" OR alias:\"$q\""
        if (query.isEmpty()) return emptyList()
        val body = call("/artist", mapOf("query" to query, "limit" to 10))
        val wanted = TitleMatch.norm(name)
        val wantedWords = TitleMatch.nameWords(name)
        return body.path("artists").toList()
            .filter { a ->
                val names = listOf(a.path("name").asString(""), a.path("sort-name").asString("")) + a.path("aliases").toList().map { it.path("name").asString("") }
                names.any { TitleMatch.norm(it) == wanted || (wantedWords.size > 1 && TitleMatch.nameWords(it) == wantedWords) }
            }
            .sortedByDescending { it.path("score").asInt(0) }
            .take(3)
            .map { a ->
                IdArtist(
                    mbid = a.path("id").asString(),
                    name = a.path("name").asString(),
                    sortName = a.path("sort-name").asString("").ifEmpty { null },
                    aliases = a.path("aliases").toList().map { it.path("name").asString("") }.filter { it.isNotBlank() }.distinct().take(20),
                )
            }
    }

    private fun recordings(arid: String, title: String, alt: String?, call: (String, Map<String, Any>) -> JsonNode, near: MutableList<JsonNode>): List<JsonNode> {
        val terms = TitleMatch.searchParts(alt ?: title).map { it.replace(luceneSpecial, " ").trim() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val searchTitle = terms.singleOrNull() ?: terms.joinToString(" OR ") { "($it)" }
        val out = mutableListOf<JsonNode>()
        for (offset in 0 until 300 step 100) {
            val body = call("/recording", mapOf("query" to "arid:$arid AND recording:($searchTitle)", "limit" to 100, "offset" to offset))
            val page = body.path("recordings").toList()
            out += page.filter { TitleMatch.sameTitle(it.path("title").asString(""), title) }
            near += page.filter { TitleMatch.partialTitle(it.path("title").asString(""), title) }
            if (page.size < 100) break
        }
        return out
    }

    /** 띄어쓰기·표기 차이로 제목 검색에 안 걸릴 때: 가수의 녹음을 넓게 훑는다 (MusicBrainz 검색은 offset + limit이 500을 넘으면 400) */
    private fun scanArtist(arid: String, title: String, call: (String, Map<String, Any>) -> JsonNode, near: MutableList<JsonNode>): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        for (offset in 0 until 500 step 100) {
            val page = call("/recording", mapOf("query" to "arid:$arid", "limit" to 100, "offset" to offset)).path("recordings").toList()
            out += page.filter { TitleMatch.sameTitle(it.path("title").asString(""), title) }
            near += page.filter { TitleMatch.partialTitle(it.path("title").asString(""), title) }
            if (page.size < 100) break
        }
        return out
    }

    /** [relaxed]면 라이브·컴필레이션과 발매일 없는 음반도 공식이면 허용한다 (발매일 있는 것을 먼저) */
    private fun earliestRelease(recs: List<JsonNode>, relaxed: Boolean = false): Pair<IdRecording, IdRelease>? =
        recs.flatMap { rec -> rec.path("releases").toList().map { rec to it } }
            .filter { (_, r) ->
                r.path("status").asString("") == "Official" && (relaxed || (
                    r.path("date").asString("").isNotEmpty() &&
                        r.path("release-group").path("secondary-types").toList().none { it.asString("") in excludedTypes }
                    ))
            }
            .sortedWith(
                compareBy(
                    { isVariantRecording(it.first) },
                    // 차선에서는 라이브 음반의 녹음을 맨 뒤로 (길이·편곡이 원곡과 달라 다른 표기 비교가 틀어진다)
                    { r -> r.second.path("release-group").path("secondary-types").toList().any { it.asString("") == "Live" } },
                    { r -> r.second.path("date").asString("").isEmpty() },
                    { variant.containsMatchIn(it.second.path("title").asString("")) },
                    { it.second.path("date").asString("").padEnd(10, '9') },
                    { typeOrder[it.second.path("release-group").path("primary-type").asString("")] ?: 3 },
                ),
            )
            .firstOrNull()
            ?.let { (rec, r) ->
                idRecording(rec) to IdRelease(
                    mbid = r.path("id").asString(),
                    groupMbid = r.path("release-group").path("id").asString(),
                    title = r.path("title").asString(),
                    date = r.path("date").asString(),
                    type = r.path("release-group").path("primary-type").asString("").ifEmpty { null },
                )
            }

    private fun idRecording(rec: JsonNode) =
        IdRecording(rec.path("id").asString(), rec.path("title").asString(), rec.path("length").takeIf { it.isNumber }?.asLong())

    private fun isVariantRecording(rec: JsonNode): Boolean {
        val title = rec.path("title").asString("")
        val marks = parenthetical.findAll(title).map { it.groupValues[1] } + rec.path("disambiguation").asString("")
        return marks.any { recordingVariant.containsMatchIn(it) }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
