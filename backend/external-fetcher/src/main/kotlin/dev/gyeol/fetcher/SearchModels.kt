package dev.gyeol.fetcher

/** 곡 검색 결과 한 줄. 출처마다 비어 있는 칸이 다르다 (Last.fm은 mbid가 자주 비고, MusicBrainz는 청취자 수가 없음) */
data class TrackHit(
    val title: String,
    val artist: String,
    val mbid: String? = null,
    val releaseYear: Int? = null,
    val listeners: Long? = null,
)

data class SearchResult(val items: List<TrackHit>, val source: String, val cached: Boolean)

class UpstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
