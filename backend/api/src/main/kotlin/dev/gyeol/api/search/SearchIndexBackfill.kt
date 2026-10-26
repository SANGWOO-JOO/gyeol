package dev.gyeol.api.search

import dev.gyeol.core.identify.TrackSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** 검색 색인이 생기기 전에 저장된 곡을 api 시작 때 한 번 색인한다 (이미 있으면 할 일이 없다) */
@Component
class SearchIndexBackfill(private val search: TrackSearchRepository) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val ids = search.unindexedTrackIds()
        if (ids.isEmpty()) return
        ids.forEach(search::reindex)
        log.info("검색 색인 채움: {}곡", ids.size)
    }
}
