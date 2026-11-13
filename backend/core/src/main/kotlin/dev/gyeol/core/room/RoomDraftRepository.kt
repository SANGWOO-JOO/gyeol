package dev.gyeol.core.room

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

data class RoomDraft(
    val id: Long, val name: String, val nickname: String, val pinHash: String, val email: String,
    val consentAt: Instant, val expiresAt: Instant,
)

data class EmailCode(val id: Long, val codeHash: String, val attempts: Int, val expiresAt: Instant, val lastSentAt: Instant, val usedAt: Instant?)

/** 방 만들기 인증 전 보관, 메일 코드, 발송 기록 (V21) */
@Repository
class RoomDraftRepository(private val jdbc: JdbcClient) {

    fun create(tokenHash: ByteArray, name: String, nickname: String, pinHash: String, email: String, consentAt: Instant, expiresAt: Instant): Long =
        jdbc.sql(
            """
            INSERT INTO room_draft (token_hash, name, nickname, pin_hash, email, consent_at, expires_at)
            VALUES (:h, :name, :nick, :pin, :email, :consent, :exp) RETURNING id
            """.trimIndent(),
        ).param("h", tokenHash).param("name", name).param("nick", nickname).param("pin", pinHash).param("email", email)
            .param("consent", Timestamp.from(consentAt)).param("exp", Timestamp.from(expiresAt)).query(Long::class.java).single()

    fun findActive(tokenHash: ByteArray, now: Instant = Instant.now()): RoomDraft? =
        jdbc.sql("SELECT * FROM room_draft WHERE token_hash = :h AND expires_at > :now").param("h", tokenHash).param("now", Timestamp.from(now))
            .query { rs, _ ->
                RoomDraft(
                    rs.getLong("id"), rs.getString("name"), rs.getString("nickname"), rs.getString("pin_hash"), rs.getString("email"),
                    rs.getTimestamp("consent_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                )
            }.optional().orElse(null)

    fun delete(id: Long) = jdbc.sql("DELETE FROM room_draft WHERE id = :id").param("id", id).update()

    fun deleteExpired(now: Instant = Instant.now()): Int =
        jdbc.sql("DELETE FROM room_draft WHERE expires_at <= :now").param("now", Timestamp.from(now)).update()

    // ── 메일 코드 ──

    /** 새 코드를 만들면 같은 대상의 이전 코드는 쓸 수 없게 한다 */
    fun newCode(purpose: String, draftId: Long?, roomId: Long?, codeHash: String, expiresAt: Instant): Long {
        jdbc.sql("UPDATE email_code SET used_at = now() WHERE used_at IS NULL AND purpose = :p AND (draft_id = :d OR room_id = :r)")
            .param("p", purpose).param("d", draftId ?: -1L).param("r", roomId ?: -1L).update()
        return jdbc.sql("INSERT INTO email_code (purpose, draft_id, room_id, code_hash, expires_at) VALUES (:p, :d, :r, :h, :e) RETURNING id")
            .param("p", purpose).param("d", draftId).param("r", roomId).param("h", codeHash).param("e", Timestamp.from(expiresAt))
            .query(Long::class.java).single()
    }

    fun latestCode(purpose: String, draftId: Long?, roomId: Long?): EmailCode? =
        jdbc.sql(
            """
            SELECT id, code_hash, attempts, expires_at, last_sent_at, used_at FROM email_code
            WHERE purpose = :p AND (draft_id = :d OR room_id = :r) ORDER BY id DESC LIMIT 1
            """.trimIndent(),
        ).param("p", purpose).param("d", draftId ?: -1L).param("r", roomId ?: -1L)
            .query { rs, _ ->
                EmailCode(
                    rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(),
                    rs.getTimestamp(6)?.toInstant(),
                )
            }.optional().orElse(null)

    fun addAttempt(codeId: Long) = jdbc.sql("UPDATE email_code SET attempts = attempts + 1 WHERE id = :id").param("id", codeId).update()

    fun useCode(codeId: Long) = jdbc.sql("UPDATE email_code SET used_at = now() WHERE id = :id AND used_at IS NULL").param("id", codeId).update()

    // ── 발송 기록 ──

    fun logSend(emailHash: ByteArray, ip: String?) {
        jdbc.sql("INSERT INTO mail_send_log (email_hash, ip) VALUES (:e, :ip)").param("e", emailHash).param("ip", ip).update()
    }

    fun sendsByEmail(emailHash: ByteArray, since: Instant): Int =
        jdbc.sql("SELECT count(*) FROM mail_send_log WHERE email_hash = :e AND sent_at > :s").param("e", emailHash).param("s", Timestamp.from(since))
            .query(Int::class.java).single()

    fun sendsByIp(ip: String, since: Instant): Int =
        jdbc.sql("SELECT count(*) FROM mail_send_log WHERE ip = :ip AND sent_at > :s").param("ip", ip).param("s", Timestamp.from(since))
            .query(Int::class.java).single()
}
