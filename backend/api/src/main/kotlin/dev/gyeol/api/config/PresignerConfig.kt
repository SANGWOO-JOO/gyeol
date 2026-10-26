package dev.gyeol.api.config

import software.amazon.awssdk.regions.providers.AwsRegionProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * 업로드 서명 URL을 브라우저가 닿는 주소로 만든다.
 * k3d에서는 파드가 LocalStack을 host.k3d.internal로 부르지만, 브라우저는 localhost로 올려야 한다.
 * 설정이 없으면(운영) Spring Cloud AWS가 만든 기본 서명기를 그대로 쓴다.
 * 환경 변수로는 GYEOL_UPLOAD_PUBLICENDPOINT (빈 값을 넣으면 켜지므로 쓰지 않을 때는 변수 자체를 두지 않는다).
 */
@Configuration
@ConditionalOnProperty("gyeol.upload.public-endpoint")
class PresignerConfig {
    @Bean
    @Primary
    fun publicPresigner(props: GyeolProperties, credentials: AwsCredentialsProvider, region: AwsRegionProvider): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI(props.upload.publicEndpoint!!))
            .region(region.region)
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
}
