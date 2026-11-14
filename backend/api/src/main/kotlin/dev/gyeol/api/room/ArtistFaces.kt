package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.identify.ArtistImageRepository
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
 * 가수 행성 얼굴 (I-44). kind:
 * - PHOTO: Wikimedia Commons 사진 (S3, 방 열쇠를 확인하고 API가 내려 줌). author·license·pageUrl로 저작자 표시
 * - COVER: 사진이 없을 때 이 방에 담긴 그 가수 곡 중 하나의 표지 (흐린 행성은 방 곡이 없어 COVER가 되지 않는다)
 * - NONE: 둘 다 없음. galaxyId(장르 구역)로 색만 칠한다
 * YouTube 프로필은 지도 얼굴로 쓰지 않는다
 */
data class Face(
    val kind: String, val url: String?, val galaxyId: Long?,
    val author: String? = null, val license: String? = null, val pageUrl: String? = null,
)
/** 가수 판·가수 화면의 사진 (Commons일 때만) */
data class PhotoView(val url: String, val author: String?, val license: String?, val pageUrl: String?)

@Service
class ArtistFaces(private val images: ArtistImageRepository, private val s3: S3Client, private val props: GyeolProperties) {

    fun imagePath(rid: String, artistId: Long) = "/api/rooms/$rid/artists/$artistId/image"

    /** [artists]: 가수 id → (장르 구역, 방 곡 표지 하나). 사진은 한 번에 읽는다 */
    fun faces(rid: String, artists: Map<Long, Pair<Long?, String?>>): Map<Long, Face> {
        val photos = images.commonsPhotos(artists.keys)
        return artists.mapValues { (id, v) ->
            val (galaxy, cover) = v
            val p = photos[id]
            when {
                p != null -> Face("PHOTO", imagePath(rid, id), galaxy, p.author, p.license, p.pageUrl)
                cover != null -> Face("COVER", cover, galaxy)
                else -> Face("NONE", null, galaxy)
            }
        }
    }

    fun photo(rid: String, artistId: Long): PhotoView? =
        images.commonsPhotos(listOf(artistId))[artistId]?.let { PhotoView(imagePath(rid, artistId), it.author, it.license, it.pageUrl) }

    /** 사진이 늘면 지도 ETag가 바뀌게 (일괄 채우는 동안 웹이 새 얼굴을 받도록) */
    fun stamp(): String = images.commonsStamp()

    fun image(artistId: Long): ResponseEntity<ByteArray> {
        val p = images.commonsPhotos(listOf(artistId))[artistId] ?: throw notFound()
        val obj = try { s3.getObjectAsBytes { it.bucket(props.coverBucket).key(p.s3Key) } } catch (e: NoSuchKeyException) { throw notFound() }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(obj.response().contentType() ?: "image/jpeg"))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .body(obj.asByteArray())
    }

    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "사진이 없어요")
}

@RestController
class ArtistFaceController(private val faces: ArtistFaces) {
    /** 방 참가자만 (방 열쇠 확인). Commons 사진이 있는 가수만, 없으면 404 */
    @GetMapping("/api/rooms/{rid}/artists/{artistId}/image")
    fun image(@PathVariable rid: String, @PathVariable artistId: Long, @RoomMember ctx: RoomContext): ResponseEntity<ByteArray> = faces.image(artistId)
}
