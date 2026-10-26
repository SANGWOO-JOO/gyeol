package dev.gyeol.worker.identify

import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.worker.WorkerProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

/** external-fetcher의 곡 식별 (외부 호출은 fetcher 한 곳에서만). 장애·한도 초과는 예외 → 큐가 다시 시도 */
@Component
class FetcherClient(props: WorkerProperties) {
    private val http = RestClient.builder()
        .baseUrl(props.identify.fetcherUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(3))
                setReadTimeout(props.identify.fetcherTimeout)
            },
        )
        .build()

    /** 찾으면 곡 정보, 못 찾으면 null */
    fun identify(artist: String, title: String): TrackInfo? {
        val r = http.post().uri("/internal/musicbrainz/identify")
            .body(mapOf("artist" to artist, "title" to title))
            .retrieve()
            .body(JsonNode::class.java) ?: error("fetcher 응답이 비어 있습니다")
        if (r.path("status").asString("") != "MATCHED") return null
        val a = r.path("artist")
        val rel = r.path("release")
        return TrackInfo(
            recordingMbid = UUID.fromString(r.path("recording").path("mbid").asString()),
            title = r.path("recording").path("title").asString(),
            artist = ArtistInfo(
                mbid = UUID.fromString(a.path("mbid").asString()),
                name = a.path("name").asString(),
                sortName = a.path("sortName").asString("").ifEmpty { null },
                aliases = a.path("aliases").toList().map { it.asString() },
            ),
            releaseMbid = rel.path("mbid").asString("").ifEmpty { null }?.let(UUID::fromString),
            releaseGroupMbid = rel.path("groupMbid").asString("").ifEmpty { null }?.let(UUID::fromString),
            releaseTitle = rel.path("title").asString("").ifEmpty { null },
            releaseDate = rel.path("date").asString("").ifEmpty { null },
            releaseType = rel.path("type").asString("").ifEmpty { null },
        )
    }
}
