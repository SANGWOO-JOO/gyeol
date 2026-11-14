package dev.gyeol.core.room

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 방 지도에 그릴 곡 하나: 담은 사람들(담은 순서) */
data class MapSong(
    val trackId: Long, val title: String, val artistId: Long, val artistName: String, val artistMbid: UUID,
    val coverUrl: String?, val memberIds: List<Long>,
    /** 표지 S3 저장본이 있으면 true (I-14, 방 표지 주소로 내려 준다) */
    val coverStored: Boolean = false,
)

data class MySong(
    val id: Long, val trackId: Long, val title: String, val artistId: Long, val artistName: String,
    val coverUrl: String?, val addedAt: Instant, val sharedWith: Int,
    val coverStored: Boolean = false,
)

data class SongRequest(
    val id: Long, val roomId: Long, val memberId: Long, val title: String, val artist: String, val mbid: UUID?,
    val status: String, val trackId: Long?, val roomSongId: Long?, val failReason: String?,
    /** I-51: 이 요청으로 담긴 곡이 만든 선물 상자와 그때 상태 (없으면 null) */
    val giftBoxId: Long? = null, val giftStatus: String? = null,
)

/** 방 노래·식별 요청·하루 한도 (V22) */
@Repository
class RoomSongRepository(private val jdbc: JdbcClient) {

    fun trackArtist(trackId: Long): Long? =
        jdbc.sql("SELECT artist_id FROM track WHERE id = :t").param("t", trackId).query(Long::class.java).optional().orElse(null)

    /** 담기. 이미 담은 곡이면 null */
    fun add(roomId: Long, memberId: Long, trackId: Long, artistId: Long): Long? =
        jdbc.sql(
            """
            INSERT INTO room_song (room_id, member_id, track_id, artist_id) VALUES (:r, :m, :t, :a)
            ON CONFLICT (room_id, member_id, track_id) DO NOTHING RETURNING id
            """.trimIndent(),
        ).param("r", roomId).param("m", memberId).param("t", trackId).param("a", artistId).query(Long::class.java).optional().orElse(null)

    fun has(roomId: Long, memberId: Long, trackId: Long): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM room_song WHERE room_id = :r AND member_id = :m AND track_id = :t)")
            .param("r", roomId).param("m", memberId).param("t", trackId).query(Boolean::class.java).single()

    /** 이 방에서 이 곡을 담은 사람 수 (2 이상이면 금빛 위성) */
    fun sharedCount(roomId: Long, trackId: Long): Int =
        jdbc.sql("SELECT count(*) FROM room_song WHERE room_id = :r AND track_id = :t").param("r", roomId).param("t", trackId).query(Int::class.java).single()

    /** 내 곡 빼기. 지운 곡의 track_id (내 곡이 아니면 null) */
    fun remove(songId: Long, memberId: Long): Long? =
        jdbc.sql("DELETE FROM room_song WHERE id = :id AND member_id = :m RETURNING track_id").param("id", songId).param("m", memberId)
            .query(Long::class.java).optional().orElse(null)

    fun removeAllOf(memberId: Long): Int = jdbc.sql("DELETE FROM room_song WHERE member_id = :m").param("m", memberId).update()

    fun countOf(memberId: Long): Int =
        jdbc.sql("SELECT count(*) FROM room_song WHERE member_id = :m").param("m", memberId).query(Int::class.java).single()

    fun countsByMember(roomId: Long): Map<Long, Int> =
        jdbc.sql("SELECT member_id, count(*) FROM room_song WHERE room_id = :r GROUP BY member_id").param("r", roomId)
            .query { rs, _ -> rs.getLong(1) to rs.getInt(2) }.list().toMap()

    fun mapSongs(roomId: Long): List<MapSong> =
        jdbc.sql(
            """
            SELECT rs.track_id, t.title, a.id, a.name, a.mbid, t.cover_url, array_agg(rs.member_id ORDER BY rs.added_at, rs.id),
                   t.cover_key IS NOT NULL
            FROM room_song rs JOIN track t ON t.id = rs.track_id JOIN artist a ON a.id = rs.artist_id
            WHERE rs.room_id = :r
            GROUP BY rs.track_id, t.title, a.id, a.name, a.mbid, t.cover_url, t.cover_key
            ORDER BY min(rs.added_at)
            """.trimIndent(),
        ).param("r", roomId).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            MapSong(
                rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getObject(5) as UUID, rs.getString(6),
                (rs.getArray(7).array as Array<Long>).toList(), rs.getBoolean(8),
            )
        }.list()

    /** 내 노래 (최근 담은 순, 커서는 그 전 페이지 마지막 id) */
    fun mySongs(memberId: Long, beforeId: Long?, limit: Int): List<MySong> =
        jdbc.sql(
            """
            SELECT rs.id, rs.track_id, t.title, a.id, a.name, t.cover_url, rs.added_at,
                   (SELECT count(*) FROM room_song o WHERE o.room_id = rs.room_id AND o.track_id = rs.track_id AND o.member_id <> rs.member_id),
                   t.cover_key IS NOT NULL
            FROM room_song rs JOIN track t ON t.id = rs.track_id JOIN artist a ON a.id = rs.artist_id
            WHERE rs.member_id = :m AND rs.id < :before
            ORDER BY rs.id DESC LIMIT :limit
            """.trimIndent(),
        ).param("m", memberId).param("before", beforeId ?: Long.MAX_VALUE).param("limit", limit)
            .query { rs, _ -> MySong(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getInt(8), rs.getBoolean(9)) }
            .list()

    // ── 표지 저장본 (I-14) ──

    /** 표지 S3 키. 저장본이 없으면 null */
    fun coverKey(trackId: Long): String? =
        jdbc.sql("SELECT cover_key FROM track WHERE id = :t AND cover_key IS NOT NULL").param("t", trackId).query(String::class.java).optional().orElse(null)

    /** 가수 곡 중 표지 저장본(S3)이 있는 대표 곡 하나: 재생 수가 많은 곡, 같으면 id가 작은 곡. 없으면 null (방 검색 가수 카드 대체 얼굴) */
    fun artistStoredCoverTrack(artistId: Long): Long? =
        jdbc.sql("SELECT id FROM track WHERE artist_id = :a AND cover_key IS NOT NULL ORDER BY plays_total DESC, id LIMIT 1")
            .param("a", artistId).query(Long::class.java).optional().orElse(null)

    /** 이 방 곡 중 표지 저장본이 있는 곡 수 (지도 ETag용, I-14) */
    fun storedCoverCount(roomId: Long): Int =
        jdbc.sql("SELECT count(DISTINCT rs.track_id) FROM room_song rs JOIN track t ON t.id = rs.track_id WHERE rs.room_id = :r AND t.cover_key IS NOT NULL")
            .param("r", roomId).query(Int::class.java).single()

    /** 이 곡들 중 표지 저장본이 있는 곡 */
    fun storedCovers(trackIds: Collection<Long>): Set<Long> =
        if (trackIds.isEmpty()) emptySet() else
            jdbc.sql("SELECT id FROM track WHERE id = ANY (:ids) AND cover_key IS NOT NULL").param("ids", trackIds.toTypedArray()).query { rs, _ -> rs.getLong(1) }.set()

    // ── 검색 결과에 친구 표시 ──

    /** 이 방에서 이 곡들을 담은 사람 (track_id → 참가자 id들) */
    /** 표지를 아직 한 번도 확인하지 않은 곡인가 (I-31) */
    fun coverUnchecked(trackId: Long): Boolean =
        jdbc.sql("SELECT cover_checked_at IS NULL FROM track WHERE id = :id").param("id", trackId).query(Boolean::class.java).optional().orElse(false)

    fun whoHasTracks(roomId: Long, trackIds: Collection<Long>): Map<Long, List<Long>> =
        if (trackIds.isEmpty()) emptyMap() else
            jdbc.sql("SELECT track_id, array_agg(member_id ORDER BY added_at) FROM room_song WHERE room_id = :r AND track_id = ANY (:ids) GROUP BY track_id")
                .param("r", roomId).param("ids", trackIds.toTypedArray())
                .query { rs, _ -> @Suppress("UNCHECKED_CAST") (rs.getLong(1) to (rs.getArray(2).array as Array<Long>).toList()) }.list().toMap()

    /** 이 방에 담긴 가수 이름(별칭 포함, 소문자) → 담은 사람 id들. 검색 결과(Last.fm)는 id가 없어 이름으로 맞춘다 */
    fun roomArtistNames(roomId: Long): Map<String, Set<Long>> {
        val rows = jdbc.sql(
            """
            SELECT lower(n), rs.member_id FROM room_song rs JOIN artist a ON a.id = rs.artist_id,
                   unnest(array_append(a.aliases, a.name)) n
            WHERE rs.room_id = :r
            """.trimIndent(),
        ).param("r", roomId).query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list()
        return rows.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    // ── 식별 요청 ──

    fun createRequest(roomId: Long, memberId: Long, title: String, artist: String, mbid: UUID?): Long =
        jdbc.sql("INSERT INTO song_request (room_id, member_id, title, artist, mbid) VALUES (:r, :m, :t, :a, :mb) RETURNING id")
            .param("r", roomId).param("m", memberId).param("t", title).param("a", artist).param("mb", mbid).query(Long::class.java).single()

    fun request(id: Long): SongRequest? =
        jdbc.sql("SELECT * FROM song_request WHERE id = :id").param("id", id).query { rs, _ ->
            SongRequest(
                rs.getLong("id"), rs.getLong("room_id"), rs.getLong("member_id"), rs.getString("title"), rs.getString("artist"),
                rs.getObject("mbid") as UUID?, rs.getString("status"), rs.getObject("track_id") as Long?, rs.getObject("room_song_id") as Long?,
                rs.getString("fail_reason"), rs.getObject("gift_box_id") as Long?, rs.getString("gift_status"),
            )
        }.optional().orElse(null)

    /** I-51: 식별로 담은 곡이 상자를 만들었으면 요청에 남긴다 (요청 결과 응답의 gift) */
    fun setRequestGift(id: Long, boxId: Long, status: String) {
        jdbc.sql("UPDATE song_request SET gift_box_id = :b, gift_status = :s WHERE id = :id").param("b", boxId).param("s", status).param("id", id).update()
    }

    fun pendingRequests(memberId: Long): Int =
        jdbc.sql("SELECT count(*) FROM song_request WHERE member_id = :m AND status = 'IDENTIFYING'").param("m", memberId).query(Int::class.java).single()

    /** 상태가 IDENTIFYING일 때만 끝낸다 (같은 메시지를 두 번 받아도 한 번만). 바뀌었으면 true */
    fun finishRequest(id: Long, status: String, trackId: Long?, roomSongId: Long?, failReason: String?): Boolean =
        jdbc.sql(
            """
            UPDATE song_request SET status = :s, track_id = :t, room_song_id = :rs, fail_reason = :f, updated_at = now()
            WHERE id = :id AND status = 'IDENTIFYING'
            """.trimIndent(),
        ).param("id", id).param("s", status).param("t", trackId).param("rs", roomSongId).param("f", failReason).update() == 1

    // ── 하루 한도 ──

    /** 오늘 담은 수를 하나 올린다. 이미 [limit]이면 올리지 않고 null */
    fun incrementDaily(memberId: Long, day: LocalDate, limit: Int): Int? =
        jdbc.sql(
            """
            INSERT INTO member_daily (member_id, day, songs_added) VALUES (:m, :d, 1)
            ON CONFLICT (member_id, day) DO UPDATE SET songs_added = member_daily.songs_added + 1
            WHERE member_daily.songs_added < :limit
            RETURNING songs_added
            """.trimIndent(),
        ).param("m", memberId).param("d", day).param("limit", limit).query(Int::class.java).optional().orElse(null)

    /** 찾지 못한 곡은 한도에서 뺀다 */
    fun decrementDaily(memberId: Long, day: LocalDate) {
        jdbc.sql("UPDATE member_daily SET songs_added = songs_added - 1 WHERE member_id = :m AND day = :d AND songs_added > 0")
            .param("m", memberId).param("d", day).update()
    }

    fun daily(memberId: Long, day: LocalDate): Int =
        jdbc.sql("SELECT songs_added FROM member_daily WHERE member_id = :m AND day = :d").param("m", memberId).param("d", day)
            .query(Int::class.java).optional().orElse(0)

    fun requestDay(id: Long): Instant =
        jdbc.sql("SELECT created_at FROM song_request WHERE id = :id").param("id", id).query(Timestamp::class.java).single().toInstant()
}
