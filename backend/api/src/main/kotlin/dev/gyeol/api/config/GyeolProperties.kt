package dev.gyeol.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("gyeol")
data class GyeolProperties(
    val sessionTtl: Duration = Duration.ofDays(30),
    val webOrigin: String = "http://localhost:15173",
    val fetcherUrl: String = "http://localhost:18081",
    /** 고른 곡 식별을 맡기는 큐 (worker의 identify 큐와 같음) */
    val identifyQueue: String = "gyeol-local-identify",
    /** 고른 곡이 모두 이미 식별된 곡이면 바로 지식 인입을 맡긴다 (L3-1) */
    val enrichQueue: String = "gyeol-local-enrich",
    val upload: Upload = Upload(),
    /** 표지 이미지를 미리 받아 둔 비공개 버킷 */
    val coverBucket: String = "gyeol-local-covers",
    val room: Room = Room(),
) {
    /** 음악 방 (docs/todo/room-api-db.md) */
    data class Room(
        /** 방 담기 식별을 맡기는 큐 (worker room-song) */
        val songQueue: String = "gyeol-local-room-song",
        /** ④ 선물 상자 열기 → worker가 캐릭터를 만든다 */
        val giftQueue: String = "gyeol-local-gift-image",
        val maxMembers: Int = 5,
        val dailySongLimit: Int = 100,
        /** 한 사람이 동시에 식별을 기다리는 곡 수 */
        val maxPendingRequests: Int = 3,
        val pinMaxFails: Int = 5,
        val pinLock: Duration = Duration.ofMinutes(10),
        val draftTtl: Duration = Duration.ofHours(1),
        val codeTtl: Duration = Duration.ofMinutes(10),
        val codeMaxAttempts: Int = 5,
        val codeResendAfter: Duration = Duration.ofMinutes(1),
        /** 같은 이메일로 1시간에 보낼 수 있는 메일 수, 같은 IP로 1시간에 보낼 수 있는 메일 수 */
        val mailPerEmailHour: Int = 5,
        val mailPerIpHour: Int = 20,
        /** 방장 삭제 후 완전 삭제까지 */
        val purgeAfter: Duration = Duration.ofDays(7),
        /** IP별 분당 요청 수: 방 만들기·코드 확인, 참가, 다시 들어오기 */
        val draftPerMinute: Int = 10,
        val joinPerMinute: Int = 20,
        /** 열쇠 쿠키에 Secure를 붙일지 (로컬 http는 false) */
        val secureCookie: Boolean = false,
        /** ⑤ 코드 맞추기 이유 쓰기 → worker */
        val codeMatchQueue: String = "gyeol-local-code-match",
        val codeMatch: CodeMatch = CodeMatch(),
    )

    /**
     * ⑤ 코드 맞추기 (11-13 결정): 각자 5곡부터, 방당 하루 새 계산 30번(저장 결과 재사용은 안 셈),
     * 점수 비중은 장르 40·지도 거리 30·겹친 가수 20·겹친 노래 10에서 시작해 측정 뒤 조정
     */
    data class CodeMatch(
        val minSongs: Int = 5,
        val dailyLimit: Int = 30,
        val weightGenre: Double = 0.4,
        val weightDistance: Double = 0.3,
        val weightArtist: Double = 0.2,
        val weightSong: Double = 0.1,
    ) {
        fun weights() = dev.gyeol.core.room.CodeMatchWeights(weightGenre, weightDistance, weightArtist, weightSong)
    }

    data class Upload(
        val bucket: String = "gyeol-local-uploads",
        val maxBytes: Long = 20L * 1024 * 1024,
        val presignTtl: Duration = Duration.ofMinutes(10),
        /** 브라우저가 올릴 S3 주소. 서버가 쓰는 주소와 다를 때만 (k3d: 서버는 host.k3d.internal, 브라우저는 localhost) */
        val publicEndpoint: String? = null,
    )
}
