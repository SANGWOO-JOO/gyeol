package dev.gyeol.fetcher.youtube

import dev.gyeol.fetcher.FetcherProperties
import dev.gyeol.fetcher.RateGate
import dev.gyeol.fetcher.UpstreamException
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
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

    /**
     * 영상의 채널 프로필 이미지 주소 (가수 사진이 Commons에 없을 때). 이미지는 받지 않고 주소만 돌려준다.
     * YouTube API 정책: 30일 안에 갱신·삭제, YouTube 출처 표시 (호출 2번, 할당량 2단위)
     */
    private val images = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .connectTimeout(java.time.Duration.ofSeconds(10)).build()

    /** 프로필 이미지를 내려받아 기본 프로필인지 본다 (저장하지 않음). 받지 못하면 기본이 아닌 것으로 둔다 */
    private fun isDefaultAvatar(url: String): Boolean = try {
        val res = images.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI(url)).GET().timeout(java.time.Duration.ofSeconds(20)).build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray(),
        )
        res.statusCode() in 200..299 && DefaultAvatar.isDefault(res.body())
    } catch (e: Exception) {
        log.warn("프로필 이미지 확인 실패: {}", e.javaClass.simpleName)
        false
    }

    fun channelImage(videoId: String): String? {
        if (props.youtube.apiKey.isBlank()) throw UpstreamException("YouTube API 키(YOUTUBE_API_KEY)가 설정되지 않았습니다")
        fun call(path: String, fields: String, id: String): JsonNode? {
            gate.acquire()
            return try {
                http.get().uri { b -> b.path(path).queryParam("part", "snippet").queryParam("fields", fields).queryParam("id", id).queryParam("key", props.youtube.apiKey).build() }
                    .retrieve().body(JsonNode::class.java)
            } catch (e: RestClientResponseException) {
                log.warn("YouTube API 응답 오류 {}", e.statusCode.value())
                throw UpstreamException("YouTube API가 ${e.statusCode.value()}을(를) 돌려주었습니다")
            } catch (e: Exception) {
                log.warn("YouTube API 호출 실패: {}", e.javaClass.simpleName)
                throw UpstreamException("YouTube API에 연결하지 못했습니다")
            }
        }
        val channel = call("/videos", "items(snippet(channelId))", videoId)?.path("items")?.firstOrNull()?.path("snippet")?.path("channelId")?.asString("")?.ifEmpty { null } ?: return null
        val url = call("/channels", "items(snippet(thumbnails(medium(url))))", channel)?.path("items")?.firstOrNull()
            ?.path("snippet")?.path("thumbnails")?.path("medium")?.path("url")?.asString("")?.ifEmpty { null } ?: return null
        // 채널 이름 첫 글자만 있는 기본 프로필은 이미지가 없는 것으로 본다 (화면은 대표 앨범 표지로 넘어간다)
        return url.takeUnless { java.net.URI(url).host?.endsWith(".ggpht.com") == true && isDefaultAvatar(it) }
    }
}

/** api가 부른다 (L2-3b). 키가 없거나 장애면 503 → api는 판정을 건너뛰고 L1처럼 동작한다 */
@RestController
class YouTubeController(private val client: YouTubeClient) {
    private val idPattern = Regex("""^[\w-]{6,32}$""")

    @GetMapping("/internal/youtube/channel-image")
    fun channelImage(@RequestParam videoId: String): ResponseEntity<Map<String, String>> {
        require(idPattern.matches(videoId)) { "영상 ID 형식이 올바르지 않습니다" }
        return client.channelImage(videoId)?.let { ResponseEntity.ok(mapOf("url" to it)) } ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/internal/youtube/categories")
    fun categories(@RequestBody req: CategoriesRequest): CategoriesResult {
        val ids = req.videoIds.orEmpty()
        require(ids.size <= 1000) { "videoIds는 1,000개 이하여야 합니다" }
        require(ids.all { idPattern.matches(it) }) { "영상 ID 형식이 올바르지 않습니다" }
        return client.categories(ids)
    }
}
