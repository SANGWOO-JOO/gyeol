package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ReleaseScan(val artistId: Long, val status: String, val recordings: Int?, val stored: Int?, val requestedAt: Instant, val finishedAt: Instant?)
data class ArtistRecording(val recordingMbid: UUID, val title: String, val firstReleaseDate: String?, val trackId: Long?, val coverUrl: String?)

/** I-30 ③ 가수 화면 최신순: 가수별 녹음(같은 제목은 가장 이른 날짜 하나)과 모으기 상태 (V27, 전역 데이터) */
@Repository
class ArtistReleaseRepository(private val jdbc: JdbcClient) {

    fun scan(artistId: Long): ReleaseScan? =
        jdbc.sql("SELECT artist_id, status, recordings, stored, requested_at, finished_at FROM artist_release_scan WHERE artist_id = :a")
            .param("a", artistId).query { rs, _ ->
                ReleaseScan(
                    rs.getLong(1), rs.getString(2), rs.getObject(3) as Int?, rs.getObject(4) as Int?,
                    rs.getTimestamp(5).toInstant(), rs.getTimestamp(6)?.toInstant(),
                )
            }.optional().orElse(null)

    /**
     * 모으기를 요청한다: 처음이거나, 끝난 지 [refreshBefore]보다 오래됐거나, 실패했을 때만 QUEUED로 바꾸고 true(= 큐에 보낼 것).
     * 이미 QUEUED·RUNNING이면 false (여러 사람이 동시에 열어도 한 번만)
     */
    fun requestScan(artistId: Long, refreshBefore: Instant): Boolean =
        jdbc.sql(
            """
            INSERT INTO artist_release_scan (artist_id, status) VALUES (:a, 'QUEUED')
            ON CONFLICT (artist_id) DO UPDATE SET status = 'QUEUED', requested_at = now(), finished_at = NULL
            WHERE artist_release_scan.status = 'FAILED'
               OR (artist_release_scan.status = 'DONE' AND artist_release_scan.finished_at < :before)
            RETURNING artist_id
            """.trimIndent(),
        ).param("a", artistId).param("before", Timestamp.from(refreshBefore)).query(Long::class.java).optional().isPresent

    /** QUEUED → RUNNING (worker가 시작할 때). 이미 다른 worker가 맡았으면 false */
    fun startScan(artistId: Long): Boolean =
        jdbc.sql("UPDATE artist_release_scan SET status = 'RUNNING' WHERE artist_id = :a AND status = 'QUEUED'").param("a", artistId).update() == 1

    fun finishScan(artistId: Long, ok: Boolean, recordings: Int?, stored: Int?) {
        jdbc.sql("UPDATE artist_release_scan SET status = :s, recordings = :r, stored = :n, finished_at = now() WHERE artist_id = :a")
            .param("s", if (ok) "DONE" else "FAILED").param("r", recordings).param("n", stored).param("a", artistId).update()
    }

    /**
     * 녹음 하나를 넣는다. 같은 제목(정규화)이 이미 있으면 날짜가 더 이른 쪽으로 바꾼다 (날짜 없는 것보다 있는 것이 이김).
     * 다시 모을 때도 같은 규칙이라 결과가 같다
     */
    fun upsert(artistId: Long, mbid: UUID, title: String, titleNorm: String, date: String?, isVersion: Boolean) {
        jdbc.sql(
            """
            INSERT INTO artist_recording (artist_id, recording_mbid, title, title_norm, first_release_date, is_version)
            VALUES (:a, :m, :t, :n, :d, :v)
            ON CONFLICT (artist_id, title_norm) DO UPDATE SET
                recording_mbid = EXCLUDED.recording_mbid, title = EXCLUDED.title, first_release_date = EXCLUDED.first_release_date
            WHERE artist_recording.first_release_date IS NULL AND EXCLUDED.first_release_date IS NOT NULL
               OR EXCLUDED.first_release_date < artist_recording.first_release_date
            """.trimIndent(),
        ).param("a", artistId).param("m", mbid).param("t", title).param("n", titleNorm).param("d", date).param("v", isVersion).update()
    }

    fun count(artistId: Long): Int =
        jdbc.sql("SELECT count(*) FROM artist_recording WHERE artist_id = :a").param("a", artistId).query(Int::class.java).single()

    /**
     * 최신순 한 쪽: 처음 나온 날 내림차순, 날짜 없는 곡은 맨 뒤. 판 표시 곡은 [withVersions]일 때만.
     * 우리 DB에 같은 녹음(MBID)이 있으면 곡 id·표지를 붙인다
     */
    fun latest(artistId: Long, withVersions: Boolean, limit: Int, offset: Int): List<ArtistRecording> =
        jdbc.sql(
            """
            SELECT r.recording_mbid, r.title, r.first_release_date, t.id, t.cover_url
            FROM artist_recording r LEFT JOIN track t ON t.recording_mbid = r.recording_mbid
            WHERE r.artist_id = :a AND (:v OR NOT r.is_version)
            ORDER BY r.first_release_date DESC NULLS LAST, r.title
            LIMIT :l OFFSET :o
            """.trimIndent(),
        ).param("a", artistId).param("v", withVersions).param("l", limit).param("o", offset).query { rs, _ ->
            ArtistRecording(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getObject(4) as Long?, rs.getString(5))
        }.list()

    fun artistMbid(artistId: Long): UUID? =
        jdbc.sql("SELECT mbid FROM artist WHERE id = :a").param("a", artistId).query(UUID::class.java).optional().orElse(null)
}
