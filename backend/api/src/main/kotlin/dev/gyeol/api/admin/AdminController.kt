package dev.gyeol.api.admin

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.room.IpRateLimiter
import dev.gyeol.api.room.RoomInput
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.admin.AdminMemberRow
import dev.gyeol.core.admin.AdminRepository
import dev.gyeol.core.admin.AdminRoomRow
import dev.gyeol.core.admin.AdminSongRow
import dev.gyeol.core.admin.AuditRow
import dev.gyeol.core.room.Role
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.RoomSongRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

data class AdminLoginRequest(val email: String?, val password: String?)
data class AdminChallenge(val challenge: String, val expiresAt: Instant)
data class AdminOtpRequest(val challenge: String?, val code: String?)
data class AdminMe(val id: Long, val email: String, val sessionExpiresAt: Instant, val lastLoginAt: Instant?)

data class AdminRoomPage(val items: List<AdminRoomRow>, val page: Int, val size: Int)
data class AdminRoomDetail(
    val room: AdminRoomRow, val hostEmail: String?, val members: List<AdminMemberRow>, val recentSongs: List<AdminSongRow>, val audit: List<AuditView>,
)
data class AuditView(val id: Long, val actorType: String, val actorId: Long?, val roomId: Long?, val action: String, val targetMemberId: Long?, val detail: Any?, val createdAt: Instant)
data class AdminReason(val reason: String?)
data class AdminRoomPatch(val name: String?)
data class AdminMemberPatch(val nickname: String?)

/**
 * ⑥ 관리자 (room-plan.md 10절, room-api-db.md 3절·12절, 11-13 결정).
 * 로그인 두 단계: 이메일·비밀번호 → 5분짜리 challenge → TOTP → 1시간 쿠키. 비밀번호·OTP 실패를 합쳐 5번이면 15분 잠금 + IP 분당 제한.
 * 모든 관리 동작은 audit_log(ADMIN, 관리자 id, detail에 ip·사유·바뀐 닉네임)에 남는다.
 */
@Service
class AdminService(
    private val repo: AdminRepository,
    private val rooms: RoomRepository,
    private val songs: RoomSongRepository,
    private val limiter: IpRateLimiter,
    private val props: GyeolProperties,
    private val json: JsonMapper,
) {
    private val a get() = props.admin
    private val encoder = BCryptPasswordEncoder(12)
    /** 없는 이메일에도 같은 시간이 걸리게 비교할 해시 */
    private val dummyHash = encoder.encode("not-a-real-password")!!
    private val cipher: TotpCipher? by lazy { a.totpKey.takeIf { it.isNotBlank() }?.let(::TotpCipher) }

    private fun cipher() = cipher ?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_DISABLED", "관리자 로그인이 설정되지 않았어요")
    private fun failed() = ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_LOGIN_FAILED", "이메일·비밀번호 또는 인증 코드가 맞지 않아요")
    private fun locked() = ApiException(HttpStatus.TOO_MANY_REQUESTS, "ADMIN_LOCKED", "로그인을 여러 번 실패해 잠시 잠겼어요. ${a.lockFor.toMinutes()}분 뒤 다시 시도해 주세요")

    /**
     * 1단계: 비밀번호. 맞으면 OTP를 기다리는 challenge (쿠키 아님).
     * 트랜잭션으로 묶지 않는다: 실패 횟수·감사 기록이 예외(401)와 함께 되돌려지면 안 된다
     */
    fun login(body: AdminLoginRequest, req: HttpServletRequest): AdminChallenge {
        cipher()
        val ip = AdminIp.of(req)
        limiter.check("admin:$ip", a.loginPerMinute)
        val now = Instant.now()
        val user = body.email?.trim()?.lowercase()?.let(repo::userByEmail)
        val ok = encoder.matches(body.password ?: "", user?.passwordHash ?: dummyHash)
        if (user == null) {
            rooms.audit("ADMIN", null, null, "ADMIN_LOGIN_FAIL", detailJson = json.writeValueAsString(mapOf("ip" to ip)))
            throw failed()
        }
        if (user.lockedUntil?.isAfter(now) == true) throw locked()
        if (!ok) {
            rooms.audit("ADMIN", user.id, null, "ADMIN_LOGIN_FAIL", detailJson = json.writeValueAsString(mapOf("ip" to ip, "step" to "password")))
            if (repo.recordFailure(user.id, a.maxFails, now.plus(a.lockFor))) throw locked()
            throw failed()
        }
        val token = AdminTokens.newToken()
        val expires = now.plus(a.challengeTtl)
        repo.createSession(user.id, AdminTokens.hash(token), "PENDING_OTP", ip, expires)
        return AdminChallenge(token, expires)
    }

    /** 2단계: OTP. 맞으면 challenge를 버리고 1시간 세션 쿠키 (login과 같은 이유로 트랜잭션 없음) */
    fun otp(body: AdminOtpRequest, req: HttpServletRequest): Pair<String, AdminMe> {
        val cipher = cipher()
        val ip = AdminIp.of(req)
        limiter.check("admin:$ip", a.loginPerMinute)
        val now = Instant.now()
        val pending = body.challenge?.let { repo.session(AdminTokens.hash(it), now) }?.takeIf { it.stage == "PENDING_OTP" } ?: throw failed()
        val user = repo.user(pending.adminId) ?: throw failed()
        if (user.lockedUntil?.isAfter(now) == true) { repo.revoke(pending.id); throw locked() }
        val step = Totp.verify(Totp.base32Decode(cipher.decrypt(user.totpSecret)), body.code, now)
        if (step == null || !repo.acceptOtp(user.id, step)) {
            rooms.audit("ADMIN", user.id, null, "ADMIN_LOGIN_FAIL", detailJson = json.writeValueAsString(mapOf("ip" to ip, "step" to "otp")))
            if (repo.recordFailure(user.id, a.maxFails, now.plus(a.lockFor))) { repo.revoke(pending.id); throw locked() }
            throw failed()
        }
        repo.revoke(pending.id)
        val token = AdminTokens.newToken()
        val expires = now.plus(a.sessionTtl)
        repo.createSession(user.id, AdminTokens.hash(token), "ACTIVE", ip, expires)
        rooms.audit("ADMIN", user.id, null, "ADMIN_LOGIN", detailJson = json.writeValueAsString(mapOf("ip" to ip)))
        return AdminTokens.cookie(token, a.sessionTtl.seconds, a.secureCookie) to AdminMe(user.id, user.email, expires, now)
    }

    fun logout(ctx: AdminContext): String {
        repo.revoke(ctx.session.id)
        return AdminTokens.cookie("", 0, a.secureCookie)
    }

    fun me(ctx: AdminContext) = AdminMe(ctx.admin.id, ctx.admin.email, ctx.session.expiresAt, ctx.admin.lastLoginAt)

    fun rooms(q: String?, status: String?, sort: String?, page: Int?, size: Int?): AdminRoomPage {
        val st = status?.uppercase()?.takeIf { it in setOf("ACTIVE", "CLOSED", "HIDDEN") }
        val s = (size ?: 30).coerceIn(1, 100)
        val p = (page ?: 0).coerceAtLeast(0)
        return AdminRoomPage(repo.rooms(q?.trim()?.takeIf { it.isNotEmpty() }, st, sort ?: "created", s, p * s), p, s)
    }

    fun room(publicId: String): AdminRoomDetail {
        val r = repo.room(publicId) ?: throw ApiException.roomNotFound()
        return AdminRoomDetail(
            r, repo.hostEmail(r.id)?.let(::maskEmail), repo.members(r.id), repo.recentSongs(r.id, 50),
            repo.audit(r.publicId, 50, 0).map(::auditView),
        )
    }

    fun audit(roomPublicId: String?, page: Int?, size: Int?): List<AuditView> {
        val s = (size ?: 50).coerceIn(1, 200)
        return repo.audit(roomPublicId?.takeIf { it.isNotBlank() }, s, (page ?: 0).coerceAtLeast(0) * s).map(::auditView)
    }

    @Transactional
    fun hide(ctx: AdminContext, publicId: String, body: AdminReason?) {
        val r = rooms.findByPublicId(publicId) ?: throw ApiException.roomNotFound()
        val now = Instant.now()
        if (rooms.hide(r.id, now, now.plus(props.room.purgeAfter)) == 0) throw ApiException.conflict("ALREADY_HIDDEN", "이미 숨긴 방이에요")
        rooms.audit("ADMIN", ctx.admin.id, r.id, "ADMIN_ROOM_HIDE", detailJson = detail(ctx, "reason" to body?.reason?.take(200)))
    }

    @Transactional
    fun restore(ctx: AdminContext, publicId: String) {
        val r = rooms.findByPublicId(publicId) ?: throw ApiException.roomNotFound()
        if (rooms.restore(r.id, Instant.now()) == 0) throw ApiException.conflict("RESTORE_NOT_ALLOWED", "숨긴 방이 아니거나 이미 완전 삭제할 때가 지났어요")
        rooms.audit("ADMIN", ctx.admin.id, r.id, "ADMIN_ROOM_RESTORE", detailJson = detail(ctx))
    }

    @Transactional
    fun renameRoom(ctx: AdminContext, publicId: String, body: AdminRoomPatch) {
        val r = rooms.findByPublicId(publicId) ?: throw ApiException.roomNotFound()
        val name = RoomInput.name(body.name)
        rooms.renameRoom(r.id, name)
        // 이름을 바꾸기 전 기록에도 새 이름이 아니라 그때 이름이 남도록 바꾸기 전 이름을 함께 적는다
        rooms.audit("ADMIN", ctx.admin.id, r.id, "ADMIN_ROOM_RENAME", detailJson = detail(ctx, "oldName" to r.name))
    }

    @Transactional
    fun renameMember(ctx: AdminContext, publicId: String, memberId: Long, body: AdminMemberPatch) {
        val r = rooms.findByPublicId(publicId) ?: throw ApiException.roomNotFound()
        val m = rooms.member(memberId)?.takeIf { it.roomId == r.id && it.removedAt == null } ?: throw memberNotFound()
        val nickname = RoomInput.nickname(body.nickname)
        if (rooms.nicknameTaken(r.id, nickname, m.id)) throw ApiException.conflict("NICKNAME_TAKEN", "이 우주에 같은 닉네임이 있어요")
        rooms.renameMember(m.id, nickname)
        rooms.bumpMapRev(r.id)
        rooms.audit("ADMIN", ctx.admin.id, r.id, "ADMIN_MEMBER_RENAME", m.id, detail(ctx, "oldNickname" to m.nickname, "newNickname" to nickname))
    }

    /** 방장이 내보내는 것과 같은 처리: 노래 삭제, 열쇠 폐기, 지도 rev. 방장은 내보낼 수 없다 */
    @Transactional
    fun kick(ctx: AdminContext, publicId: String, memberId: Long, body: AdminReason?) {
        val r = rooms.findByPublicId(publicId) ?: throw ApiException.roomNotFound()
        val m = rooms.member(memberId)?.takeIf { it.roomId == r.id && it.removedAt == null } ?: throw memberNotFound()
        if (m.role == Role.HOST) throw ApiException.conflict("CANNOT_KICK_HOST", "방장은 내보낼 수 없어요. 방을 숨겨 주세요")
        val removed = songs.removeAllOf(m.id)
        rooms.remove(m.id)
        rooms.bumpMapRev(r.id)
        rooms.audit("ADMIN", ctx.admin.id, r.id, "ADMIN_MEMBER_KICK", m.id, detail(ctx, "nickname" to m.nickname, "songs" to removed, "reason" to body?.reason?.take(200)))
    }

    private fun memberNotFound() = ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "이 우주에 없는 사람이에요")

    private fun detail(ctx: AdminContext, vararg kv: Pair<String, Any?>): String =
        json.writeValueAsString(mapOf("ip" to ctx.ip) + kv.filter { it.second != null })

    private fun auditView(r: AuditRow) =
        AuditView(r.id, r.actorType, r.actorId, r.roomId, r.action, r.targetMemberId, r.detailJson?.let { json.readTree(it) }, r.createdAt)

    companion object {
        /** abcd@gmail.com → a***@g***.com */
        fun maskEmail(email: String): String {
            val local = email.substringBefore('@')
            val domain = email.substringAfter('@', "")
            val host = domain.substringBefore('.')
            val tld = domain.substringAfter('.', "")
            return "${local.take(1)}***@${host.take(1)}***" + if (tld.isNotEmpty()) ".$tld" else ""
        }
    }
}

@RestController
class AdminController(private val service: AdminService) {
    @PostMapping("/api/admin/login")
    fun login(@RequestBody body: AdminLoginRequest, req: HttpServletRequest): AdminChallenge = service.login(body, req)

    @PostMapping("/api/admin/login/otp")
    fun otp(@RequestBody body: AdminOtpRequest, req: HttpServletRequest): ResponseEntity<AdminMe> {
        val (cookie, me) = service.otp(body, req)
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie).body(me)
    }

    @PostMapping("/api/admin/logout")
    fun logout(@AdminOnly ctx: AdminContext): ResponseEntity<Void> =
        ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, service.logout(ctx)).build()

    @GetMapping("/api/admin/me")
    fun me(@AdminOnly ctx: AdminContext): AdminMe = service.me(ctx)

    @GetMapping("/api/admin/rooms")
    fun rooms(
        @AdminOnly ctx: AdminContext, @RequestParam q: String?, @RequestParam status: String?, @RequestParam sort: String?,
        @RequestParam page: Int?, @RequestParam size: Int?,
    ): AdminRoomPage = service.rooms(q, status, sort, page, size)

    @GetMapping("/api/admin/rooms/{rid}")
    fun room(@AdminOnly ctx: AdminContext, @PathVariable rid: String): AdminRoomDetail = service.room(rid)

    @PostMapping("/api/admin/rooms/{rid}/hide")
    fun hide(@AdminOnly ctx: AdminContext, @PathVariable rid: String, @RequestBody(required = false) body: AdminReason?): ResponseEntity<Void> {
        service.hide(ctx, rid, body); return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/admin/rooms/{rid}/restore")
    fun restore(@AdminOnly ctx: AdminContext, @PathVariable rid: String): ResponseEntity<Void> {
        service.restore(ctx, rid); return ResponseEntity.noContent().build()
    }

    @PatchMapping("/api/admin/rooms/{rid}")
    fun renameRoom(@AdminOnly ctx: AdminContext, @PathVariable rid: String, @RequestBody body: AdminRoomPatch): ResponseEntity<Void> {
        service.renameRoom(ctx, rid, body); return ResponseEntity.noContent().build()
    }

    @PatchMapping("/api/admin/rooms/{rid}/members/{mid}")
    fun renameMember(@AdminOnly ctx: AdminContext, @PathVariable rid: String, @PathVariable mid: Long, @RequestBody body: AdminMemberPatch): ResponseEntity<Void> {
        service.renameMember(ctx, rid, mid, body); return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/api/admin/rooms/{rid}/members/{mid}")
    fun kick(@AdminOnly ctx: AdminContext, @PathVariable rid: String, @PathVariable mid: Long, @RequestBody(required = false) body: AdminReason?): ResponseEntity<Void> {
        service.kick(ctx, rid, mid, body); return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/admin/audit")
    fun audit(@AdminOnly ctx: AdminContext, @RequestParam room: String?, @RequestParam page: Int?, @RequestParam size: Int?): List<AuditView> =
        service.audit(room, page, size)
}
