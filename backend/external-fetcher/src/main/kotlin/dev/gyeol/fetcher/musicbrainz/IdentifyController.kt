package dev.gyeol.fetcher.musicbrainz

import dev.gyeol.fetcher.lastfm.LastFmClient
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * L2 곡 식별 (identify-worker가 부른다). 곡 하나에 MusicBrainz 호출 2~15번, 수 초~수십 초가 걸린다.
 * 바쁘거나 장애면 503 (SearchController의 예외 처리와 같음) → worker가 큐로 다시 시도한다.
 */
@RestController
class IdentifyController(musicBrainz: MusicBrainzClient, lastFm: LastFmClient) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val identifier = MusicBrainzIdentifier(musicBrainz::fetch, lastFm::spellings)

    @PostMapping("/internal/musicbrainz/identify")
    fun identify(@RequestBody req: IdentifyRequest): IdentifyResult {
        val artist = req.artist?.trim().orEmpty()
        val title = req.title?.trim().orEmpty()
        require(artist.length in 1..200 && title.length in 1..300) { "artist는 1~200자, title은 1~300자여야 합니다" }
        val r = identifier.identify(artist, title)
        log.info("식별 {}: {} - {} → {} {} (호출 {}번, {}ms)", r.status, artist, title, r.release?.title ?: r.reason, r.release?.date ?: "", r.calls, r.ms)
        return r
    }
}
