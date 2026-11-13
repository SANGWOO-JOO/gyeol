package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.room.Role
import dev.gyeol.core.room.RoomDraft
import dev.gyeol.core.room.RoomDraftRepository
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.session.Tokens
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

data class CreateDraftRequest(val name: String?, val nickname: String?, val pin: String?, val email: String?, val consent: Boolean?)
data class DraftResponse(val draftToken: String, val email: String, val expiresAt: Instant, val resendAfter: Instant)
data class VerifyRequest(val code: String?)

/** 참가·다시 들어오기·방 만들기가 돌려주는 열쇠 (localStorage용. 같은 값이 HttpOnly 쿠키로도 간다) */
data class JoinedResponse(val roomId: String, val key: String, val member: MemberView)

data class MemberView(val id: Long, val nickname: String, val role: String, val colorIdx: Int)

object RoomInput {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun name(raw: String?): String = raw?.trim()?.takeIf { it.length in 1..30 } ?: throw ApiException.validation("우주 이름은 1~30자예요")
    fun nickname(raw: String?): String = raw?.trim()?.takeIf { it.length in 1..12 } ?: throw ApiException.validation("닉네임은 1~12자예요")
    fun pin(raw: String?): String = raw?.takeIf { PinHasher.PIN.matches(it) } ?: throw ApiException.validation("암호는 숫자 4자리예요")
    fun email(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it.length <= 254 && EMAIL.matches(it) } ?: throw ApiException.validation("이메일 형식을 확인해 주세요")
}

/**
 * 방 만들기: 내용 보관 + 메일 코드 → 코드 확인 → 방·방장·열쇠 (방장 이메일 인증 순서 ⑥).
 * 코드는 10분, 5번 틀리면 다시 받기, 다시 받기는 1분에 한 번. 이메일·IP별 발송 제한.
 */
@Service
class RoomDraftService(
    private val drafts: RoomDraftRepository,
    private val rooms: RoomRepository,
    private val pins: PinHasher,
    private val mail: MailSender,
    private val props: GyeolProperties,
) {
    private val p get() = props.room

    @Transactional
    fun create(body: CreateDraftRequest, ip: String): DraftResponse {
        val name = RoomInput.name(body.name)
        val nickname = RoomInput.nickname(body.nickname)
        val pin = RoomInput.pin(body.pin)
        val email = RoomInput.email(body.email)
        if (body.consent != true) throw ApiException.validation("이메일 보관에 동의해야 우주를 만들 수 있어요")
        checkSendLimits(email, ip)

        val token = Tokens.newToken()
        val now = Instant.now()
        val id = drafts.create(Tokens.hash(token), name, nickname, pins.hash(pin), email, now, now.plus(p.draftTtl))
        val sentAt = sendCode(id, email, ip)
        return DraftResponse(token, LogMailSender.mask(email), now.plus(p.draftTtl), sentAt.plus(p.codeResendAfter))
    }

    @Transactional
    fun resend(token: String, ip: String): DraftResponse {
        val draft = find(token)
        val last = drafts.latestCode("CREATE", draft.id, null)
        if (last != null && last.lastSentAt.plus(p.codeResendAfter).isAfter(Instant.now())) {
            throw ApiException.tooMany("RESEND_TOO_SOON", "코드는 1분에 한 번 다시 받을 수 있어요")
        }
        checkSendLimits(draft.email, ip)
        val sentAt = sendCode(draft.id, draft.email, ip)
        return DraftResponse(token, LogMailSender.mask(draft.email), draft.expiresAt, sentAt.plus(p.codeResendAfter))
    }

    /** 코드가 맞으면 방을 만들고 방장 열쇠를 준다 */
    @Transactional(noRollbackFor = [ApiException::class])
    fun verify(token: String, body: VerifyRequest): JoinedResponse {
        val draft = find(token)
        val code = body.code?.trim()?.takeIf { PinHasher.CODE.matches(it) } ?: throw ApiException.validation("코드는 숫자 6자리예요")
        val ec = drafts.latestCode("CREATE", draft.id, null)
        if (ec == null || ec.usedAt != null || ec.expiresAt.isBefore(Instant.now())) {
            throw ApiException(HttpStatus.GONE, "CODE_EXPIRED", "코드가 만료됐어요. 다시 받아 주세요")
        }
        if (ec.attempts >= p.codeMaxAttempts) throw ApiException.tooMany("CODE_LOCKED", "5번 틀렸어요. 코드를 다시 받아 주세요")
        if (!pins.matches(code, ec.codeHash)) {
            drafts.addAttempt(ec.id)
            val left = p.codeMaxAttempts - ec.attempts - 1
            throw ApiException(HttpStatus.BAD_REQUEST, "CODE_MISMATCH", "코드가 맞지 않아요", mapOf("attemptsLeft" to left))
        }
        drafts.useCode(ec.id)
        return createRoom(draft)
    }

    private fun createRoom(d: RoomDraft): JoinedResponse {
        // 링크 id는 58비트 무작위라 겹칠 일이 사실상 없다. 겹치면 UNIQUE 위반으로 실패하고 다시 누르면 된다
        val pid = RoomIds.newPublicId()
        val roomId = rooms.create(pid, d.name) to pid
        val memberId = rooms.addMember(roomId.first, d.nickname, Role.HOST, 0, d.pinHash)
        rooms.saveHostEmail(roomId.first, d.email, d.consentAt)
        drafts.delete(d.id)
        val key = Tokens.newToken()
        rooms.addKey(memberId, Tokens.hash(key))
        rooms.audit("HOST", memberId, roomId.first, "ROOM_CREATE")
        return JoinedResponse(roomId.second, key, MemberView(memberId, d.nickname, Role.HOST.name, 0))
    }

    private fun find(token: String): RoomDraft {
        if (!Tokens.looksValid(token)) throw ApiException(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "처음부터 다시 만들어 주세요")
        return drafts.findActive(Tokens.hash(token)) ?: throw ApiException(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "처음부터 다시 만들어 주세요")
    }

    private fun checkSendLimits(email: String, ip: String) {
        val hourAgo = Instant.now().minus(Duration.ofHours(1))
        if (drafts.sendsByEmail(Tokens.hash(email), hourAgo) >= p.mailPerEmailHour || drafts.sendsByIp(ip, hourAgo) >= p.mailPerIpHour) {
            throw ApiException.tooMany("MAIL_LIMIT", "메일을 너무 많이 보냈어요. 잠시 뒤 다시 시도해 주세요")
        }
    }

    private fun sendCode(draftId: Long, email: String, ip: String): Instant {
        val code = pins.randomCode()
        val now = Instant.now()
        drafts.newCode("CREATE", draftId, null, pins.hash(code), now.plus(p.codeTtl))
        drafts.logSend(Tokens.hash(email), ip)
        mail.sendCode(email, "CREATE", code)
        return now
    }
}

@RestController
class RoomDraftController(private val service: RoomDraftService, private val limiter: IpRateLimiter, private val props: GyeolProperties) {

    @PostMapping("/api/room-drafts")
    fun create(@RequestBody body: CreateDraftRequest, req: HttpServletRequest): ResponseEntity<DraftResponse> {
        val ip = limiter.ip(req)
        limiter.check("draft:$ip", props.room.draftPerMinute)
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body, ip))
    }

    @PostMapping("/api/room-drafts/{token}/resend")
    fun resend(@PathVariable token: String, req: HttpServletRequest): DraftResponse {
        val ip = limiter.ip(req)
        limiter.check("draft:$ip", props.room.draftPerMinute)
        return service.resend(token, ip)
    }

    @PostMapping("/api/room-drafts/{token}/verify")
    fun verify(@PathVariable token: String, @RequestBody body: VerifyRequest, req: HttpServletRequest): ResponseEntity<JoinedResponse> {
        limiter.check("verify:${limiter.ip(req)}", props.room.joinPerMinute)
        val joined = service.verify(token, body)
        return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.SET_COOKIE, RoomKeys.cookie(joined.roomId, joined.key, props.room.secureCookie).toString())
            .body(joined)
    }
}
