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
    val recommend: Recommend = Recommend(),
    val guess: Guess = Guess(),
    val artistImage: ArtistImageProps = ArtistImageProps(),
    val room: RoomProps = RoomProps(),
    val map: MapProps = MapProps(),
    val gift: GiftProps = GiftProps(),
    val codeMatch: CodeMatchProps = CodeMatchProps(),
    /** 청취 기록 세션에서 식별 뒤 보강·추천까지 자동으로 이어 갈지 (기본은 분석까지만) */
    val autoRecommend: Boolean = false,
    /** 외부 API가 바빠서(503) 실패한 메시지를 다시 받기까지 */
    val busyRetry: Duration = Duration.ofSeconds(30),
) {
    /** 음악 지도 데이터 만들기 내부 엔드포인트 (/internal/map 아래, 로컬에서만 켠다) */
    data class MapProps(val adminEndpoint: Boolean = false, val knowledgeRetryWait: Duration = Duration.ofSeconds(30))

    /**
     * ④ 50곡 선물 캐릭터 (room-plan.md ⑯). 이미지 모델은 us-west-2에만 있어 그 리전으로 부른다 (보내는 것은 장르·분위기 프롬프트뿐).
     * llm.mode가 bedrock일 때만 실제 호출, 그 밖에는 기본 캐릭터 그림을 S3에 저장하는 가짜 생성
     */
    data class GiftProps(
        val queue: String = "gyeol-local-gift-image",
        val dlq: String = "gyeol-local-gift-image-dlq",
        val imageModel: String = "stability.stable-image-core-v1:1",
        val imageRegion: String = "us-west-2",
        /** 서비스 전체 하루(한국 날짜) 이미지 생성 수. 넘으면 기본 캐릭터 (11-13 결정: 50장 ≈ $2) */
        val dailyImageLimit: Int = 50,
    )

    /** ⑤ 코드 맞추기 이유 쓰기 (점수는 API가 계산) */
    data class CodeMatchProps(val queue: String = "gyeol-local-code-match", val dlq: String = "gyeol-local-code-match-dlq")

    /** 음악 방 담기 식별 (docs/todo/room-api-db.md) */
    data class RoomProps(val songQueue: String = "gyeol-local-room-song", val songDlq: String = "gyeol-local-room-song-dlq")

    /** 가수 이미지 (docs/todo/artist-images.md). 사진은 표지와 같은 버킷의 artists/ 아래에 둔다 */
    data class ArtistImageProps(
        val queue: String = "gyeol-local-artist-image",
        /** 세션에서 많이 들은 가수 몇 명까지 */
        val top: Int = 50,
        /** YouTube 주소·없음 결과를 다시 확인하기까지 (YouTube API 정책 30일) */
        val retry: Duration = Duration.ofDays(30),
    )

    /** 방송·일반 채널 영상에서 LLM으로 가수·곡 뽑기 */
    data class Guess(
        val queue: String = "gyeol-local-guess",
        /** 이만큼 들은 영상만 (5회 이상이 일반 채널 미식별 재생의 약 85%) */
        val minPlays: Int = 5,
        /** 세션마다 LLM에 보내는 영상 수 상한 (비용 제한) */
        val maxVideos: Int = 500,
        val batch: Int = 10,
        val retry: Duration = Duration.ofDays(30),
    )

    /** L3-4 추천 (docs/l3-design.md 11장) */
    data class Recommend(
        val queue: String = "gyeol-local-recommend",
        val dlq: String = "gyeol-local-recommend-dlq",
        val candidates: Int = 50,
        val picks: Int = 20,
        val perArtist: Int = 2,
        /** 점수 가중치: 프로필 벡터 유사도 · Last.fm 유사도 · 같은 작곡가·프로듀서 (L3-5 피드백으로 조정) */
        val wVector: Double = 0.5,
        val wLastfm: Double = 0.3,
        val wCredit: Double = 0.2,
        /** 후보 보강 메시지 하나에 담는 곡 수 */
        val batch: Int = 5,
        /** 재정렬 모델 (비우면 gyeol.worker.llm.model) */
        val model: String = "",
    )

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
        /** 표지 이미지를 미리 받아 두는 비공개 버킷 */
        val bucket: String = "gyeol-local-covers",
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
