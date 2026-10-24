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
    )
}
