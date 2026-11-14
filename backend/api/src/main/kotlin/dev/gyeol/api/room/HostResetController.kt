package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.mail.MailSender
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.room.Role
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

/** 이메일 주소는 돌려주지 않는다 (화면에도 안 보임). codeExpiresAt: 이번 코드 만료(10분), resendAfter: 다시 받기 가능 시각(1분) */
data class HostResetSent(val codeExpiresAt: Instant, val resendAfter: Instant)
data class HostResetVerify(val code: String?, val newPin: String?)

/**
 * 방장 비밀번호 재설정 (room-api-db.md 3절, 화면 6-d): 열쇠 없이 방 링크만으로 부른다.
 * 1) 방장 등록 이메일로 6자리 코드 (email_code purpose HOST_RESET, 방마다 최신 코드 하나)
 * 2) 코드 + 새 4자리 → 방장 비밀번호 교체(틀린 횟수·잠금도 풂) → 방장의 다른 기기 열쇠 모두 폐기(I-10) → 이 기기 열쇠 발급(다시 들어오기와 같은 응답)
 * 막는 장치: 코드 5번 틀리면 그 코드 잠금, 다시 받기 1분, 같은 이메일 1시간 5통·같은 IP 1시간 20통(방 만들기와 같은 mail_send_log), IP 분당 제한
 */
@Service
class HostResetService(
    private val rooms: RoomRepository,
    private val drafts: RoomDraftRepository,
    private val access: RoomAccess,
    private val pins: PinHasher,
    private val mail: MailSender,
    private val props: GyeolProperties,
    private val json: tools.jackson.databind.json.JsonMapper,
) {
    private val p get() = props.room

    @Transactional
    fun send(publicId: String, ip: String): HostResetSent {
        // 없는 방·숨긴 방은 404: 방이 있는지는 열쇠 없이 부르는 GET /rooms/{rid}로도 이미 알 수 있어 숨길 정보가 아니다
        val room = access.room(publicId)
        val host = rooms.members(room.id).firstOrNull { it.role == Role.HOST && it.removedAt == null }
        val email = rooms.hostEmail(room.id)
        val now = Instant.now()
        val last = drafts.latestCode("HOST_RESET", null, room.id)
        if (last != null && last.lastSentAt.plus(p.codeResendAfter).isAfter(now)) {
            throw ApiException.tooMany("RESEND_TOO_SOON", "코드는 1분에 한 번 다시 받을 수 있어요")
        }
        if (email == null || host == null) {
            // 방장 이메일이 없으면(정상 흐름에서는 없음) 보내지 않되 같은 모양으로 답한다
            return HostResetSent(now.plus(p.codeTtl), now.plus(p.codeResendAfter))
        }
        val hourAgo = now.minus(Duration.ofHours(1))
        if (drafts.sendsByEmail(Tokens.hash(email), hourAgo) >= p.mailPerEmailHour || drafts.sendsByIp(ip, hourAgo) >= p.mailPerIpHour) {
            throw ApiException.tooMany("MAIL_LIMIT", "메일을 너무 많이 보냈어요. 잠시 뒤 다시 시도해 주세요")
        }
        val code = pins.randomCode()
        drafts.newCode("HOST_RESET", null, room.id, pins.hash(code), now.plus(p.codeTtl))
        drafts.logSend(Tokens.hash(email), ip)
        mail.sendCode(email, "HOST_RESET", code)
        rooms.audit("HOST", host.id, room.id, "HOST_RESET_SENT", host.id, json.writeValueAsString(mapOf("ip" to ip)))
        return HostResetSent(now.plus(p.codeTtl), now.plus(p.codeResendAfter))
    }

    /** 틀린 횟수가 남도록 ApiException에서는 되돌리지 않는다 (방 만들기 verify와 같음) */
    @Transactional(noRollbackFor = [ApiException::class])
    fun verify(publicId: String, body: HostResetVerify, ip: String): JoinedResponse {
        val room = access.room(publicId)
        val code = body.code?.trim()?.takeIf { PinHasher.CODE.matches(it) } ?: throw ApiException.validation("코드는 숫자 6자리예요")
        val newPin = RoomInput.pin(body.newPin)
        val ec = drafts.latestCode("HOST_RESET", null, room.id)
        if (ec == null || ec.usedAt != null || ec.expiresAt.isBefore(Instant.now())) {
            throw ApiException(HttpStatus.GONE, "CODE_EXPIRED", "코드가 만료됐어요. 다시 받아 주세요")
        }
        if (ec.attempts >= p.codeMaxAttempts) throw ApiException.tooMany("CODE_LOCKED", "5번 틀렸어요. 코드를 다시 받아 주세요")
        if (!pins.matches(code, ec.codeHash)) {
            drafts.addAttempt(ec.id)
            val left = p.codeMaxAttempts - ec.attempts - 1
            throw ApiException(HttpStatus.BAD_REQUEST, "CODE_MISMATCH", "코드가 맞지 않아요", mapOf("attemptsLeft" to left))
        }
        val host = rooms.members(room.id).firstOrNull { it.role == Role.HOST && it.removedAt == null }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "이 우주에 방장이 없어요")
        drafts.useCode(ec.id)
        rooms.setPin(host.id, pins.hash(newPin))
        // 이메일을 빼앗긴 경우까지 막도록 방장의 다른 기기 열쇠는 모두 폐기하고 이번 열쇠만 남긴다 (11-13 I-10 결정. 참가자 새 4자리 setPin은 열쇠 유지 그대로)
        val revoked = rooms.revokeKeys(host.id)
        val key = Tokens.newToken()
        rooms.addKey(host.id, Tokens.hash(key))
        rooms.audit("HOST", host.id, room.id, "HOST_PIN_RESET", host.id, json.writeValueAsString(mapOf("ip" to ip, "revokedKeys" to revoked)))
        return JoinedResponse(room.publicId, key, host.view())
    }
}

@RestController
class HostResetController(private val service: HostResetService, private val limiter: IpRateLimiter, private val props: GyeolProperties) {
    @PostMapping("/api/rooms/{rid}/host-reset")
    fun send(@PathVariable rid: String, req: HttpServletRequest): ResponseEntity<HostResetSent> {
        val ip = limiter.ip(req)
        limiter.check("host-reset:$ip", props.room.joinPerMinute)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.send(rid, ip))
    }

    @PostMapping("/api/rooms/{rid}/host-reset/verify")
    fun verify(@PathVariable rid: String, @RequestBody body: HostResetVerify, req: HttpServletRequest): ResponseEntity<JoinedResponse> {
        val ip = limiter.ip(req)
        limiter.check("host-reset:$ip", props.room.joinPerMinute)
        val joined = service.verify(rid, body, ip)
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, RoomKeys.cookie(joined.roomId, joined.key, props.room.secureCookie).toString()).body(joined)
    }
}
