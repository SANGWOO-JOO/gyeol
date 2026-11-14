package dev.gyeol.core.admin

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

data class AdminUser(
    val id: Long, val email: String, val passwordHash: String, val totpSecret: String, val totpLastStep: Long,
    val failedCount: Int, val lockedUntil: Instant?, val lastLoginAt: Instant?,
)

data class AdminSession(val id: Long, val adminId: Long, val stage: String, val expiresAt: Instant)

/** 관리자 방 목록 한 줄 */
data class AdminRoomRow(
    val id: Long, val publicId: String, val name: String, val status: String, val hostNickname: String?,
    val memberCount: Int, val songCount: Int, val createdAt: Instant, val lastActiveAt: Instant, val purgeAt: Instant?,
)

data class AdminMemberRow(val id: Long, val nickname: String, val role: String, val songCount: Int, val createdAt: Instant, val removedAt: Instant?)
data class AuditRow(
    val id: Long, val actorType: String, val actorId: Long?, val roomId: Long?, val action: String,
    val targetMemberId: Long?, val detailJson: String?, val createdAt: Instant,
)

/** ⑥ 관리자 (V26). 계정 행은 운영 DB에 직접 넣는다 (tools/admin-account) */
@Repository
class AdminRepository(private val jdbc: JdbcClient) {

    private val userMapper = RowMapper { rs, _ ->
        AdminUser(
            rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"), rs.getString("totp_secret"), rs.getLong("totp_last_step"),
            rs.getInt("failed_count"), rs.getTimestamp("locked_until")?.toInstant(), rs.getTimestamp("last_login_at")?.toInstant(),
        )
    }

    fun userByEmail(email: String): AdminUser? =
        jdbc.sql("SELECT * FROM admin_user WHERE email = :e").param("e", email.lowercase()).query(userMapper).optional().orElse(null)

    fun user(id: Long): AdminUser? = jdbc.sql("SELECT * FROM admin_user WHERE id = :id").param("id", id).query(userMapper).optional().orElse(null)

    /** 실패 1회 기록. [maxFails]번째면 [lockUntil]까지 잠그고 횟수를 0으로. 잠갔으면 true */
    fun recordFailure(id: Long, maxFails: Int, lockUntil: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE admin_user SET
                locked_until = CASE WHEN failed_count + 1 >= :max THEN :until ELSE locked_until END,
                failed_count = CASE WHEN failed_count + 1 >= :max THEN 0 ELSE failed_count + 1 END
            WHERE id = :id RETURNING locked_until IS NOT NULL AND locked_until = :until
            """.trimIndent(),
        ).param("id", id).param("max", maxFails).param("until", Timestamp.from(lockUntil)).query(Boolean::class.java).single()

    /** OTP 통과: 실패 횟수 초기화, 이 시간 칸을 사용함으로 표시 (같은 칸 이하의 코드는 다시 못 씀). 이미 쓴 칸이면 false */
    fun acceptOtp(id: Long, step: Long): Boolean =
        jdbc.sql("UPDATE admin_user SET totp_last_step = :s, failed_count = 0, locked_until = NULL, last_login_at = now() WHERE id = :id AND totp_last_step < :s")
            .param("id", id).param("s", step).update() == 1

    fun createSession(adminId: Long, tokenHash: ByteArray, stage: String, ip: String, expiresAt: Instant): Long =
        jdbc.sql("INSERT INTO admin_session (token_hash, admin_id, stage, ip, expires_at) VALUES (:h, :a, :s, :ip, :e) RETURNING id")
            .param("h", tokenHash).param("a", adminId).param("s", stage).param("ip", ip).param("e", Timestamp.from(expiresAt))
            .query(Long::class.java).single()

    /** 만료·폐기되지 않은 세션 */
    fun session(tokenHash: ByteArray, now: Instant): AdminSession? =
        jdbc.sql("SELECT id, admin_id, stage, expires_at FROM admin_session WHERE token_hash = :h AND revoked_at IS NULL AND expires_at > :now")
            .param("h", tokenHash).param("now", Timestamp.from(now))
            .query { rs, _ -> AdminSession(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toInstant()) }.optional().orElse(null)

    fun revoke(sessionId: Long) {
        jdbc.sql("UPDATE admin_session SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL").param("id", sessionId).update()
    }

    // ── 방 ──

    private val roomRowMapper = RowMapper { rs, _ ->
        AdminRoomRow(
            rs.getLong("id"), rs.getString("public_id"), rs.getString("name"), rs.getString("status"), rs.getString("host_nickname"),
            rs.getInt("member_count"), rs.getInt("song_count"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_active_at").toInstant(), rs.getTimestamp("purge_at")?.toInstant(),
        )
    }

    private val roomSelect = """
        SELECT r.id, r.public_id, r.name, r.status, r.created_at, r.last_active_at, r.purge_at,
               (SELECT m.nickname FROM member m WHERE m.room_id = r.id AND m.role = 'HOST' ORDER BY m.id LIMIT 1) AS host_nickname,
               (SELECT count(*) FROM member m WHERE m.room_id = r.id AND m.removed_at IS NULL) AS member_count,
               (SELECT count(*) FROM room_song s WHERE s.room_id = r.id) AS song_count
        FROM room r
    """.trimIndent()

    /** 방 이름·공개 id·방장 닉네임 검색, 상태 걸러내기. [sort]: created(기본)·active·songs, 최신·많은 순 */
    fun rooms(q: String?, status: String?, sort: String, limit: Int, offset: Int): List<AdminRoomRow> {
        val order = when (sort) {
            "active" -> "last_active_at DESC"
            "songs" -> "song_count DESC"
            else -> "created_at DESC"
        }
        return jdbc.sql(
            """
            SELECT * FROM ($roomSelect) x
            WHERE (CAST(:q AS text) IS NULL OR x.name ILIKE :like OR x.public_id = :q OR x.host_nickname ILIKE :like)
              AND (CAST(:st AS text) IS NULL OR x.status = :st)
            ORDER BY $order, id DESC LIMIT :l OFFSET :o
            """.trimIndent(),
        ).param("q", q).param("like", q?.let { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" })
            .param("st", status).param("l", limit).param("o", offset).query(roomRowMapper).list()
    }

    fun room(publicId: String): AdminRoomRow? =
        jdbc.sql("SELECT * FROM ($roomSelect) x WHERE x.public_id = :p").param("p", publicId).query(roomRowMapper).optional().orElse(null)

    fun members(roomId: Long): List<AdminMemberRow> =
        jdbc.sql(
            """
            SELECT m.id, m.nickname, m.role, (SELECT count(*) FROM room_song s WHERE s.member_id = m.id), m.created_at, m.removed_at
            FROM member m WHERE m.room_id = :r ORDER BY m.id
            """.trimIndent(),
        ).param("r", roomId).query { rs, _ ->
            AdminMemberRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6)?.toInstant())
        }.list()

    fun hostEmail(roomId: Long): String? =
        jdbc.sql("SELECT email FROM room_host_email WHERE room_id = :r").param("r", roomId).query(String::class.java).optional().orElse(null)

    /** 감사 기록 (최신 순). [roomPublicId]로 걸러내면 방이 지워진 뒤에도 detail의 공개 id로 찾는다 */
    fun audit(roomPublicId: String?, limit: Int, offset: Int): List<AuditRow> =
        jdbc.sql(
            """
            SELECT id, actor_type, actor_id, room_id, action, target_member_id, detail::text, created_at FROM audit_log
            WHERE CAST(:p AS text) IS NULL OR detail ->> 'roomPublicId' = :p
            ORDER BY created_at DESC, id DESC LIMIT :l OFFSET :o
            """.trimIndent(),
        ).param("p", roomPublicId).param("l", limit).param("o", offset).query { rs, _ ->
            AuditRow(
                rs.getLong(1), rs.getString(2), rs.getObject(3) as Long?, rs.getObject(4) as Long?, rs.getString(5),
                rs.getObject(6) as Long?, rs.getString(7), rs.getTimestamp(8).toInstant(),
            )
        }.list()

    /** I-57: [before]보다 오래전에 만든 관리자 세션의 IP를 지운다 (세션 행은 남김). 지운 행 수 */
    fun clearOldSessionIps(before: java.time.Instant): Int =
        jdbc.sql("UPDATE admin_session SET ip = NULL WHERE created_at < :b AND ip IS NOT NULL").param("b", java.sql.Timestamp.from(before)).update()
}
