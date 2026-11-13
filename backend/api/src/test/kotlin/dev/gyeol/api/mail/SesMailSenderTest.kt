package dev.gyeol.api.mail

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import java.time.Duration
import java.util.Base64

class SesMailSenderTest {
    /** sendEmail만 가로채는 가짜 클라이언트 (실제 AWS를 부르지 않는다) */
    private class FakeSes(private val fail: Boolean = false) : SesV2Client {
        val requests = mutableListOf<SendEmailRequest>()
        override fun sendEmail(request: SendEmailRequest): SendEmailResponse {
            requests += request
            if (fail) throw MessageRejectedException.builder().message("Email address is not verified").build()
            return SendEmailResponse.builder().messageId("m-1").build()
        }
        override fun serviceName() = "ses"
        override fun close() {}
    }

    private val props = GyeolProperties()

    @Test
    fun `발신자·받는 사람·UTF-8·코드가 요청에 들어간다`() {
        val ses = FakeSes()
        SesMailSender(ses, MailProperties(mode = "ses", from = "noreply@gyeol.example", configurationSet = "gyeol"), props)
            .sendCode("someone@example.com", "CREATE", "123456")

        val req = ses.requests.single()
        assertEquals(listOf("someone@example.com"), req.destination().toAddresses())
        assertTrue(req.fromEmailAddress().endsWith(" <noreply@gyeol.example>"))
        val encodedName = req.fromEmailAddress().substringAfter("=?UTF-8?B?").substringBefore("?=")
        assertEquals("내 음악 결", String(Base64.getDecoder().decode(encodedName), Charsets.UTF_8))
        assertEquals("gyeol", req.configurationSetName())

        val msg = req.content().simple()
        assertEquals("UTF-8", msg.subject().charset())
        assertEquals("UTF-8", msg.body().text().charset())
        assertTrue(msg.subject().data().contains("123456"))
        assertTrue(msg.body().text().data().contains("123456"))
        assertTrue(msg.body().html().data().contains("123456"))
    }

    @Test
    fun `설정 세트를 비우면 붙이지 않는다`() {
        val ses = FakeSes()
        SesMailSender(ses, MailProperties(mode = "ses", from = "noreply@gyeol.example"), props).sendCode("a@b.c", "HOST_RESET", "000001")
        assertNull(ses.requests.single().configurationSetName())
    }

    @Test
    fun `SES가 거부하면 503 MAIL_FAILED`() {
        val sender = SesMailSender(FakeSes(fail = true), MailProperties(mode = "ses", from = "noreply@gyeol.example"), props)
        val e = assertThrows<ApiException> { sender.sendCode("a@b.c", "CREATE", "123456") }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status)
        assertEquals("MAIL_FAILED", e.code)
    }

    @Test
    fun `MAIL_FROM이 없으면 시작하지 않는다`() {
        assertThrows<IllegalArgumentException> { SesMailSender(FakeSes(), MailProperties(mode = "ses"), props) }
    }

    @Test
    fun `두 용도의 문구에 코드와 유효 시간이 들어간다`() {
        val create = MailTemplates.code("CREATE", "654321", Duration.ofMinutes(10))
        val reset = MailTemplates.code("HOST_RESET", "654321", Duration.ofMinutes(10))
        for (m in listOf(create, reset)) {
            assertTrue(m.text.contains("654321") && m.text.contains("10분"))
            assertTrue(m.html.contains("654321"))
        }
        assertTrue(create.subject.contains("방 만들기"))
        assertTrue(reset.subject.contains("재설정"))
        assertFalse(create.subject == reset.subject)
    }

    @Test
    fun `모르는 용도나 코드 형식이 아니면 거부한다`() {
        assertThrows<IllegalArgumentException> { MailTemplates.code("OTHER", "123456", Duration.ofMinutes(10)) }
        assertThrows<IllegalArgumentException> { MailTemplates.code("CREATE", "<b>1</b>", Duration.ofMinutes(10)) }
    }
}
