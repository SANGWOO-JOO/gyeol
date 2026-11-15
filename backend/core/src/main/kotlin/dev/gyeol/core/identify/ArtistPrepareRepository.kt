package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/** 검색 때 가수 미리 준비 상태 (V32). 키는 'n:{정규화 이름}' 또는 'id:{artist_id}' */
@Repository
class ArtistPrepareRepository(private val jdbc: JdbcClient) {

    /**
     * 맡길 수 있으면 QUEUED로 두고 true. I-72부터 "하루 1번" 대신 준비할 순위가 남았는지로 정한다:
     * - 진행 중(QUEUED·RUNNING, 1시간 안)이면 false. 다만 [windowEnd]가 지금보다 넓으면 넓혀 둔다(돌던 작업이 이어서 처리)
     * - SKIPPED(동명이인 등)·FAILED는 하루 안이면 false
     * - 인기곡 목록이 있고 [windowEnd]까지 준비할 순위가 없으면 false ([needsWork])
     * - 오늘 이 가수 묶음이 [batchLimit]에 닿았으면 false, 방당 하루 [roomLimit]명을 넘으면 false
     */
    fun request(key: String, name: String, roomId: Long, day: LocalDate, roomLimit: Int, windowEnd: Int = 10, batchLimit: Int = Int.MAX_VALUE, retryDays: Int = 3): Boolean {
        val row = jdbc.sql("SELECT status, requested_at > now() - interval '1 hour', requested_at > now() - interval '1 day', window_end, CASE WHEN batches_day = :d THEN batches ELSE 0 END FROM search_artist_prep WHERE prep_key = :k")
            .param("k", key).param("d", day)
            .query { rs, _ -> listOf(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getInt(4), rs.getInt(5)) }.optional().orElse(null)
        if (row != null) {
            val (status, inHour, inDay, window, batches) = row
            if ((status == "QUEUED" || status == "RUNNING") && inHour as Boolean) {
                if (windowEnd > (window as Int)) jdbc.sql("UPDATE search_artist_prep SET window_end = :w WHERE prep_key = :k").param("w", windowEnd).param("k", key).update()
                return false
            }
            if ((status == "SKIPPED" || status == "FAILED") && inDay as Boolean) return false
            if ((batches as Int) >= batchLimit) return false
        }
        // 목록이 있고 창 안에 준비할 순위가 없으면 맡기지 않는다 (목록이 없거나 7일 지났으면 needsWork가 true)
        if (!needsWork(key, windowEnd, retryDays)) return false
        val used = jdbc.sql("SELECT coalesce((SELECT count FROM search_artist_prep_daily WHERE room_id = :r AND day = :d), 0)")
            .param("r", roomId).param("d", day).query(Int::class.java).single()
        if (used >= roomLimit) return false
        val queued = jdbc.sql(
            """
            INSERT INTO search_artist_prep (prep_key, name, status, window_end) VALUES (:k, :n, 'QUEUED', :w)
            ON CONFLICT (prep_key) DO UPDATE SET status = 'QUEUED', name = EXCLUDED.name, requested_at = now(), finished_at = NULL, reason = NULL,
                window_end = greatest(search_artist_prep.window_end, EXCLUDED.window_end)
            WHERE search_artist_prep.status NOT IN ('QUEUED', 'RUNNING') OR search_artist_prep.requested_at < now() - interval '1 hour'
            RETURNING prep_key
            """.trimIndent(),
        ).param("k", key).param("n", name).param("w", windowEnd).query(String::class.java).optional().isPresent
        if (!queued) return false
        jdbc.sql(
            """
            INSERT INTO search_artist_prep_daily (room_id, day, count) VALUES (:r, :d, 1)
            ON CONFLICT (room_id, day) DO UPDATE SET count = search_artist_prep_daily.count + 1
            """.trimIndent(),
        ).param("r", roomId).param("d", day).update()
        return true
    }

    // ── I-72 인기곡 목록·준비 커서 ──

    /** 목록이 없거나 7일 지났거나, [windowEnd]까지 아직 안 해 본(또는 [retryDays] 지난 실패·없음) 순위가 있으면 true */
    fun needsWork(key: String, windowEnd: Int, retryDays: Int = 3): Boolean =
        jdbc.sql(
            """
            SELECT NOT EXISTS (SELECT 1 FROM artist_top_track WHERE prep_key = :k AND fetched_at > now() - interval '7 days')
                OR EXISTS (SELECT 1 FROM artist_top_track WHERE prep_key = :k AND rank <= :w
                           AND (status = 'PENDING' OR (status IN ('NO_MATCH', 'FAILED', 'OTHER') AND tried_at < now() - make_interval(days => :r))))
            """.trimIndent(),
        ).param("k", key).param("w", windowEnd).param("r", retryDays).query(Boolean::class.java).single()

    data class TopTrack(val rank: Int, val title: String, val trackId: Long?, val status: String)

    /** 저장된 인기곡 목록 (7일 지난 목록도 돌려준다. 새로 받기는 worker가 한다) */
    fun topTracks(key: String): List<TopTrack> =
        jdbc.sql("SELECT rank, title, track_id, status FROM artist_top_track WHERE prep_key = :k ORDER BY rank").param("k", key)
            .query { rs, _ -> TopTrack(rs.getInt(1), rs.getString(2), rs.getObject(3) as Long?, rs.getString(4)) }.list()

    fun topFresh(key: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM artist_top_track WHERE prep_key = :k AND fetched_at > now() - interval '7 days')").param("k", key).query(Boolean::class.java).single()

    /** 새 목록으로 바꾼다. 같은 제목(정규화)이 있던 순위의 준비 상태·곡은 이어 받는다 */
    fun saveTopTracks(key: String, titles: List<String>) {
        val old = topTracks(key).associateBy { SearchText.norm(it.title) }
        jdbc.sql("DELETE FROM artist_top_track WHERE prep_key = :k").param("k", key).update()
        titles.forEachIndexed { i, t ->
            val prev = old[SearchText.norm(t)]
            // 이어 받은 상태는 시도 시각을 지금으로 둔다 (실패·없음은 며칠 뒤 다시)
            jdbc.sql("INSERT INTO artist_top_track (prep_key, rank, title, track_id, status, tried_at) VALUES (:k, :r, :t, :tr, :s, CASE WHEN :s = 'PENDING' THEN NULL ELSE now() END)")
                .param("k", key).param("r", i + 1).param("t", t).param("tr", prev?.trackId).param("s", prev?.status ?: "PENDING").update()
        }
    }

    /** 다른 키의 목록을 그대로 복사한다 (n: 키로 준비한 가수가 우리 가수가 되면 id: 키에서도 바로 쓰게) */
    fun copyTopTracks(from: String, to: String) {
        jdbc.sql("DELETE FROM artist_top_track WHERE prep_key = :to").param("to", to).update()
        jdbc.sql("INSERT INTO artist_top_track (prep_key, rank, title, track_id, status, tried_at, fetched_at) SELECT :to, rank, title, track_id, status, tried_at, fetched_at FROM artist_top_track WHERE prep_key = :from")
            .param("from", from).param("to", to).update()
    }

    /** 이번 묶음에서 준비할 순위 [n]개: [windowEnd]까지, 아직 안 했거나 [retryDays] 지난 실패·없음 */
    fun nextRanks(key: String, windowEnd: Int, n: Int, retryDays: Int = 3): List<TopTrack> =
        jdbc.sql(
            """
            SELECT rank, title, track_id, status FROM artist_top_track
            WHERE prep_key = :k AND rank <= :w AND (status = 'PENDING' OR (status IN ('NO_MATCH', 'FAILED', 'OTHER') AND tried_at < now() - make_interval(days => :r)))
            ORDER BY rank LIMIT :n
            """.trimIndent(),
        ).param("k", key).param("w", windowEnd).param("r", retryDays).param("n", n)
            .query { rs, _ -> TopTrack(rs.getInt(1), rs.getString(2), rs.getObject(3) as Long?, rs.getString(4)) }.list()

    /** 순위 상태 기록. 이미 READY(우리 곡에 이음)인 순위는 READY가 아닌 결과로 덮지 않는다 (검색이 먼저 이었을 수 있다) */
    fun markRank(key: String, rank: Int, status: String, trackId: Long?) {
        jdbc.sql("UPDATE artist_top_track SET status = :s, track_id = :t, tried_at = now() WHERE prep_key = :k AND rank = :r AND NOT (status = 'READY' AND track_id IS NOT NULL AND :s <> 'READY')")
            .param("k", key).param("r", rank).param("s", status).param("t", trackId).update()
    }

    data class PrepState(val name: String, val windowEnd: Int, val artistMbid: UUID?, val batchesToday: Int)

    fun state(key: String, day: LocalDate): PrepState? =
        jdbc.sql("SELECT name, window_end, artist_mbid, CASE WHEN batches_day = :d THEN batches ELSE 0 END FROM search_artist_prep WHERE prep_key = :k")
            .param("k", key).param("d", day)
            .query { rs, _ -> PrepState(rs.getString(1), rs.getInt(2), rs.getObject(3) as UUID?, rs.getInt(4)) }.optional().orElse(null)

    fun setArtistMbid(key: String, mbid: UUID) {
        jdbc.sql("UPDATE search_artist_prep SET artist_mbid = :m WHERE prep_key = :k").param("k", key).param("m", mbid).update()
    }

    /** 묶음 하나를 셌다 (하루 단위) */
    fun countBatch(key: String, day: LocalDate) {
        jdbc.sql("UPDATE search_artist_prep SET batches = CASE WHEN batches_day = :d THEN batches + 1 ELSE 1 END, batches_day = :d WHERE prep_key = :k")
            .param("k", key).param("d", day).update()
    }

    /** RUNNING → QUEUED (다음 묶음을 이어서 맡길 때). 바뀌었으면 true */
    fun requeue(key: String): Boolean =
        jdbc.sql("UPDATE search_artist_prep SET status = 'QUEUED', requested_at = now() WHERE prep_key = :k AND status = 'RUNNING'").param("k", key).update() == 1

    /** 검색 응답 pending: 진행 중(1시간 안)인가 */
    fun inProgress(key: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM search_artist_prep WHERE prep_key = :k AND status IN ('QUEUED', 'RUNNING') AND requested_at > now() - interval '1 hour')")
            .param("k", key).query(Boolean::class.java).single()

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
