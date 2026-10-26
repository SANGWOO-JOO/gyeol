package dev.gyeol.fetcher.coverart

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** L2-2 앨범 표지 (cover-worker가 부른다). 장애면 503 → worker가 다시 시도 */
@RestController
class CoverArtController(private val client: CoverArtClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/internal/coverart/front")
    fun front(@RequestBody req: CoverRequest): CoverResult {
        val r = client.front(req)
        log.info("표지 {}: {} ({}ms)", r.from ?: "없음", req.releaseTitle, r.ms)
        return r
    }
}
