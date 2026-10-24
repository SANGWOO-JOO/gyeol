package dev.gyeol.core.listen

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class Source { MUSIC_APP, YOUTUBE }

data class ListenEvent(
    val videoId: String?,
    val title: String,
    val channel: String?,
    val watchedAt: Instant,
    val source: Source,
)

@Repository
class ListenEventRepository(private val jdbcTemplate: JdbcTemplate) {

    /** 수만 건을 1,000건씩 묶어 넣는다 */
    fun insertBatch(sessionId: Long, uploadId: UUID, events: List<ListenEvent>, expiresAt: Instant, chunkSize: Int = 1000) {
        val expires = Timestamp.from(expiresAt)
        events.chunked(chunkSize).forEach { chunk ->
            jdbcTemplate.batchUpdate(
                """
                INSERT INTO listen_event (session_id, upload_id, video_id, title, channel, watched_at, source, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                chunk,
                chunk.size,
            ) { ps, e ->
                ps.setLong(1, sessionId)
                ps.setObject(2, uploadId)
                ps.setString(3, e.videoId)
                ps.setString(4, e.title)
                ps.setString(5, e.channel)
                ps.setTimestamp(6, Timestamp.from(e.watchedAt))
                ps.setString(7, e.source.name)
                ps.setTimestamp(8, expires)
            }
        }
    }
}
