package dev.gyeol.worker

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gyeol.worker")
data class WorkerProperties(
    /** 처리할 큐 역할. 쉼표로 여러 개 (예: "parse,identify"). EKS에서는 역할마다 Deployment를 나눠 따로 늘린다 */
    val role: String = "parse",
    val parseQueue: String = "gyeol-local-parse",
    /** 3번 실패한 메시지가 가는 큐. 여기 오면 사용자에게 실패로 보여 준다 */
    val parseDlq: String = "gyeol-local-parse-dlq",
    val uploadBucket: String = "gyeol-local-uploads",
    val listenTtl: Duration = Duration.ofDays(7),
    val identify: Identify = Identify(),
    val cover: Cover = Cover(),
    /** 외부 API가 바빠서(503) 실패한 메시지를 다시 받기까지 */
    val busyRetry: Duration = Duration.ofSeconds(30),
) {
    data class Cover(
        val queue: String = "gyeol-local-cover",
        /** 표지가 없던 곡을 다시 확인하기까지 */
        val retry: Duration = Duration.ofDays(30),
    )

    data class Identify(
        val queue: String = "gyeol-local-identify",
        val dlq: String = "gyeol-local-identify-dlq",
        /** 세션마다 바로 식별할 곡 수 (재생 상위). 가상 데이터 기준 300곡이 재생의 약 70% */
        val topN: Int = 300,
        /** 메시지 하나에 담는 곡 수. 곡당 최대 수십 초라 SQS 표시 제한 시간(15분) 안에 끝나게 작게 */
        val batch: Int = 10,
        /** 못 찾은 곡을 다시 물어보기까지 (MusicBrainz 데이터가 늘어나므로) */
        val notFoundRetry: Duration = Duration.ofDays(30),
        val fetcherUrl: String = "http://localhost:18081",
        val fetcherTimeout: Duration = Duration.ofMinutes(3),
    )

    fun has(r: String) = role.split(',').any { it.trim() == r }
}
