package dev.gyeol.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gyeol")
data class GyeolProperties(
    val sessionTtl: Duration = Duration.ofDays(30),
    val webOrigin: String = "http://localhost:15173",
    val fetcherUrl: String = "http://localhost:18081",
    val upload: Upload = Upload(),
) {
    data class Upload(
        val bucket: String = "gyeol-local-uploads",
        val maxBytes: Long = 20L * 1024 * 1024,
        val presignTtl: Duration = Duration.ofMinutes(10),
        /** 브라우저가 올릴 S3 주소. 서버가 쓰는 주소와 다를 때만 (k3d: 서버는 host.k3d.internal, 브라우저는 localhost) */
        val publicEndpoint: String? = null,
    )
}
