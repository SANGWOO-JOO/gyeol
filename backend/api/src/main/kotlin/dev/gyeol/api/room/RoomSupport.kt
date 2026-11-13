package dev.gyeol.api.room

import dev.gyeol.api.web.ApiException
import dev.gyeol.core.room.Member
import dev.gyeol.core.room.Room
import dev.gyeol.core.room.RoomRepository
import dev.gyeol.core.room.RoomStatus
import dev.gyeol.core.session.Tokens
import io.awspring.cloud.sqs.operations.SqsTemplate
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.ResponseCookie
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 4자리 암호·메일 코드 해시 (BCrypt). 원문은 저장·로그 출력하지 않는다 */
@Component
class PinHasher {
    private val encoder = BCryptPasswordEncoder(10)
    private val random = SecureRandom()

    fun hash(raw: String): String = encoder.encode(raw)!!
    fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)

    /** 0000~9999 */
    fun randomPin(): String = "%04d".format(random.nextInt(10_000))
    fun randomCode(): String = "%06d".format(random.nextInt(1_000_000))

    companion object {
        val PIN = Regex("^[0-9]{4}$")
        val CODE = Regex("^[0-9]{6}$")
    }
}

object RoomIds {
    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 헷갈리는 글자(l, o, 0, 1, I, O) 제외

    /** 링크용 10자 (약 58비트) */
    fun newPublicId(): String = (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    fun looksValid(id: String) = id.length == 10 && id.all { it in ALPHABET }
}

/**
 * 기기 열쇠. HttpOnly 쿠키(경로를 그 방으로 한정해 방마다 따로)와 localStorage(→ X-Room-Key 헤더) 두 곳에 둔다.
 * 카톡 안 브라우저처럼 쿠키가 잘 남지 않는 경우를 헤더가 보완한다.
 */
object RoomKeys {
    const val HEADER = "X-Room-Key"
    const val COOKIE = "gyeol_room"

    fun cookie(publicId: String, key: String, secure: Boolean): ResponseCookie =
        ResponseCookie.from(COOKIE, key).httpOnly(true).secure(secure).sameSite("Lax").path("/api/rooms/$publicId")
            .maxAge(Duration.ofDays(400)).build()

    fun clear(publicId: String, secure: Boolean): ResponseCookie =
        ResponseCookie.from(COOKIE, "").httpOnly(true).secure(secure).sameSite("Lax").path("/api/rooms/$publicId").maxAge(0).build()

    fun read(req: HttpServletRequest): String? =
        req.getHeader(HEADER)?.takeIf { it.isNotBlank() } ?: req.cookies?.firstOrNull { it.name == COOKIE }?.value?.takeIf { it.isNotBlank() }
}

/** 컨트롤러 인자: 이 방에 참가한 기기의 참가자. 열쇠가 없거나 다른 방 열쇠면 401 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class RoomMember

data class RoomContext(val room: Room, val member: Member, val keyHash: ByteArray) {
    val isHost get() = member.role == dev.gyeol.core.room.Role.HOST
    fun requireHost() { if (!isHost) throw ApiException.hostOnly() }
}

@Component
class RoomAccess(private val rooms: RoomRepository) {
    /** 링크의 방. 형식이 틀리거나 없거나 숨긴(삭제) 방이면 404 */
    fun room(publicId: String): Room {
        if (!RoomIds.looksValid(publicId)) throw ApiException.roomNotFound()
        return rooms.findByPublicId(publicId)?.takeIf { it.status != RoomStatus.HIDDEN } ?: throw ApiException.roomNotFound()
    }

    fun context(publicId: String, key: String?): RoomContext {
        val room = room(publicId)
        if (key == null || !Tokens.looksValid(key)) throw ApiException.noKey()
        val hash = Tokens.hash(key)
        val member = rooms.memberByKey(hash)?.takeIf { it.roomId == room.id } ?: throw ApiException.noKey()
        return RoomContext(room, member, hash)
    }
}

@Component
class RoomMemberResolver(private val access: RoomAccess) : HandlerMethodArgumentResolver {
    override fun supportsParameter(p: MethodParameter) = p.hasParameterAnnotation(RoomMember::class.java) && p.parameterType == RoomContext::class.java

    override fun resolveArgument(p: MethodParameter, mav: ModelAndViewContainer?, req: NativeWebRequest, binder: WebDataBinderFactory?): Any {
        val http = req.getNativeRequest(HttpServletRequest::class.java)!!
        @Suppress("UNCHECKED_CAST")
        val vars = http.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as Map<String, String>
        return access.context(vars["rid"] ?: throw ApiException.roomNotFound(), RoomKeys.read(http))
    }
}

@Configuration
class RoomWebConfig(private val resolver: RoomMemberResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(resolver)
    }
}

/**
 * 분 단위 요청 제한 (서버 메모리, 고정 창). 단일 서버에서는 정확하고, 파드가 여럿이면 파드별 근사치다.
 * 하루 담기 한도·메일 발송 제한처럼 정확해야 하는 것은 DB로 센다.
 */
@Component
class IpRateLimiter {
    private val windows = ConcurrentHashMap<String, Pair<Long, Int>>()

    /** [key]가 이번 분에 [limit]번을 넘으면 429 */
    fun check(key: String, limit: Int, now: Instant = Instant.now()) {
        val minute = now.epochSecond / 60
        val (m, n) = windows.compute(key) { _, old -> if (old == null || old.first != minute) minute to 1 else old.first to old.second + 1 }!!
        if (m == minute && n > limit) throw ApiException.tooMany("RATE_LIMIT", "요청이 너무 많아요. 잠시 뒤 다시 시도해 주세요")
        if (windows.size > 50_000) windows.entries.removeIf { it.value.first < minute }
    }

    fun ip(req: HttpServletRequest): String =
        req.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: req.remoteAddr
}

/** 커밋 뒤에 큐로 보낸다 (먼저 보내면 worker가 아직 없는 행을 읽을 수 있음). 못 보내도 요청은 성공으로 둔다 */
@Component
class AfterCommitSender(private val sqs: SqsTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(queue: String, body: String) {
        val run = { runCatching { sqs.send { it.queue(queue).payload(body) } }.onFailure { log.error("큐 요청 실패: {}", queue, it) } }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { run() }
            })
        } else run()
    }
}
