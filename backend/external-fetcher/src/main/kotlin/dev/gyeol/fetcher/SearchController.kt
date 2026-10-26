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

    private fun validate(q: String, limit: Int): Pair<String, Int> {
        val query = q.trim()
        require(query.length in 2..100) { "검색어는 2~100자여야 합니다" }
        require(limit in 1..20) { "limit은 1~20이어야 합니다" }
        return query to limit
    }
}
