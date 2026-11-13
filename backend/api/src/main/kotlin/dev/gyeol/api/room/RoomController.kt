package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.GiftSummary
import dev.gyeol.core.room.Member
import dev.gyeol.core.room.Role
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.RoomSongRepository
import dev.gyeol.core.room.RoomStatus
import dev.gyeol.core.session.Tokens
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 초대 화면용 공개 요약: 링크만 받은 사람에게는 이것만 보인다 (11-13 결정 1) */
data class RoomSummary(val roomId: String, val name: String, val status: String, val capacity: Int, val members: List<MemberView>)

data class JoinRequest(val nickname: String?, val pin: String?)
data class RejoinRequest(val memberId: Long?, val pin: String?)
data class RenameRequest(val nickname: String?)
data class RoomPatch(val name: String?, val closed: Boolean?)

data class MeView(
    val roomId: String, val roomName: String, val roomStatus: String, val member: MemberView,
    val songCount: Int, val todayAdded: Int, val dailyLimit: Int, val pendingRequests: Int,
    /** ④ 선물 요약 (아래 바·탭 배지·다음 날 상자 바로 열기) */
    val gift: GiftSummary? = null,
)

data class MemberRow(val id: Long, val nickname: String, val role: String, val colorIdx: Int, val songCount: Int, val me: Boolean, val avatarCharacterId: Long? = null)
data class NewPin(val memberId: Long, val pin: String)

val KST: ZoneId = ZoneId.of("Asia/Seoul")
fun todayKst(): LocalDate = LocalDate.now(KST)
fun Member.view() = MemberView(id, nickname, role.name, colorIdx, avatarCharacterId)

@Service
class RoomService(
    private val rooms: RoomRepository,
    private val songs: RoomSongRepository,
    private val access: RoomAccess,
    private val pins: PinHasher,
    private val props: GyeolProperties,
    private val gifts: GiftRepository,
) {
    private val p get() = props.room

    fun summary(publicId: String): RoomSummary {
        val room = access.room(publicId)
        return RoomSummary(room.publicId, room.name, room.status.name, p.maxMembers, rooms.members(room.id).map { it.view() })
    }

    /** 참가: 방 행을 잠그고 인원을 센다 (동시에 6명이 눌러도 5명만) */
    @Transactional
    fun join(publicId: String, body: JoinRequest): JoinedResponse {
        val nickname = RoomInput.nickname(body.nickname)
        val pin = RoomInput.pin(body.pin)
        val room = rooms.lock(access.room(publicId).id)
        if (room.status == RoomStatus.CLOSED) throw ApiException.conflict("ROOM_CLOSED", "방장이 참가를 마감했어요")
        val members = rooms.members(room.id)
        if (members.size >= p.maxMembers) throw ApiException.conflict("ROOM_FULL", "이 우주는 자리가 다 찼어요")
        if (rooms.nicknameTaken(room.id, nickname)) throw ApiException.conflict("NICKNAME_TAKEN", "이 우주에 같은 닉네임이 있어요")
        val color = (0 until p.maxMembers).first { c -> members.none { it.colorIdx == c } }
        val memberId = rooms.addMember(room.id, nickname, Role.MEMBER, color, pins.hash(pin))
        rooms.bumpMapRev(room.id)
        return issueKey(room.publicId, memberId, MemberView(memberId, nickname, Role.MEMBER.name, color))
    }

    /** 다른 기기에서 "나는 OO예요" + 4자리. 5번 틀리면 10분 잠금 */
    @Transactional(noRollbackFor = [ApiException::class])
    fun rejoin(publicId: String, body: RejoinRequest): JoinedResponse {
        val room = access.room(publicId)
        val member = body.memberId?.let(rooms::member)?.takeIf { it.roomId == room.id && it.removedAt == null }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "이 우주에 없는 이름이에요")
        val pin = RoomInput.pin(body.pin)
        val now = Instant.now()
        member.lockedUntil?.takeIf { it.isAfter(now) }?.let { until ->
            throw ApiException(HttpStatus.LOCKED, "PIN_LOCKED", "5번 틀려서 잠시 잠겼어요", mapOf("retryAfterSeconds" to (until.epochSecond - now.epochSecond)))
        }
        if (!pins.matches(pin, member.pinHash)) {
            val fails = rooms.recordPinFailure(member.id, p.pinMaxFails, now.plus(p.pinLock))
            if (fails >= p.pinMaxFails) {
                throw ApiException(HttpStatus.LOCKED, "PIN_LOCKED", "5번 틀려서 잠시 잠겼어요", mapOf("retryAfterSeconds" to p.pinLock.seconds))
            }
            throw ApiException(HttpStatus.UNAUTHORIZED, "PIN_MISMATCH", "암호가 맞지 않아요", mapOf("attemptsLeft" to (p.pinMaxFails - fails)))
        }
        rooms.resetPinFailures(member.id)
        return issueKey(room.publicId, member.id, member.view())
    }

    private fun issueKey(publicId: String, memberId: Long, view: MemberView): JoinedResponse {
        val key = Tokens.newToken()
        rooms.addKey(memberId, Tokens.hash(key))
        return JoinedResponse(publicId, key, view)
    }

    /** 다음 날 첫 조회 때 기다리는 상자를 하루 1개 READY로 올린 뒤 선물 요약을 함께 준다 */
    fun me(ctx: RoomContext): MeView {
        val m = ctx.member
        val today = todayKst()
        gifts.promoteWaiting(m.id, today)
        return MeView(
            ctx.room.publicId, ctx.room.name, ctx.room.status.name, m.view(),
            songs.countOf(m.id), songs.daily(m.id, today), p.dailySongLimit, songs.pendingRequests(m.id),
            gifts.summary(m.id, today),
        )
    }

    @Transactional
    fun rename(ctx: RoomContext, body: RenameRequest): MemberView {
        val nickname = RoomInput.nickname(body.nickname)
        if (rooms.nicknameTaken(ctx.room.id, nickname, ctx.member.id)) throw ApiException.conflict("NICKNAME_TAKEN", "이 우주에 같은 닉네임이 있어요")
        rooms.renameMember(ctx.member.id, nickname)
        rooms.bumpMapRev(ctx.room.id)
        return ctx.member.copy(nickname = nickname).view()
    }

    fun members(ctx: RoomContext): List<MemberRow> {
        val counts = songs.countsByMember(ctx.room.id)
        return rooms.members(ctx.room.id).map { MemberRow(it.id, it.nickname, it.role.name, it.colorIdx, counts[it.id] ?: 0, it.id == ctx.member.id, it.avatarCharacterId) }
    }

    // ── 방장 ──

    private fun target(ctx: RoomContext, memberId: Long): Member {
        ctx.requireHost()
        return rooms.member(memberId)?.takeIf { it.roomId == ctx.room.id && it.removedAt == null }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "이 우주에 없는 사람이에요")
    }

    /** 참가자 새 암호: 서버가 랜덤 4자리를 만들어 바로 그 사람의 암호로 (⑭). 응답에서 한 번만 보여 준다 */
    @Transactional
    fun resetPin(ctx: RoomContext, memberId: Long): NewPin {
        val t = target(ctx, memberId)
        if (t.role == Role.HOST) throw ApiException.validation("방장 암호는 이메일로 다시 정해요")
        val pin = pins.randomPin()
        rooms.setPin(t.id, pins.hash(pin))
        rooms.audit("HOST", ctx.member.id, ctx.room.id, "PIN_RESET", t.id)
        return NewPin(t.id, pin)
    }

    /** 내보내기: 그 사람의 열쇠와 담은 노래를 함께 지운다 (11-13 결정) */
    @Transactional
    fun kick(ctx: RoomContext, memberId: Long) {
        val t = target(ctx, memberId)
        if (t.id == ctx.member.id) throw ApiException.validation("방장은 자신을 내보낼 수 없어요")
        val removed = songs.removeAllOf(t.id)
        rooms.remove(t.id)
        rooms.bumpMapRev(ctx.room.id)
        rooms.audit("HOST", ctx.member.id, ctx.room.id, "MEMBER_KICK", t.id, """{"songs":$removed}""")
    }

    @Transactional
    fun patch(ctx: RoomContext, body: RoomPatch): RoomSummary {
        ctx.requireHost()
        body.name?.let { rooms.renameRoom(ctx.room.id, RoomInput.name(it)); rooms.audit("HOST", ctx.member.id, ctx.room.id, "ROOM_RENAME") }
        body.closed?.let {
            rooms.setStatus(ctx.room.id, if (it) RoomStatus.CLOSED else RoomStatus.ACTIVE)
            rooms.audit("HOST", ctx.member.id, ctx.room.id, if (it) "ROOM_CLOSE" else "ROOM_OPEN")
        }
        rooms.bumpMapRev(ctx.room.id)
        return summary(ctx.room.publicId)
    }

    /** 방장 삭제: 바로 숨기고 7일 뒤 완전 삭제 (11-13 결정 2). 7일 안에는 관리자만 되살린다 */
    @Transactional
    fun delete(ctx: RoomContext) {
        ctx.requireHost()
        val now = Instant.now()
        rooms.hide(ctx.room.id, now, now.plus(p.purgeAfter))
        rooms.audit("HOST", ctx.member.id, ctx.room.id, "ROOM_DELETE")
    }
}

@RestController
class RoomController(private val service: RoomService, private val rooms: RoomRepository, private val limiter: IpRateLimiter, private val props: GyeolProperties) {

    private fun withCookie(status: HttpStatus, joined: JoinedResponse) = ResponseEntity.status(status)
        .header(HttpHeaders.SET_COOKIE, RoomKeys.cookie(joined.roomId, joined.key, props.room.secureCookie).toString()).body(joined)

    @GetMapping("/api/rooms/{rid}")
    fun summary(@PathVariable rid: String): RoomSummary = service.summary(rid)

    @PostMapping("/api/rooms/{rid}/members")
    fun join(@PathVariable rid: String, @RequestBody body: JoinRequest, req: HttpServletRequest): ResponseEntity<JoinedResponse> {
        limiter.check("join:${limiter.ip(req)}", props.room.joinPerMinute)
        return withCookie(HttpStatus.CREATED, service.join(rid, body))
    }

    @PostMapping("/api/rooms/{rid}/session")
    fun rejoin(@PathVariable rid: String, @RequestBody body: RejoinRequest, req: HttpServletRequest): ResponseEntity<JoinedResponse> {
        limiter.check("rejoin:${limiter.ip(req)}", props.room.joinPerMinute)
        return withCookie(HttpStatus.OK, service.rejoin(rid, body))
    }

    /** 이 기기에서 나가기: 이 기기 열쇠만 지운다 (노래는 그대로) */
    @DeleteMapping("/api/rooms/{rid}/session")
    fun leave(@PathVariable rid: String, @RoomMember ctx: RoomContext): ResponseEntity<Void> {
        rooms.revokeKey(ctx.keyHash)
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RoomKeys.clear(rid, props.room.secureCookie).toString()).build()
    }

    @GetMapping("/api/rooms/{rid}/me")
    fun me(@PathVariable rid: String, @RoomMember ctx: RoomContext): MeView = service.me(ctx)

    @PatchMapping("/api/rooms/{rid}/me")
    fun rename(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestBody body: RenameRequest): MemberView = service.rename(ctx, body)

    @GetMapping("/api/rooms/{rid}/members")
    fun members(@PathVariable rid: String, @RoomMember ctx: RoomContext): List<MemberRow> = service.members(ctx)

    @PostMapping("/api/rooms/{rid}/members/{mid}/pin")
    fun resetPin(@PathVariable rid: String, @PathVariable mid: Long, @RoomMember ctx: RoomContext): NewPin = service.resetPin(ctx, mid)

    @DeleteMapping("/api/rooms/{rid}/members/{mid}")
    fun kick(@PathVariable rid: String, @PathVariable mid: Long, @RoomMember ctx: RoomContext): ResponseEntity<Void> {
        service.kick(ctx, mid); return ResponseEntity.noContent().build()
    }

    @PatchMapping("/api/rooms/{rid}")
    fun patch(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestBody body: RoomPatch): RoomSummary = service.patch(ctx, body)

    @DeleteMapping("/api/rooms/{rid}")
    fun delete(@PathVariable rid: String, @RoomMember ctx: RoomContext): ResponseEntity<Void> {
        service.delete(ctx); return ResponseEntity.noContent().build()
    }
}
