package dev.gyeol.fetcher

import dev.gyeol.fetcher.lastfm.LastFmClient
import dev.gyeol.fetcher.musicbrainz.MusicBrainzClient
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

data class FetcherError(val code: String, val message: String)

/** 클러스터 안에서 api만 부르는 내부 API. 외부에는 열지 않는다. */
@RestControllerAdvice
class FetcherErrors {
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) =
        ResponseEntity.badRequest().body(FetcherError("VALIDATION", e.message ?: "잘못된 요청"))

    @ExceptionHandler(UpstreamRejectedException::class)
    fun rejected(e: UpstreamRejectedException) =
        ResponseEntity.status(422).body(FetcherError("UPSTREAM_REJECTED", e.message ?: "외부 API가 요청을 거절했습니다"))

    @ExceptionHandler(TooBusyException::class, UpstreamException::class)
    fun unavailable(e: RuntimeException) =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(FetcherError("UPSTREAM_UNAVAILABLE", e.message ?: "외부 API를 쓸 수 없습니다"))
}

@RestController
class SearchController(
    private val lastFm: LastFmClient,
    private val musicBrainz: MusicBrainzClient,
) {
    /** A 입구 즉시 검색 (Last.fm + 다시 정렬) */
    @GetMapping("/internal/lastfm/search")
    fun lastFm(@RequestParam q: String, @RequestParam(defaultValue = "10") limit: Int): SearchResult {
        val (query, n) = validate(q, limit)
        return lastFm.search(query, n)
    }

    /** L2 곡 식별용 (응답이 느려 즉시 검색에는 쓰지 않음) */
    @GetMapping("/internal/musicbrainz/search")
    fun musicBrainz(@RequestParam q: String, @RequestParam(defaultValue = "10") limit: Int): SearchResult {
        val (query, n) = validate(q, limit)
        return musicBrainz.search(query, n)
    }

    /** L3-4 추천 후보: Last.fm track.getSimilar (match는 0~1 유사도). 키가 없거나 결과가 없으면 빈 목록 */
    @GetMapping("/internal/lastfm/similar")
    fun similar(@RequestParam artist: String, @RequestParam track: String, @RequestParam(defaultValue = "20") limit: Int): Map<String, Any> {
        require(limit in 1..50) { "limit은 1~50이어야 합니다" }
        val body = lastFm.info("track.getSimilar", mapOf("artist" to artist, "track" to track, "limit" to limit.toString()))
        val items = body?.path("similartracks")?.path("track")?.toList().orEmpty().mapNotNull { t ->
            val name = t.path("name").asString("").ifEmpty { return@mapNotNull null }
            val a = t.path("artist").path("name").asString("").ifEmpty { return@mapNotNull null }
            mapOf("title" to name, "artist" to a, "mbid" to t.path("mbid").asString("").ifEmpty { null }, "match" to t.path("match").asDouble(0.0))
        }
        return mapOf("items" to items)
    }

    /** L3-4 추천 후보: 비슷한 가수 (artist.getSimilar). 한국 곡은 track.getSimilar가 비어 있어 이 경로가 주가 된다 */
    @GetMapping("/internal/lastfm/similar-artists")
    fun similarArtists(@RequestParam artist: String, @RequestParam(defaultValue = "10") limit: Int): Map<String, Any> {
        require(limit in 1..50) { "limit은 1~50이어야 합니다" }
        val items = lastFm.info("artist.getSimilar", mapOf("artist" to artist, "limit" to limit.toString()))
            ?.path("similarartists")?.path("artist")?.toList().orEmpty()
            .mapNotNull { a -> a.path("name").asString("").ifEmpty { null }?.let { mapOf("artist" to it, "match" to a.path("match").asDouble(0.0)) } }
        return mapOf("items" to items)
    }

    /**
     * 가수의 인기곡 (artist.getTopTracks), 순위 순. L3-4 추천 후보와 I-30 가수 화면(인기순)이 쓴다.
     * page는 1부터. 응답의 total은 Last.fm이 아는 전체 곡 수(없으면 0)
     */
    @GetMapping("/internal/lastfm/top-tracks")
    fun topTracks(
        @RequestParam artist: String, @RequestParam(defaultValue = "3") limit: Int, @RequestParam(defaultValue = "1") page: Int,
    ): Map<String, Any> {
        require(limit in 1..50) { "limit은 1~50이어야 합니다" }
        require(page in 1..20) { "page는 1~20이어야 합니다" }
        val top = lastFm.info("artist.getTopTracks", mapOf("artist" to artist, "limit" to limit.toString(), "page" to page.toString()))?.path("toptracks")
        val items = top?.path("track")?.toList().orEmpty().mapNotNull { t ->
            t.path("name").asString("").ifEmpty { null }?.let {
                mapOf(
                    "title" to it, "artist" to t.path("artist").path("name").asString(artist),
                    "mbid" to t.path("mbid").asString("").ifEmpty { null },
                    "playcount" to t.path("playcount").asString("").toLongOrNull(),
                    "listeners" to t.path("listeners").asString("").toLongOrNull(),
                )
            }
        }
        return mapOf("items" to items, "total" to (top?.path("@attr")?.path("total")?.asString("")?.toLongOrNull() ?: 0L))
    }

    private fun validate(q: String, limit: Int): Pair<String, Int> {
        val query = q.trim()
        require(query.length in 2..100) { "검색어는 2~100자여야 합니다" }
        require(limit in 1..20) { "limit은 1~20이어야 합니다" }
        return query to limit
    }
}
