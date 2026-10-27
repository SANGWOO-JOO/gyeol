package dev.gyeol.worker.describe

import dev.gyeol.core.knowledge.VectorRepository
import dev.gyeol.worker.WorkerProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Duration

/**
 * 곡 설명 → 벡터 (L3-3). embed 서비스를 부르고 track_vector에 저장한다.
 * 곡 설명끼리 비교하므로 "query" 접두어 (L3-0 측정: 곡 설명끼리 query 67% vs passage 50%).
 * 문장이 바뀌지 않았으면 다시 만들지 않는다. 서비스 장애는 예외 → 큐가 다시 시도
 */
@Component
class EmbedClient(props: WorkerProperties, private val vectors: VectorRepository) {
    private val http = RestClient.builder()
        .baseUrl(props.embed.url)
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(Duration.ofSeconds(3)); setReadTimeout(props.embed.timeout) })
        .build()

    /** 벡터를 새로 만든 곡 수 */
    fun embedTracks(trackIds: Collection<Long>): Int {
        val todo = trackIds.mapNotNull { vectors.descriptorText(it) }
            .map { it to sha256(it.text) }
            .filter { (t, h) -> vectors.currentHash(t.trackId) != h }
        if (todo.isEmpty()) return 0
        todo.chunked(32).forEach { group ->
            val r = http.post().uri("/embed").body(mapOf("texts" to group.map { it.first.text }, "kind" to "query"))
                .retrieve().body(JsonNode::class.java) ?: error("embed 응답이 비어 있습니다")
            val model = r.path("model").asString()
            r.path("vectors").toList().forEachIndexed { i, v ->
                val (t, h) = group[i]
                vectors.save(t.trackId, model, v.toList().map { it.asDouble().toFloat() }.toFloatArray(), h)
            }
        }
        return todo.size
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
