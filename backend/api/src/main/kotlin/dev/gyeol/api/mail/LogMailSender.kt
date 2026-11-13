package dev.gyeol.api.mail

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** 로컬·테스트용 가짜 메일. 값이 없으면 이것을 쓴다 */
@Component
@ConditionalOnProperty(name = ["gyeol.mail.mode"], havingValue = "log", matchIfMissing = true)
class LogMailSender : MailSender {
    private val log = LoggerFactory.getLogger(javaClass)
    /** 테스트가 읽는 가짜 메일함 (받는 사람 → 마지막 코드) */
    val outbox = ConcurrentHashMap<String, String>()

    override fun sendCode(to: String, purpose: String, code: String) {
        outbox[to.lowercase()] = code
        // 로컬 개발용 가짜 메일: 코드만 찍고 주소는 일부 가린다
        log.info("[가짜 메일] {} → {}: 코드 {}", purpose, mask(to), code)
    }

    companion object {
        fun mask(email: String): String {
            val (local, domain) = email.split('@', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return local.take(1) + "***@" + domain
        }
    }
}
