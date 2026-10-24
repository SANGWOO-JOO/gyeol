package dev.gyeol.worker

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gyeol.worker")
data class WorkerProperties(
    val role: String = "parse",
    val parseQueue: String = "gyeol-local-parse",
    val uploadBucket: String = "gyeol-local-uploads",
    val listenTtl: Duration = Duration.ofDays(7),
)
