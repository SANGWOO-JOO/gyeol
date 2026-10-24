package dev.gyeol.core.upload

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

enum class UploadStatus { PENDING, PROCESSED, FAILED }

data class Upload(val id: UUID, val sessionId: Long, val s3Key: String, val status: UploadStatus)

@Repository
class UploadRepository(private val jdbc: JdbcClient) {

    fun create(id: UUID, sessionId: Long, s3Key: String) {
        jdbc.sql("INSERT INTO upload (id, session_id, s3_key) VALUES (:id, :sid, :key)")
            .param("id", id).param("sid", sessionId).param("key", s3Key)
            .update()
    }

    fun findByS3Key(s3Key: String): Upload? =
        jdbc.sql("SELECT id, session_id, s3_key, status FROM upload WHERE s3_key = :key")
            .param("key", s3Key)
            .query { rs, _ ->
                Upload(rs.getObject("id", UUID::class.java), rs.getLong("session_id"), rs.getString("s3_key"), UploadStatus.valueOf(rs.getString("status")))
            }
            .optional().orElse(null)

    fun keysOfSession(sessionId: Long): List<String> =
        jdbc.sql("SELECT s3_key FROM upload WHERE session_id = :sid").param("sid", sessionId)
            .query { rs, _ -> rs.getString("s3_key") }.list()

    /** PENDING일 때만 PROCESSED로 바꾼다. 이미 처리된 업로드면 false (같은 메시지 두 번 방지) */
    fun markProcessed(id: UUID, itemCount: Int): Boolean =
        jdbc.sql("UPDATE upload SET status = 'PROCESSED', item_count = :n, processed_at = now() WHERE id = :id AND status = 'PENDING'")
            .param("id", id).param("n", itemCount)
            .update() == 1

    fun markFailed(id: UUID) {
        jdbc.sql("UPDATE upload SET status = 'FAILED', processed_at = now() WHERE id = :id").param("id", id).update()
    }
}
