package dev.gyeol.api.admin

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.admin.AdminRepository
import dev.gyeol.core.admin.AdminSession
import dev.gyeol.core.admin.AdminUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (RFC 6238): HMAC-SHA1, 30초 칸, 6자리. 비밀값은 Base32 문자열(인증 앱에 넣는 형태).
 * 시계 어긋남을 위해 앞뒤 1칸까지 받고, 받은 칸 번호를 돌려줘 같은 코드 재사용을 막는다(admin_user.totp_last_step)
 */
object Totp {
    const val STEP_SECONDS = 30L

    fun step(at: Instant): Long = at.epochSecond / STEP_SECONDS

    fun code(secret: ByteArray, step: Long, digits: Int = 6): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = h.last().toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /** 맞으면 그 칸 번호, 아니면 null */
    fun verify(secret: ByteArray, code: String?, at: Instant): Long? {
        val c = code?.trim()?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
        val now = step(at)
        return (now - 1..now + 1).firstOrNull { s ->
            MessageDigest.isEqual(code(secret, s).toByteArray(), c.toByteArray())
        }
    }

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Decode(s: String): ByteArray {
        val clean = s.trim().replace("=", "").replace(" ", "").uppercase()
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = B32.indexOf(ch)
            require(v >= 0) { "Base32가 아닌 글자" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/** TOTP 비밀값 암호화 (AES-256-GCM). 저장 형식: base64(iv 12바이트 + 암호문·태그). tools/admin-account와 같은 형식 */
class TotpCipher(keyBase64: String) {
    private val key = SecretKeySpec(Base64.getDecoder().decode(keyBase64).also { require(it.size == 32) { "ADMIN_TOTP_KEY는 32바이트여야 합니다" } }, "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    fun decrypt(stored: String): String {
        val all = Base64.getDecoder().decode(stored)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }
}

object AdminTokens {
    const val COOKIE = "gyeol_admin"
    const val PATH = "/api/admin"
    private val random = SecureRandom()

    fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

    fun cookie(token: String, maxAgeSeconds: Long, secure: Boolean): String =
        "$COOKIE=$token; Path=$PATH; Max-Age=$maxAgeSeconds; HttpOnly; SameSite=Strict" + if (secure) "; Secure" else ""

    fun read(req: HttpServletRequest): String? = req.cookies?.firstOrNull { it.name == COOKIE }?.value
}

/** 로그인한 관리자 (ACTIVE 세션) */
data class AdminContext(val admin: AdminUser, val session: AdminSession, val ip: String)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class AdminOnly

@Component
class AdminResolver(private val repo: AdminRepository) : HandlerMethodArgumentResolver {
    override fun supportsParameter(p: MethodParameter) = p.hasParameterAnnotation(AdminOnly::class.java) && p.parameterType == AdminContext::class.java

    override fun resolveArgument(p: MethodParameter, mav: ModelAndViewContainer?, req: NativeWebRequest, binder: WebDataBinderFactory?): Any {
        val http = req.getNativeRequest(HttpServletRequest::class.java)!!
        val token = AdminTokens.read(http) ?: throw unauthorized()
        val s = repo.session(AdminTokens.hash(token), Instant.now())?.takeIf { it.stage == "ACTIVE" } ?: throw unauthorized()
        val admin = repo.user(s.adminId) ?: throw unauthorized()
        return AdminContext(admin, s, AdminIp.of(http))
    }

    private fun unauthorized() = ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_LOGIN_REQUIRED", "관리자 로그인이 필요해요")
}

object AdminIp {
    fun of(req: HttpServletRequest): String =
        req.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: req.remoteAddr
}

/**
 * /api/admin 아래 공통: 응답은 저장하지 않게(no-store), 상태를 바꾸는 요청은 Origin이 있으면 웹 주소와 같아야 한다
 * (쿠키는 SameSite=Strict라 다른 사이트 요청에는 붙지 않지만 한 겹 더)
 */
@Component
class AdminInterceptor(private val props: GyeolProperties) : HandlerInterceptor {
    override fun preHandle(req: HttpServletRequest, res: HttpServletResponse, handler: Any): Boolean {
        res.setHeader("Cache-Control", "no-store")
        if (req.method !in setOf("GET", "HEAD", "OPTIONS")) {
            val origin = req.getHeader("Origin")
            if (origin != null && origin != props.webOrigin) throw ApiException(HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "허용되지 않은 출처예요")
        }
        return true
    }
}

@Configuration
class AdminWebConfig(private val resolver: AdminResolver, private val interceptor: AdminInterceptor) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(resolver)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/admin/**")
    }
}
