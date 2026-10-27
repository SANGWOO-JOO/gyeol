package dev.gyeol.fetcher.knowledge

import dev.gyeol.fetcher.UpstreamException
import dev.gyeol.fetcher.lastfm.LastFmClient
import dev.gyeol.fetcher.musicbrainz.MusicBrainzClient
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientException

/** L3-1 지식 인입 (enrich-worker가 부른다). 외부 장애는 503 → worker가 다시 시도 */
@RestController
class KnowledgeController(musicBrainz: MusicBrainzClient, wiki: WikiClient, lastFm: LastFmClient) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val service = KnowledgeService(musicBrainz::fetch, wiki::sitelinks, wiki::summary, lastFm::info)
    private val uuid = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")

    @GetMapping("/internal/knowledge/{type}/{mbid}")
    fun knowledge(@PathVariable type: String, @PathVariable mbid: String, @RequestParam(required = false) artist: String?, @RequestParam(required = false) title: String?): KnowledgeResult {
        require(uuid.matches(mbid)) { "mbid 형식이 올바르지 않습니다" }
        val started = System.nanoTime()
        val r = try {
            when (type) {
                "recording" -> service.recording(mbid, artist, title)
                "work" -> service.work(mbid)
                "release-group" -> service.releaseGroup(mbid, artist, title)
                "artist" -> service.artist(mbid, artist)
                else -> throw IllegalArgumentException("알 수 없는 대상: $type")
            }
        } catch (e: RestClientException) {
            throw UpstreamException("지식 출처에 연결하지 못했습니다: ${e.javaClass.simpleName}", e)
        }
        log.info("지식 {} {}: 청크 {}개 ({}), 크레딧 {}개, {}ms", type, mbid, r.chunks.size, r.chunks.joinToString(",") { "${it.source}/${it.lang}" }, r.credits.size, (System.nanoTime() - started) / 1_000_000)
        return r
    }
}
