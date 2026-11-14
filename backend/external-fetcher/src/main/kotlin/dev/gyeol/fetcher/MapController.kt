package dev.gyeol.fetcher

import dev.gyeol.fetcher.lastfm.LastFmClient
import dev.gyeol.fetcher.musicbrainz.MusicBrainzClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 음악 지도 초기 가수 모으기 (room-plan.md 8절 ②). 외부 호출은 여기서만 하고 API별 속도 제한을 함께 쓴다.
 * 1. Last.fm geo.getTopArtists: 그 나라 사용자가 많이 듣는 가수 (해외 가수가 섞임)
 * 2. MusicBrainz 가수 조회: 출신 국가로 다시 거른다
 */
@RestController
class MapController(private val lastFm: LastFmClient, private val musicBrainz: MusicBrainzClient) {

    /** country는 Last.fm 나라 이름 (South Korea, Japan, United States). 키가 없거나 결과가 없으면 빈 목록 */
    @GetMapping("/internal/lastfm/geo-top")
    fun geoTop(@RequestParam country: String, @RequestParam(defaultValue = "1") page: Int, @RequestParam(defaultValue = "100") limit: Int): Map<String, Any> {
        require(country.length in 2..60) { "country가 올바르지 않습니다" }
        require(page in 1..50 && limit in 1..100) { "page는 1~50, limit은 1~100이어야 합니다" }
        val items = lastFm.info("geo.getTopArtists", mapOf("country" to country, "page" to page.toString(), "limit" to limit.toString()))
            ?.path("topartists")?.path("artist")?.toList().orEmpty()
            .mapNotNull { a ->
                val name = a.path("name").asString("").ifEmpty { return@mapNotNull null }
                mapOf("name" to name, "mbid" to a.path("mbid").asString("").ifEmpty { null }, "listeners" to a.path("listeners").asLong(0))
            }
        return mapOf("items" to items)
    }

    /**
     * 가수 하나: mbid가 있으면 그것으로, 없으면 이름 검색 1위(점수 95 이상)만. 못 찾으면 found=false.
     * 응답: mbid, name, sortName, country(ISO 2자리, 없으면 null), aliases(한·영·일 별칭)
     */
    @GetMapping("/internal/musicbrainz/artist")
    fun artist(@RequestParam(required = false) mbid: String?, @RequestParam(required = false) name: String?): Map<String, Any?> {
        val node = when {
            !mbid.isNullOrBlank() -> {
                require(Regex("^[0-9a-f-]{36}$").matches(mbid)) { "mbid 형식이 올바르지 않습니다" }
                try { musicBrainz.fetch("/artist/$mbid", mapOf("inc" to "aliases")) } catch (e: UpstreamRejectedException) { null }
            }
            !name.isNullOrBlank() -> {
                val q = "artist:\"${musicBrainz.escape(name.trim())}\""
                val hit = musicBrainz.fetch("/artist", mapOf("query" to q, "limit" to 1)).path("artists").firstOrNull()
                    ?.takeIf { it.path("score").asInt(0) >= 95 }
                hit?.path("id")?.asString("")?.ifEmpty { null }?.let { musicBrainz.fetch("/artist/$it", mapOf("inc" to "aliases")) }
            }
            else -> throw IllegalArgumentException("mbid나 name이 필요합니다")
        } ?: return mapOf("found" to false)
        val aliases = node.path("aliases").toList().mapNotNull { it.path("name").asString("").ifEmpty { null } }.distinct().take(20)
        return mapOf(
            "found" to true,
            "mbid" to node.path("id").asString(),
            "name" to node.path("name").asString(),
            "sortName" to node.path("sort-name").asString("").ifEmpty { null },
            "country" to node.path("country").asString("").ifEmpty { null },
            "aliases" to aliases,
        )
    }

    /**
     * I-30 ③ 가수의 녹음 목록 (MusicBrainz browse, 100개씩). 녹음마다 처음 나온 날(first-release-date, 없으면 null).
     * 응답: total(전체 녹음 수), offset, items[{mbid, title, firstReleaseDate, video}]. 초당 1회 제한은 다른 MusicBrainz 호출과 함께 쓴다
     */
    @GetMapping("/internal/musicbrainz/artist-recordings")
    fun artistRecordings(@RequestParam mbid: String, @RequestParam(defaultValue = "0") offset: Int): Map<String, Any?> {
        require(Regex("^[0-9a-f-]{36}$").matches(mbid)) { "mbid 형식이 올바르지 않습니다" }
        require(offset in 0..20_000) { "offset은 0~20000이어야 합니다" }
        val body = musicBrainz.fetch("/recording", mapOf("artist" to mbid, "limit" to 100, "offset" to offset, "inc" to "artist-credits"))
        val items = body.path("recordings").toList().mapNotNull { r ->
            val title = r.path("title").asString("").trim().ifEmpty { return@mapNotNull null }
            val credits = r.path("artist-credit").toList()
            mapOf(
                "mbid" to r.path("id").asString(),
                "title" to title,
                "firstReleaseDate" to r.path("first-release-date").asString("").ifEmpty { null },
                "video" to r.path("video").asBoolean(false),
                // I-39: 길이(ms, 모르면 null), 크레딧 첫 가수(주 가수), 크레딧 전체 표기
                "lengthMs" to r.path("length").takeIf { it.isNumber }?.asLong(),
                "primaryArtistMbid" to credits.firstOrNull()?.path("artist")?.path("id")?.asString("")?.ifEmpty { null },
                "credit" to credits.joinToString("") { it.path("name").asString("") + it.path("joinphrase").asString("") },
            )
        }
        return mapOf("total" to body.path("recording-count").asInt(0), "offset" to offset, "items" to items)
    }
}
