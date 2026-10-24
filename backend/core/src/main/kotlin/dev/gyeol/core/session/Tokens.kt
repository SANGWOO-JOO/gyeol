package dev.gyeol.core.session

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 결과 링크용 토큰. 원문은 사용자에게만 주고, DB에는 SHA-256 해시만 저장한다. */
object Tokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 32바이트 무작위 값 → base64url 43자 */
    fun newToken(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)

    fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

    /** 형식이 틀린 토큰은 DB를 보지 않고 바로 "없음"으로 처리한다 */
    fun looksValid(token: String): Boolean = token.length == 43 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** 로그에 남길 때 앞 6자만 보여 준다 */
    fun mask(token: String): String = token.take(6) + "…"
}
