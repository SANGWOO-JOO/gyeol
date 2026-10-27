package dev.gyeol.api.video

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.session.SessionService
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.SessionStatus
import dev.gyeol.core.video.VideoCheckRepository
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

data class VideoCheckRequest(val videoIds: List<String>?)

/**
 * [musicIds]: 음악 카테고리(10)로 판정된 영상. [skipped]면 판정을 못 했으니(키 없음·장애) 브라우저는 L1처럼 음악 기록만 올린다.
 * [fromCache]·[fromApi]: 판정 출처별 개수 (캐시 효과 측정용)
 */
data class VideoCheckResponse(val musicIds: List<String>, val checked: Int, val fromCache: Int, val fromApi: Int, val skipped: Boolean)

/**
 * 일반 영상 음악 판정 (L2-3b). 브라우저는 미분류 영상의 **ID만** 보낸다 (제목은 보내지 않음).
 * 판정 결과를 받아 음악인 영상만 제목을 포함해 업로드한다.
 */
@RestController
class VideoCheckController(
    private val sessions: SessionService,
    private val cache: VideoCheckRepository,
    props: GyeolProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val idPattern = Regex("""^[\w-]{6,32}$""")
    private val fetcher = RestClient.builder()
        .baseUrl(props.fetcherUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(Duration.ofSeconds(1)); setReadTimeout(Duration.ofSeconds(30)) })
        .build()

    @PostMapping("/api/sessions/{token}/video-check")
    fun check(@PathVariable token: String, @RequestBody body: VideoCheckRequest): VideoCheckResponse {
        val session = sessions.find(token)
        if (session.entry != Entry.TAKEOUT || session.status != SessionStatus.CREATED) throw ApiException.invalidState("업로드 전 청취 기록 세션에서만 판정할 수 있습니다")
        val ids = body.videoIds.orEmpty().distinct()
        if (ids.size > MAX) throw ApiException.validation("영상 ID는 ${MAX}개 이하여야 합니다")
        if (!ids.all { idPattern.matches(it) }) throw ApiException.validation("영상 ID 형식이 올바르지 않습니다")

        val cached = cache.fresh(ids, Instant.now().minus(RETENTION))
        val missing = ids.filter { it !in cached }
        val fetched = if (missing.isEmpty()) emptyMap() else try {
            val r = fetcher.post().uri("/internal/youtube/categories").body(mapOf("videoIds" to missing)).retrieve().body(JsonNode::class.java)
            val cats = r?.path("categories")?.properties()?.associate { (k, v) -> k to v.asString("") }.orEmpty()
            cache.save(missing, cats)
            cats
        } catch (e: Exception) {
            log.warn("음악 판정 건너뜀: {}", e.message)
            return VideoCheckResponse(ids.filter { cached[it] == MUSIC }, ids.size, cached.size, 0, skipped = true)
        }
        val all = cached + fetched
        return VideoCheckResponse(ids.filter { all[it] == MUSIC }, ids.size, cached.size, missing.size, skipped = false)
    }

    companion object {
        const val MAX = 1000
        const val MUSIC = "10"
        /** YouTube API 정책: 받은 데이터는 30일 안에 갱신하거나 지운다 */
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
