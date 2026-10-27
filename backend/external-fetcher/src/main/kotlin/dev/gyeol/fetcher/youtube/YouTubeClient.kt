package dev.gyeol.fetcher.youtube

import dev.gyeol.fetcher.FetcherProperties
import dev.gyeol.fetcher.RateGate
import dev.gyeol.fetcher.UpstreamException
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode

data class CategoriesRequest(val videoIds: List<String>?)
/** [categories]: 영상 ID → categoryId (10이 Music). 결과에 없는 ID는 삭제·비공개 등으로 찾지 못한 영상 */
data class CategoriesResult(val categories: Map<String, String>, val calls: Int, val ms: Long)

/**
 * YouTube Data API videos.list로 영상 카테고리만 받는다 (L2-3b 음악 판정).
 * 제목·설명 등 다른 데이터는 받지 않고 저장하지 않는다 (YouTube API 정책: 판정 용도로만, 30일 안에 갱신·삭제).
 * 50개씩 묶어 부르고, 호출 1번에 할당량 1단위.
 */
@Component
class YouTubeClient(private val props: FetcherProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val gate = RateGate(props.youtube.minInterval, props.youtube.maxWait)
    private val http = RestClient.builder()
        .baseUrl(props.youtube.baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(props.youtube.timeout); setReadTimeout(props.youtube.timeout) })
        .build()

    fun categories(ids: List<String>): CategoriesResult {
        if (props.youtube.apiKey.isBlank()) throw UpstreamException("YouTube API 키(YOUTUBE_API_KEY)가 설정되지 않았습니다")
        val started = System.nanoTime()
        val out = HashMap<String, String>()
        val batches = ids.distinct().chunked(50)
        for (batch in batches) {
            gate.acquire()
            val body = try {
                http.get()
                    .uri { b -> b.path("/videos").queryParam("part", "snippet").queryParam("fields", "items(id,snippet(categoryId))").queryParam("id", batch.joinToString(",")).queryParam("key", props.youtube.apiKey).build() }
                    .retrieve().body(JsonNode::class.java)
            } catch (e: RestClientResponseException) {
                // 키 값이 로그에 남지 않게 상태 코드만 남긴다
                log.warn("YouTube API 응답 오류 {}", e.statusCode.value())
                throw UpstreamException("YouTube API가 ${e.statusCode.value()}을(를) 돌려주었습니다")
            } catch (e: Exception) {
                log.warn("YouTube API 호출 실패: {}", e.javaClass.simpleName)
                throw UpstreamException("YouTube API에 연결하지 못했습니다")
            }
            body?.path("items")?.forEach { out[it.path("id").asString()] = it.path("snippet").path("categoryId").asString("") }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        log.info("YouTube 카테고리: {}개 요청, {}개 응답, 호출 {}번, {}ms", ids.size, out.size, batches.size, ms)
        return CategoriesResult(out, batches.size, ms)
    }
}

/** api가 부른다 (L2-3b). 키가 없거나 장애면 503 → api는 판정을 건너뛰고 L1처럼 동작한다 */
@RestController
class YouTubeController(private val client: YouTubeClient) {
    private val idPattern = Regex("""^[\w-]{6,32}$""")

    @PostMapping("/internal/youtube/categories")
    fun categories(@RequestBody req: CategoriesRequest): CategoriesResult {
        val ids = req.videoIds.orEmpty()
        require(ids.size <= 1000) { "videoIds는 1,000개 이하여야 합니다" }
        require(ids.all { idPattern.matches(it) }) { "영상 ID 형식이 올바르지 않습니다" }
        return client.categories(ids)
    }
}
