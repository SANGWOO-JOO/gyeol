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
    val llm: Llm = Llm(),
    val enrich: Enrich = Enrich(),
    val embed: Embed = Embed(),
    /** 외부 API가 바빠서(503) 실패한 메시지를 다시 받기까지 */
    val busyRetry: Duration = Duration.ofSeconds(30),
) {
    /** L3-3 embed 서비스 (Python, intfloat/multilingual-e5-small ONNX) */
    data class Embed(
        val url: String = "http://localhost:18084",
        val timeout: Duration = Duration.ofSeconds(30),
    )

    data class Enrich(
        val queue: String = "gyeol-local-enrich",
        val dlq: String = "gyeol-local-enrich-dlq",
        /** 청취 기록 세션에서 보강할 곡 수 (재생 상위). 고른 곡은 전부 */
        val topN: Int = 50,
        /** 메시지 하나에 담는 곡 수 (곡 하나에 MusicBrainz 몇 번 + 위키·Last.fm 몇 번) */
        val batch: Int = 5,
        /** 지식을 다시 가져오기까지 */
        val refresh: Duration = Duration.ofDays(90),
    )

    data class Llm(
        /** fake: 흔한 제목 형태만 규칙으로 흉내 (테스트·부하 실험), bedrock: 실제 호출 */
        val mode: String = "fake",
        /** Haiku 4.5는 추론 프로필로만 부를 수 있다 */
        val model: String = "global.anthropic.claude-haiku-4-5-20251001-v1:0",
        val region: String = "ap-northeast-2",
        val maxTokens: Int = 4000,
        /** 측정용 내부 엔드포인트 (POST /internal/llm/clean). 로컬에서만 켠다 */
        val debugEndpoint: Boolean = false,
    )

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
