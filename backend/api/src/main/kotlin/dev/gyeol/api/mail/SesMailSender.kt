package dev.gyeol.api.mail

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest

/** `gyeol.mail.*` (MAIL_MODE·MAIL_FROM·MAIL_FROM_NAME·MAIL_CONFIG_SET) */
@ConfigurationProperties("gyeol.mail")
data class MailProperties(
    /** log | ses */
    val mode: String = "log",
    /** SES에서 인증한 도메인의 주소 (예: noreply@gyeol.example). IAM 정책의 ses:FromAddress와 같아야 한다 */
    val from: String = "",
    val fromName: String = "내 음악 결",
    /** 비우면 설정 세트 없이 보낸다 */
    val configurationSet: String = "",
)

@Configuration
@EnableConfigurationProperties(MailProperties::class)
class MailConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = ["gyeol.mail.mode"], havingValue = "ses")
    fun sesV2Client(@Value("\${spring.cloud.aws.region.static:ap-northeast-2}") region: String): SesV2Client =
        SesV2Client.builder().region(Region.of(region)).build()
}

/** SES v2로 발송. 실패하면 503 MAIL_FAILED (호출한 트랜잭션이 롤백된다). 로그에는 코드를 남기지 않는다 */
@Component
@ConditionalOnProperty(name = ["gyeol.mail.mode"], havingValue = "ses")
class SesMailSender(
    private val ses: SesV2Client,
    private val mail: MailProperties,
    private val props: GyeolProperties,
) : MailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(mail.from.contains('@')) { "gyeol.mail.mode=ses에는 gyeol.mail.from(MAIL_FROM)이 필요합니다" }
    }

    override fun sendCode(to: String, purpose: String, code: String) {
        val content = MailTemplates.code(purpose, code, props.room.codeTtl)
        val request = SendEmailRequest.builder()
            .fromEmailAddress(fromHeader())
            .destination(Destination.builder().toAddresses(to).build())
            .content(EmailContent.builder().simple(
                Message.builder()
                    .subject(utf8(content.subject))
                    .body(Body.builder().text(utf8(content.text)).html(utf8(content.html)).build())
                    .build(),
            ).build())
            .apply { if (mail.configurationSet.isNotBlank()) configurationSetName(mail.configurationSet) }
            .build()
        try {
            val res = ses.sendEmail(request)
            log.info("메일 발송 {} → {}: {}", purpose, LogMailSender.mask(to), res.messageId())
        } catch (e: SdkException) {
            // 샌드박스에서 인증 안 된 받는 사람(MessageRejected)도 여기로 온다
            log.error("메일 발송 실패 {} → {}: {}", purpose, LogMailSender.mask(to), e.message)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_FAILED", "메일을 보내지 못했어요. 잠시 뒤 다시 시도해 주세요")
        }
    }

    /** 보내는 이름이 한글이라 RFC 2047로 감싼다 (SES는 표시 이름의 비ASCII를 인코딩해서 받는다) */
    internal fun fromHeader(): String {
        val name = "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(mail.fromName.toByteArray(Charsets.UTF_8)) + "?="
        return "$name <${mail.from}>"
    }

    private fun utf8(s: String) = Content.builder().data(s).charset("UTF-8").build()
}
