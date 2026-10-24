package dev.gyeol.api.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@EnableConfigurationProperties(GyeolProperties::class)
class WebConfig(private val props: GyeolProperties) : WebMvcConfigurer {
    /** 로컬 웹 개발 서버(Vite)에서 부를 수 있게. 배포 때는 CloudFront가 같은 도메인으로 묶어 필요 없다. */
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(props.webOrigin)
            .allowedMethods("GET", "POST", "DELETE")
    }
}
