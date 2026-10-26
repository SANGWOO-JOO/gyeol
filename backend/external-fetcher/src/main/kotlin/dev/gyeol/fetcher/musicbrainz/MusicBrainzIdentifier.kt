package dev.gyeol.fetcher.musicbrainz

import tools.jackson.databind.JsonNode

data class IdentifyRequest(val artist: String?, val title: String?)

data class IdArtist(val mbid: String, val name: String, val sortName: String?, val aliases: List<String>)
data class IdRecording(val mbid: String, val title: String)
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

        val artists = findArtists(artistName, call)
        if (artists.isEmpty()) return done(IdentifyResult("NOT_FOUND", reason = "ARTIST"))

        var alt: String? = null
        var altChecked = false
        var sawRecording = false
        for (artist in artists) {
            var recs = recordings(artist.mbid, title, null, call)
            if (recs.isEmpty() && !altChecked) {
                altChecked = true
                alt = spellings(artistName, title).firstOrNull { TitleMatch.norm(it) == TitleMatch.norm(title) && it != title }
                if (alt != null) recs = recordings(artist.mbid, title, alt, call)
            }
            if (recs.isEmpty()) recs = scanArtist(artist.mbid, title, call)
            if (recs.isEmpty()) continue
            sawRecording = true

            val best = earliestRelease(recs) ?: continue
            return done(IdentifyResult("MATCHED", recording = best.first, artist = artist, release = best.second))
        }
        return done(IdentifyResult("NOT_FOUND", reason = if (sawRecording) "RELEASE" else "RECORDING"))
    }

    private fun findArtists(name: String, call: (String, Map<String, Any>) -> JsonNode): List<IdArtist> {
        val q = esc(name)
        val body = call("/artist", mapOf("query" to "artist:\"$q\" OR alias:\"$q\"", "limit" to 10))
        val wanted = TitleMatch.norm(name)
        return body.path("artists").toList()
            .filter { a ->
                val names = listOf(a.path("name").asString(""), a.path("sort-name").asString("")) + a.path("aliases").toList().map { it.path("name").asString("") }
                names.any { TitleMatch.norm(it) == wanted }
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

    private fun recordings(arid: String, title: String, alt: String?, call: (String, Map<String, Any>) -> JsonNode): List<JsonNode> {
        val searchTitle = (alt ?: title).replace(luceneSpecial, " ").trim()
        if (searchTitle.isEmpty()) return emptyList()
        val out = mutableListOf<JsonNode>()
        for (offset in 0 until 300 step 100) {
            val body = call("/recording", mapOf("query" to "arid:$arid AND recording:($searchTitle)", "limit" to 100, "offset" to offset))
            val page = body.path("recordings").toList()
            out += page.filter { TitleMatch.sameTitle(it.path("title").asString(""), title) }
            if (page.size < 100) break
        }
        return out
    }

    /** 띄어쓰기·표기 차이로 제목 검색에 안 걸릴 때: 가수의 녹음을 넓게 훑는다 (최대 1,000개) */
    private fun scanArtist(arid: String, title: String, call: (String, Map<String, Any>) -> JsonNode): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        for (offset in 0 until 1000 step 100) {
            val page = call("/recording", mapOf("query" to "arid:$arid", "limit" to 100, "offset" to offset)).path("recordings").toList()
            out += page.filter { TitleMatch.sameTitle(it.path("title").asString(""), title) }
            if (page.size < 100) break
        }
        return out
    }

    private fun earliestRelease(recs: List<JsonNode>): Pair<IdRecording, IdRelease>? =
        recs.flatMap { rec -> rec.path("releases").toList().map { rec to it } }
            .filter { (_, r) ->
                r.path("status").asString("") == "Official" && r.path("date").asString("").isNotEmpty() &&
                    r.path("release-group").path("secondary-types").toList().none { it.asString("") in excludedTypes }
            }
            .sortedWith(
                compareBy(
                    { isVariantRecording(it.first) },
                    { variant.containsMatchIn(it.second.path("title").asString("")) },
                    { it.second.path("date").asString("").padEnd(10, '9') },
                    { typeOrder[it.second.path("release-group").path("primary-type").asString("")] ?: 3 },
                ),
            )
            .firstOrNull()
            ?.let { (rec, r) ->
                IdRecording(rec.path("id").asString(), rec.path("title").asString()) to IdRelease(
                    mbid = r.path("id").asString(),
                    groupMbid = r.path("release-group").path("id").asString(),
                    title = r.path("title").asString(),
                    date = r.path("date").asString(),
                    type = r.path("release-group").path("primary-type").asString("").ifEmpty { null },
                )
            }

    private fun isVariantRecording(rec: JsonNode): Boolean {
        val title = rec.path("title").asString("")
        val marks = parenthetical.findAll(title).map { it.groupValues[1] } + rec.path("disambiguation").asString("")
        return marks.any { recordingVariant.containsMatchIn(it) }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
