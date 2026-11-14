package dev.gyeol.worker.identify

import dev.gyeol.core.identify.ArtistInfo
import dev.gyeol.core.identify.CoverTarget
import dev.gyeol.core.identify.TrackInfo
import dev.gyeol.worker.WorkerProperties
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
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

    /** 원곡 음반의 앞표지 주소. 없으면 null (다른 음반 표지로 대신하지 않음) */
    fun cover(t: CoverTarget): String? =
        http.post().uri("/internal/coverart/front")
            .body(mapOf("releaseMbid" to t.releaseMbid?.toString(), "releaseGroupMbid" to t.releaseGroupMbid?.toString(), "releaseTitle" to t.releaseTitle))
            .retrieve()
            .body(JsonNode::class.java)
            ?.path("url")?.asString("")?.ifEmpty { null }

    /** Commons 가수 사진 정보 (url, pageUrl, author, license). 없으면 null */
    fun artistCommons(mbid: String): JsonNode? = try {
        http.get().uri { b -> b.path("/internal/artist-image/commons").queryParam("mbid", mbid).build() }.retrieve().body(JsonNode::class.java)
    } catch (e: HttpClientErrorException.NotFound) {
        null
    }

    /** Topic 채널 이름으로 찾은 MusicBrainz 가수 mbid. 확실하지 않으면 null */
    fun findArtist(name: String, titles: List<String>): String? = try {
        http.get().uri { b ->
            b.path("/internal/artist-image/find-artist").queryParam("name", "{n}")
            titles.indices.forEach { i -> b.queryParam("title", "{t$i}") }
            b.build(mapOf("n" to name) + titles.mapIndexed { i, t -> "t$i" to t })
        }.retrieve()
            .body(JsonNode::class.java)?.path("mbid")?.asString("")?.ifEmpty { null }
    } catch (e: HttpClientErrorException.NotFound) {
        null
    }

    fun artistFile(url: String): ResponseEntity<ByteArray> =
        http.get().uri { b -> b.path("/internal/artist-image/file").queryParam("url", url).build() }.retrieve().toEntity(ByteArray::class.java)

    /** 표지 이미지 내려받기. 없으면 예외(404) */
    fun coverImage(url: String): ResponseEntity<ByteArray> =
        http.get().uri { b -> b.path("/internal/coverart/image").queryParam("url", url).build() }.retrieve().toEntity(ByteArray::class.java)

    /** 지식 인입 (L3-1): type은 recording·work·release-group·artist */
    fun knowledge(type: String, mbid: String, artist: String? = null, title: String? = null): JsonNode =
        http.get().uri { b ->
            b.path("/internal/knowledge/$type/$mbid").apply { artist?.let { queryParam("artist", it) }; title?.let { queryParam("title", it) } }.build()
        }.retrieve().body(JsonNode::class.java) ?: error("fetcher 응답이 비어 있습니다")

    /** L3-4 추천 후보 (Last.fm): 비슷한 곡 / 비슷한 가수 / 가수 인기곡 */
    fun lastfm(path: String, params: Map<String, Any>): JsonNode =
        http.get().uri { b -> b.path("/internal/lastfm/$path").apply { params.forEach { (k, v) -> queryParam(k, v) } }.build() }
            .retrieve().body(JsonNode::class.java) ?: error("fetcher 응답이 비어 있습니다")

    /** 음악 지도 초기 가수: Last.fm 나라별 인기 가수 (country는 Last.fm 나라 이름) */
    fun geoTop(country: String, page: Int): JsonNode = lastfm("geo-top", mapOf("country" to country, "page" to page, "limit" to 100))

    /** MusicBrainz 가수 (출신 국가·별칭). mbid가 없으면 이름 검색 1위(점수 95 이상)만 */
    fun mbArtist(mbid: String?, name: String): JsonNode =
        http.get().uri { b -> b.path("/internal/musicbrainz/artist").apply { if (mbid != null) queryParam("mbid", mbid) else queryParam("name", name) }.build() }
            .retrieve().body(JsonNode::class.java) ?: error("fetcher 응답이 비어 있습니다")

    /** I-30 ③ 가수의 녹음 목록 100개씩 (total·items[mbid,title,firstReleaseDate,video]) */
    fun artistRecordings(mbid: String, offset: Int): JsonNode =
        http.get().uri { b -> b.path("/internal/musicbrainz/artist-recordings").queryParam("mbid", mbid).queryParam("offset", offset).build() }
            .retrieve().body(JsonNode::class.java) ?: error("fetcher 응답이 비어 있습니다")

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
            lengthMs = r.path("recording").path("lengthMs").takeIf { it.isNumber }?.asLong(),
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
            languageVersion = r.path("languageVersion").asBoolean(false),
        )
    }
}
