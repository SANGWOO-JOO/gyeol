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
}
