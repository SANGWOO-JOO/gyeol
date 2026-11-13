package dev.gyeol.api.mail

import java.time.Duration

data class MailContent(val subject: String, val text: String, val html: String)

/** 인증 코드 메일 문구. 코드는 6자리 숫자만 들어오므로 HTML 이스케이프가 필요 없다 */
object MailTemplates {
    private val CODE = Regex("^[0-9]{6}$")

    fun code(purpose: String, code: String, ttl: Duration): MailContent {
        require(CODE.matches(code)) { "코드 형식" }
        val minutes = ttl.toMinutes()
        val (subject, lead) = when (purpose) {
            "CREATE" -> "[내 음악 결] 방 만들기 인증 코드 $code" to "우주(방)를 만들려면 아래 인증 코드를 입력해 주세요."
            "HOST_RESET" -> "[내 음악 결] 방장 암호 재설정 코드 $code" to "방장 암호를 다시 정하려면 아래 코드를 입력해 주세요."
            else -> throw IllegalArgumentException("알 수 없는 메일 용도: $purpose")
        }
        val ignore = "직접 요청하지 않았다면 이 메일은 무시해 주세요. 코드를 다른 사람에게 알려 주지 마세요."
        val text = "$lead\n\n인증 코드: $code\n\n코드는 ${minutes}분 동안 쓸 수 있어요.\n$ignore\n\n— 내 음악 결\n"
        val html = """
            <div style="font-family:sans-serif;line-height:1.6;color:#222">
              <p>$lead</p>
              <p style="font-size:28px;font-weight:bold;letter-spacing:6px">$code</p>
              <p>코드는 ${minutes}분 동안 쓸 수 있어요.<br>$ignore</p>
              <p style="color:#888">— 내 음악 결</p>
            </div>
        """.trimIndent()
        return MailContent(subject, text, html)
    }
}
