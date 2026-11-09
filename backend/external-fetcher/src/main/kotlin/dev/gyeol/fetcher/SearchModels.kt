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

open class UpstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 외부 API가 요청 자체를 거절했다 (4xx). 같은 요청을 다시 보내도 결과가 같으므로 재시도하지 않는다 */
class UpstreamRejectedException(message: String, cause: Throwable? = null) : UpstreamException(message, cause)
