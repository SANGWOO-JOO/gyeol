package dev.gyeol.core.session

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class SessionRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Session(
            id = rs.getLong("id"),
            entry = Entry.valueOf(rs.getString("entry")),
            status = SessionStatus.valueOf(rs.getString("status")),
            totalCount = rs.getObject("total_count") as Int?,
            musicCount = rs.getObject("music_count") as Int?,
            unclassifiedCount = rs.getObject("unclassified_count") as Int?,
            savedCount = rs.getObject("saved_count") as Int?,
            periodStart = rs.getTimestamp("period_start")?.toInstant(),
            periodEnd = rs.getTimestamp("period_end")?.toInstant(),
            errorMessage = rs.getString("error_message"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
        )
    }

    fun create(tokenHash: ByteArray, entry: Entry, expiresAt: Instant): Long =
        jdbc.sql("INSERT INTO session (token_hash, entry, expires_at) VALUES (:hash, :entry, :expiresAt) RETURNING id")
            .param("hash", tokenHash)
            .param("entry", entry.name)
            .param("expiresAt", Timestamp.from(expiresAt))
            .query(Long::class.java)
            .single()

    /** 만료된 세션은 없는 것과 같게 본다 */
    fun findActiveByTokenHash(tokenHash: ByteArray, now: Instant = Instant.now()): Session? =
        jdbc.sql("SELECT * FROM session WHERE token_hash = :hash AND expires_at > :now")
            .param("hash", tokenHash)
            .param("now", Timestamp.from(now))
            .query(mapper)
            .optional()
            .orElse(null)

    fun findById(id: Long): Session? =
        jdbc.sql("SELECT * FROM session WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    /** 상태가 기대한 값일 때만 바꾼다. 바뀐 행이 없으면 false (동시 요청·잘못된 순서 방지) */
    fun transition(id: Long, from: SessionStatus, to: SessionStatus): Boolean =
        jdbc.sql("UPDATE session SET status = :to, updated_at = now() WHERE id = :id AND status = :from")
            .param("id", id).param("from", from.name).param("to", to.name)
            .update() == 1

    fun markUploading(id: Long, totalCount: Int, musicCount: Int, unclassifiedCount: Int): Boolean =
        jdbc.sql(
            """
            UPDATE session SET status = 'UPLOADING', total_count = :total, music_count = :music,
                   unclassified_count = :unclassified, updated_at = now()
            WHERE id = :id AND status = 'CREATED'
            """.trimIndent(),
        )
            .param("id", id).param("total", totalCount).param("music", musicCount).param("unclassified", unclassifiedCount)
            .update() == 1

    fun markDone(id: Long, savedCount: Int, periodStart: Instant?, periodEnd: Instant?) {
        jdbc.sql(
            """
            UPDATE session SET status = 'DONE', saved_count = :saved, period_start = :start, period_end = :end,
                   error_message = NULL, updated_at = now()
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", id).param("saved", savedCount)
            .param("start", periodStart?.let(Timestamp::from))
            .param("end", periodEnd?.let(Timestamp::from))
            .update()
    }

    fun markFailed(id: Long, message: String) {
        jdbc.sql("UPDATE session SET status = 'FAILED', error_message = :msg, updated_at = now() WHERE id = :id")
            .param("id", id).param("msg", message.take(500))
            .update()
    }

    fun delete(id: Long) {
        jdbc.sql("DELETE FROM session WHERE id = :id").param("id", id).update()
    }

    /**
     * 만료된 세션 삭제 (업로드·청취 기록·고른 곡은 연쇄 삭제).
     * S3 원본을 아직 못 지운 세션은 남겨 두어, 파일이 고아로 남지 않게 한다
     */
    fun deleteExpired(now: Instant, limit: Int): Int =
        jdbc.sql(
            """
            DELETE FROM session WHERE id IN (
                SELECT s.id FROM session s
                WHERE s.expires_at < :now
                  AND NOT EXISTS (SELECT 1 FROM upload u WHERE u.session_id = s.id AND u.file_deleted_at IS NULL)
                ORDER BY s.id LIMIT :limit
            )
            """.trimIndent(),
        ).param("now", Timestamp.from(now)).param("limit", limit).update()

    fun insertSeeds(sessionId: Long, seeds: List<SeedTrack>) {
        seeds.forEach { s ->
            jdbc.sql(
                "INSERT INTO seed_track (session_id, mbid, title, artist, position) VALUES (:sid, :mbid, :title, :artist, :pos)",
            )
                .param("sid", sessionId).param("mbid", s.mbid).param("title", s.title)
                .param("artist", s.artist).param("pos", s.position)
                .update()
        }
    }

    fun findSeeds(sessionId: Long): List<SeedTrack> =
        jdbc.sql("SELECT mbid, title, artist, position FROM seed_track WHERE session_id = :sid ORDER BY position")
            .param("sid", sessionId)
            .query { rs, _ ->
                SeedTrack(rs.getObject("mbid", UUID::class.java) as UUID?, rs.getString("title"), rs.getString("artist"), rs.getInt("position"))
            }
            .list()
}
