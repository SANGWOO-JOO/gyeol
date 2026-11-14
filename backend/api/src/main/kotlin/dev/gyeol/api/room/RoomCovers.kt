package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.room.RoomSongRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.time.Duration

/**
 * 방 화면 곡 표지 (I-14). Cover Art Archive 외부 주소는 느려서, S3 저장본이 있는 곡은 우리 주소로 준다.
 * 응답의 coverPath가 있으면 그 주소, 없으면 지금처럼 coverUrl(외부)을 쓴다.
 * 범위: 방 참가자(방 열쇠)만, 우리 DB 곡 중 저장본이 있는 곡. 가수 화면은 우리 DB의 어떤 가수 곡이든 나올 수 있어
 * 방 곡으로 좁히지 않는다 (앨범 표지라 개인 정보가 아니고, 방 열쇠가 없으면 401).
 */
@Service
class RoomCovers(private val songs: RoomSongRepository, private val s3: S3Client, private val props: GyeolProperties) {

    fun path(rid: String, trackId: Long, stored: Boolean): String? = if (stored) "/api/rooms/$rid/covers/$trackId" else null

    /** 여러 곡을 한 번에 (가수 화면 곡 목록) */
    fun paths(rid: String, trackIds: Collection<Long>): Map<Long, String> =
        songs.storedCovers(trackIds).associateWith { "/api/rooms/$rid/covers/$it" }

    fun image(trackId: Long): ResponseEntity<ByteArray> {
        val key = songs.coverKey(trackId) ?: throw notFound()
        val obj = try { s3.getObjectAsBytes { it.bucket(props.coverBucket).key(key) } } catch (e: NoSuchKeyException) { throw notFound() }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(obj.response().contentType() ?: "image/jpeg"))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .body(obj.asByteArray())
    }

    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "COVER_NOT_FOUND", "표지가 없어요")
}

@RestController
class RoomCoverController(private val covers: RoomCovers) {
    /** 방 참가자만 (방 열쇠 확인). 저장본이 없으면 404 → 웹은 coverUrl을 쓴다 */
    @GetMapping("/api/rooms/{rid}/covers/{trackId}")
    fun cover(@PathVariable rid: String, @PathVariable trackId: Long, @RoomMember ctx: RoomContext): ResponseEntity<ByteArray> = covers.image(trackId)
}
