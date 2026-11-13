package dev.gyeol.api.mail

/**
 * 인증 코드 메일 발송. `gyeol.mail.mode`로 고른다: log(기본, 로컬·테스트) → [LogMailSender], ses → [SesMailSender].
 *
 * 호출하는 쪽은 DB 트랜잭션 안에서 부른다. 발송이 실패하면 예외로 코드 행·발송 기록이 함께 롤백된다.
 * 한계: 메일은 나갔는데 커밋이 실패하는 경우(사용자는 다시 받기로 새 코드를 받는다)가 남고, SES 호출 동안 DB 연결을 잡는다.
 */
interface MailSender {
    /** [purpose]: CREATE(방 만들기 인증) · HOST_RESET(방장 암호 재설정) */
    fun sendCode(to: String, purpose: String, code: String)
}
