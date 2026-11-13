package dev.gyeol.api.room

import dev.gyeol.api.search.TrackItem
import dev.gyeol.api.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/** I-30 ③ 최신순 (V27·worker 수집). ③에서 채운다 */
@Service
class ArtistLatestService {
    /** (곡과 발매일, 수집 상태, 다음 쪽 있음) */
    fun page(artistId: Long, artistName: String, page: Int, size: Int): Triple<List<Pair<TrackItem, String?>>, String, Boolean> =
        throw ApiException(HttpStatus.NOT_IMPLEMENTED, "LATEST_NOT_READY", "최신순은 준비 중이에요")
}
