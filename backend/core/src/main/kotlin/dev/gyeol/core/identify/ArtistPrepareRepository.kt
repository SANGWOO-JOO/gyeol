package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/** 검색 때 가수 미리 준비 상태 (V32). 키는 'n:{정규화 이름}' 또는 'id:{artist_id}' */
@Repository
class ArtistPrepareRepository(private val jdbc: JdbcClient) {

    /**
     * 맡길 수 있으면 QUEUED로 두고 true. 같은 키가 진행 중(1시간 안)이거나 하루 안에 맡겨졌으면 false.
     * 방당 하루 [roomLimit]명을 넘으면 false (키도 바꾸지 않는다). 동시에 불러도 한 번만 true가 되게 행 잠금·조건부 갱신을 쓴다
     */
    fun request(key: String, name: String, roomId: Long, day: LocalDate, roomLimit: Int): Boolean {
        val used = jdbc.sql("SELECT coalesce((SELECT count FROM search_artist_prep_daily WHERE room_id = :r AND day = :d), 0)")
            .param("r", roomId).param("d", day).query(Int::class.java).single()
        if (used >= roomLimit) return false
        val queued = jdbc.sql(
            """
            INSERT INTO search_artist_prep (prep_key, name, status) VALUES (:k, :n, 'QUEUED')
            ON CONFLICT (prep_key) DO UPDATE SET status = 'QUEUED', name = EXCLUDED.name, requested_at = now(), finished_at = NULL, reason = NULL
            WHERE (search_artist_prep.status IN ('QUEUED', 'RUNNING') AND search_artist_prep.requested_at < now() - interval '1 hour')
               OR (search_artist_prep.status NOT IN ('QUEUED', 'RUNNING') AND search_artist_prep.requested_at < now() - interval '1 day')
            RETURNING prep_key
            """.trimIndent(),
        ).param("k", key).param("n", name).query(String::class.java).optional().isPresent
        if (!queued) return false
        jdbc.sql(
            """
            INSERT INTO search_artist_prep_daily (room_id, day, count) VALUES (:r, :d, 1)
            ON CONFLICT (room_id, day) DO UPDATE SET count = search_artist_prep_daily.count + 1
            """.trimIndent(),
        ).param("r", roomId).param("d", day).update()
        return true
    }

    /** QUEUED → RUNNING. 이미 다른 메시지가 맡았거나 끝났으면 false (같은 메시지를 두 번 받아도 한 번만) */
    fun start(key: String): Boolean =
        jdbc.sql("UPDATE search_artist_prep SET status = 'RUNNING' WHERE prep_key = :k AND status = 'QUEUED'").param("k", key).update() == 1

    fun finish(key: String, status: String, artistId: Long?, reason: String?) {
        jdbc.sql("UPDATE search_artist_prep SET status = :s, artist_id = :a, reason = :r, finished_at = now() WHERE prep_key = :k")
            .param("k", key).param("s", status).param("a", artistId).param("r", reason?.take(32)).update()
    }

    fun status(key: String): String? =
        jdbc.sql("SELECT status FROM search_artist_prep WHERE prep_key = :k").param("k", key).query(String::class.java).optional().orElse(null)

    /** 곡의 가수 mbid (식별된 곡이 확정한 가수의 곡인지 본다) */
    fun trackArtistMbid(trackId: Long): UUID? =
        jdbc.sql("SELECT a.mbid FROM track t JOIN artist a ON a.id = t.artist_id WHERE t.id = :t").param("t", trackId)
            .query(UUID::class.java).optional().orElse(null)

    fun artistMbid(artistId: Long): UUID? =
        jdbc.sql("SELECT mbid FROM artist WHERE id = :a").param("a", artistId).query(UUID::class.java).optional().orElse(null)

    fun trackArtistId(trackId: Long): Long? =
        jdbc.sql("SELECT artist_id FROM track WHERE id = :t").param("t", trackId).query(Long::class.java).optional().orElse(null)

    /** 표지를 확인한 적 없는 그 가수 곡 (재생 수 많은 곡부터) */
    fun uncheckedCoverTracks(artistId: Long, limit: Int): List<Long> =
        jdbc.sql("SELECT id FROM track WHERE artist_id = :a AND cover_checked_at IS NULL ORDER BY plays_total DESC, id LIMIT :n")
            .param("a", artistId).param("n", limit).query { rs, _ -> rs.getLong(1) }.list()

    /** 사진을 다시 찾아볼 가수인가: 확인한 적 없거나, COMMONS가 아니고 [retryDays]일이 지났다 */
    fun needsPhoto(artistId: Long, retryDays: Long): Boolean =
        jdbc.sql(
            """
            SELECT NOT EXISTS (SELECT 1 FROM artist_image WHERE artist_id = :a
                               AND (source = 'COMMONS' OR checked_at > now() - make_interval(days => :d)))
            """.trimIndent(),
        ).param("a", artistId).param("d", retryDays.toInt()).query(Boolean::class.java).single()
}
