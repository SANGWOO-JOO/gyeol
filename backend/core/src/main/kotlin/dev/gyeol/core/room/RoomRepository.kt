package dev.gyeol.core.room

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

enum class RoomStatus { ACTIVE, CLOSED, HIDDEN }
enum class Role { HOST, MEMBER }

data class Room(
    val id: Long, val publicId: String, val name: String, val status: RoomStatus, val mapRev: Long,
    val createdAt: Instant, val lastActiveAt: Instant, val purgeAt: Instant?,
)

data class Member(
    val id: Long, val roomId: Long, val nickname: String, val role: Role, val colorIdx: Int,
    val pinHash: String, val failCount: Int, val lockedUntil: Instant?, val createdAt: Instant, val removedAt: Instant?,
    /** 선물로 받은 캐릭터를 얼굴로 쓰는 경우 (V24) */
    val avatarCharacterId: Long? = null,
)

/** 방·참가자·기기 열쇠·감사 기록 (V21) */
@Repository
class RoomRepository(private val jdbc: JdbcClient) {

    private val roomMapper = RowMapper { rs, _ ->
        Room(
            rs.getLong("id"), rs.getString("public_id"), rs.getString("name"), RoomStatus.valueOf(rs.getString("status")),
            rs.getLong("map_rev"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_active_at").toInstant(),
            rs.getTimestamp("purge_at")?.toInstant(),
        )
    }
    private val memberMapper = RowMapper { rs, _ ->
        Member(
            rs.getLong("id"), rs.getLong("room_id"), rs.getString("nickname"), Role.valueOf(rs.getString("role")), rs.getInt("color_idx"),
            rs.getString("pin_hash"), rs.getInt("fail_count"), rs.getTimestamp("locked_until")?.toInstant(),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("removed_at")?.toInstant(),
            rs.getObject("avatar_character_id") as Long?,
        )
    }

    fun create(publicId: String, name: String): Long =
        jdbc.sql("INSERT INTO room (public_id, name) VALUES (:p, :n) RETURNING id").param("p", publicId).param("n", name)
            .query(Long::class.java).single()

    fun findByPublicId(publicId: String): Room? =
        jdbc.sql("SELECT * FROM room WHERE public_id = :p").param("p", publicId).query(roomMapper).optional().orElse(null)

    fun findById(id: Long): Room? =
        jdbc.sql("SELECT * FROM room WHERE id = :id").param("id", id).query(roomMapper).optional().orElse(null)

    /** 참가·담기처럼 인원·한도를 세는 쓰기 전에 방 행을 잠근다 (동시 참가 6명 → 5명만) */
    fun lock(id: Long): Room =
        jdbc.sql("SELECT * FROM room WHERE id = :id FOR UPDATE").param("id", id).query(roomMapper).single()

    fun bumpMapRev(id: Long) {
        jdbc.sql("UPDATE room SET map_rev = map_rev + 1, last_active_at = now() WHERE id = :id").param("id", id).update()
    }

    fun renameRoom(id: Long, name: String) = jdbc.sql("UPDATE room SET name = :n WHERE id = :id").param("id", id).param("n", name).update()

    fun setStatus(id: Long, status: RoomStatus) =
        jdbc.sql("UPDATE room SET status = :s WHERE id = :id AND status <> 'HIDDEN'").param("id", id).param("s", status.name).update()

    /**
     * 숨김(삭제): 바로 못 들어오게 하고 [purgeAt]에 완전 삭제.
     * 숨긴 시각도 같은 앱 시계로 넣는다 (DB now()와 앱 시계가 몇 ms 어긋나면 "7일 뒤"가 6일 23시간으로 보였다, 11-13 테스트)
     */
    fun hide(id: Long, hiddenAt: Instant, purgeAt: Instant) =
        jdbc.sql("UPDATE room SET status_before_hide = status, status = 'HIDDEN', hidden_at = :h, purge_at = :p WHERE id = :id AND status <> 'HIDDEN'")
            .param("id", id).param("h", Timestamp.from(hiddenAt)).param("p", Timestamp.from(purgeAt)).update()

    /** 관리자 되살리기: 숨겼고 아직 완전 삭제 전인 방만, 숨기기 전 상태로. 바뀐 행 수 */
    fun restore(id: Long, now: Instant): Int =
        jdbc.sql(
            """
            UPDATE room SET status = coalesce(status_before_hide, 'ACTIVE'), status_before_hide = NULL, hidden_at = NULL, purge_at = NULL
            WHERE id = :id AND status = 'HIDDEN' AND purge_at > :now
            """.trimIndent(),
        ).param("id", id).param("now", Timestamp.from(now)).update()

    fun saveHostEmail(roomId: Long, email: String, consentAt: Instant) {
        jdbc.sql("INSERT INTO room_host_email (room_id, email, consent_at) VALUES (:r, :e, :c)")
            .param("r", roomId).param("e", email).param("c", Timestamp.from(consentAt)).update()
    }

    fun hostEmail(roomId: Long): String? =
        jdbc.sql("SELECT email FROM room_host_email WHERE room_id = :r").param("r", roomId).query(String::class.java).optional().orElse(null)

    // ── 참가자 ──

    fun members(roomId: Long): List<Member> =
        jdbc.sql("SELECT * FROM member WHERE room_id = :r AND removed_at IS NULL ORDER BY id").param("r", roomId).query(memberMapper).list()

    fun member(id: Long): Member? =
        jdbc.sql("SELECT * FROM member WHERE id = :id").param("id", id).query(memberMapper).optional().orElse(null)

    fun nicknameTaken(roomId: Long, nickname: String, exceptMemberId: Long? = null): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM member WHERE room_id = :r AND lower(nickname) = lower(:n) AND removed_at IS NULL AND id <> :e)")
            .param("r", roomId).param("n", nickname).param("e", exceptMemberId ?: -1L).query(Boolean::class.java).single()

    fun addMember(roomId: Long, nickname: String, role: Role, colorIdx: Int, pinHash: String): Long =
        jdbc.sql("INSERT INTO member (room_id, nickname, role, color_idx, pin_hash) VALUES (:r, :n, :role, :c, :p) RETURNING id")
            .param("r", roomId).param("n", nickname).param("role", role.name).param("c", colorIdx).param("p", pinHash)
            .query(Long::class.java).single()

    fun renameMember(memberId: Long, nickname: String): Int =
        jdbc.sql("UPDATE member SET nickname = :n WHERE id = :id").param("id", memberId).param("n", nickname).update()

    /** 내보내기: 참가자 표시를 지우고 열쇠를 모두 무효로 */
    fun remove(memberId: Long) {
        jdbc.sql("UPDATE member SET removed_at = now() WHERE id = :id AND removed_at IS NULL").param("id", memberId).update()
        jdbc.sql("UPDATE member_key SET revoked_at = now() WHERE member_id = :id AND revoked_at IS NULL").param("id", memberId).update()
    }

    fun recordPinFailure(memberId: Long, maxFails: Int, lockUntil: Instant): Int =
        jdbc.sql(
            """
            UPDATE member SET fail_count = fail_count + 1,
                   locked_until = CASE WHEN fail_count + 1 >= :max THEN :until ELSE locked_until END
            WHERE id = :id RETURNING fail_count
            """.trimIndent(),
        ).param("id", memberId).param("max", maxFails).param("until", Timestamp.from(lockUntil)).query(Int::class.java).single()

    fun resetPinFailures(memberId: Long) {
        jdbc.sql("UPDATE member SET fail_count = 0, locked_until = NULL WHERE id = :id").param("id", memberId).update()
    }

    /** 새 암호: 해시를 바꾸고 틀린 횟수·잠금도 푼다. 기존 기기 열쇠는 유지 */
    fun setPin(memberId: Long, pinHash: String) {
        jdbc.sql("UPDATE member SET pin_hash = :p, fail_count = 0, locked_until = NULL WHERE id = :id").param("id", memberId).param("p", pinHash).update()
    }

    // ── 기기 열쇠 ──

    /** 이 사람의 모든 기기 열쇠 폐기 (방장 암호 재설정 뒤, I-10). 폐기한 수 */
    fun revokeKeys(memberId: Long): Int =
        jdbc.sql("UPDATE member_key SET revoked_at = now() WHERE member_id = :m AND revoked_at IS NULL").param("m", memberId).update()

    fun addKey(memberId: Long, keyHash: ByteArray) {
        jdbc.sql("INSERT INTO member_key (key_hash, member_id) VALUES (:h, :m)").param("h", keyHash).param("m", memberId).update()
    }

    /** 살아 있는 열쇠의 참가자 (내보냈거나 나간 열쇠는 없음). 쓸 때마다 last_seen_at 갱신 */
    fun memberByKey(keyHash: ByteArray): Member? =
        jdbc.sql(
            """
            UPDATE member_key k SET last_seen_at = now()
            FROM member m WHERE k.key_hash = :h AND k.revoked_at IS NULL AND m.id = k.member_id AND m.removed_at IS NULL
            RETURNING m.*
            """.trimIndent(),
        ).param("h", keyHash).query(memberMapper).optional().orElse(null)

    fun revokeKey(keyHash: ByteArray) {
        jdbc.sql("UPDATE member_key SET revoked_at = now() WHERE key_hash = :h AND revoked_at IS NULL").param("h", keyHash).update()
    }

    // ── 감사 기록 ──

    /**
     * 감사 기록. 방이 지워진 뒤에도 알아볼 수 있게 detail에 방 공개 id·이름을 함께 남긴다 (V26, 1년 보관).
     * 개인정보(ip·nickname·oldNickname·newNickname)는 detail의 이 키들에만 넣는다: 방을 완전 삭제할 때 지운다
     */
    fun audit(actorType: String, actorId: Long?, roomId: Long?, action: String, targetMemberId: Long? = null, detailJson: String? = null) {
        jdbc.sql(
            """
            INSERT INTO audit_log (actor_type, actor_id, room_id, action, target_member_id, detail)
            SELECT :t, :a, :r, :act, :m,
                   coalesce(CAST(:d AS jsonb), '{}'::jsonb)
                   || coalesce((SELECT jsonb_build_object('roomPublicId', public_id, 'roomName', name) FROM room WHERE id = :r), '{}'::jsonb)
            """.trimIndent(),
        ).param("t", actorType).param("a", actorId).param("r", roomId).param("act", action).param("m", targetMemberId).param("d", detailJson).update()
    }

    /**
     * 숨긴 지 7일이 지난 방을 완전히 지운다 (참가자·노래·이메일·선물은 FK CASCADE).
     * 감사 기록은 남기되(room_id는 NULL이 됨) 먼저 그 방 기록의 IP·닉네임을 지운다.
     * 두 문장 사이에 멈춰도 되는 순서다: 이미 되살릴 수 없는 방의 기록을 먼저 가릴 뿐이다. 지운 방 수
     */
    fun purgeHidden(now: Instant = Instant.now()): Int {
        val at = Timestamp.from(now)
        jdbc.sql(
            """
            UPDATE audit_log SET detail = detail - 'ip' - 'nickname' - 'oldNickname' - 'newNickname'
            WHERE room_id IN (SELECT id FROM room WHERE status = 'HIDDEN' AND purge_at <= :now)
            """.trimIndent(),
        ).param("now", at).update()
        return jdbc.sql("DELETE FROM room WHERE status = 'HIDDEN' AND purge_at <= :now").param("now", at).update()
    }

    /** 1년 지난 감사 기록 삭제 (정리 작업). 지운 수 */
    /**
     * I-56: 마지막 사용이 [unusedBefore]보다 오래된(1년) 숨기지 않은 방. 마지막 사용 = 참가자 기기 열쇠의 마지막 요청(입장·지도 보기·담기 등
     * 방 API를 쓸 때마다 갱신, [memberByKey])과 마지막 노래 담기, 없으면 방 만든 시각 중 가장 늦은 것.
     * room.last_active_at은 다른 방 활동으로 공용 지도를 다시 그릴 때도 올라가 쓰지 않는다. (방 id, 마지막 사용)
     */
    fun unusedRooms(unusedBefore: Instant): List<Pair<Long, Instant>> =
        jdbc.sql(
            """
            SELECT id, last_used FROM (
                SELECT r.id, greatest(
                    r.created_at,
                    coalesce((SELECT max(k.last_seen_at) FROM member m JOIN member_key k ON k.member_id = m.id WHERE m.room_id = r.id), r.created_at),
                    coalesce((SELECT max(rs.added_at) FROM room_song rs WHERE rs.room_id = r.id), r.created_at)
                ) AS last_used
                FROM room r WHERE r.status <> 'HIDDEN'
            ) x WHERE last_used < :b ORDER BY id
            """.trimIndent(),
        ).param("b", Timestamp.from(unusedBefore)).query { rs, _ -> rs.getLong(1) to rs.getTimestamp(2).toInstant() }.list()

    /**
     * I-68: [before]보다 오래된 감사 기록의 detail에서 ip 키만 지운다 (HOST_RESET_SENT·HOST_PIN_RESET·ADMIN_LOGIN_FAIL 등). 행은 1년 보관 그대로.
     * 바뀐 행 수
     */
    fun clearOldAuditIps(before: Instant): Int =
        jdbc.sql("UPDATE audit_log SET detail = detail - 'ip' WHERE created_at < :b AND detail ->> 'ip' IS NOT NULL")
            .param("b", Timestamp.from(before)).update()

    fun deleteOldAudit(before: Instant): Int =
        jdbc.sql("DELETE FROM audit_log WHERE created_at < :b").param("b", Timestamp.from(before)).update()
}
