package dev.gyeol.api.room

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.room.GiftBox
import dev.gyeol.core.room.GiftCharacter
import dev.gyeol.core.room.GiftRepository
import dev.gyeol.core.room.GiftSummary
import dev.gyeol.core.room.RoomRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class GiftBoxView(val id: Long, val milestone: Int, val status: String, val earnedAt: Instant, val readyOn: LocalDate?, val openedAt: Instant?, val characterId: Long?)
data class CharacterView(val id: Long, val giftBoxId: Long?, val isFallback: Boolean, val createdAt: Instant)
data class GiftsResponse(val summary: GiftSummary, val boxes: List<GiftBoxView>, val characters: List<CharacterView>, val avatarCharacterId: Long?)
/** 캐릭터를 만든 재료: 구역 이름과 그 구역 노래 수 (가수 이름 없음) */
data class GenreShare(val galaxy: String, val songs: Int)

/** genreMix: 열린 상자(OPENED)만, 상자를 연 시각까지 담은 노래의 구역 상위 4개 (웹 4-k "시티팝 25곡 · 댄스 팝 15곡") */
data class GiftBoxDetail(val box: GiftBoxView, val character: CharacterView?, val genreMix: List<GenreShare> = emptyList())
data class AvatarRequest(val characterId: Long?)

private fun GiftBox.view() = GiftBoxView(id, milestone, status.name, earnedAt, readyOn, openedAt, characterId)
private fun GiftCharacter.view() = CharacterView(id, giftBoxId, isFallback, createdAt)

/**
 * ④ 50곡 선물 (room-plan.md ⑯, room-api-db.md 3절). 상자를 열면 worker(gift-image 큐)가 캐릭터를 만든다.
 * 캐릭터 그림은 비공개 S3(covers 버킷 characters/…)라 같은 방 참가자에게만 API가 전달한다. 기본 캐릭터(preset:N)는 내장 그림.
 */
@Service
class GiftService(
    private val gifts: GiftRepository,
    private val rooms: RoomRepository,
    private val sender: AfterCommitSender,
    private val json: JsonMapper,
    private val props: GyeolProperties,
    private val s3: S3Client,
) {
    fun list(ctx: RoomContext): GiftsResponse {
        val m = ctx.member
        val today = todayKst()
        gifts.promoteWaiting(m.id, today)
        return GiftsResponse(
            gifts.summary(m.id, today), gifts.boxes(m.id).map { it.view() }, gifts.characters(m.id).map { it.view() },
            rooms.member(m.id)?.avatarCharacterId,
        )
    }

    fun box(ctx: RoomContext, boxId: Long): GiftBoxDetail {
        val b = myBox(ctx, boxId)
        val mix = if (b.status == dev.gyeol.core.room.GiftStatus.OPENED) {
            gifts.genreMix(b.memberId, 4, b.openedAt).map { GenreShare(it.first, it.second) }
        } else emptyList()
        return GiftBoxDetail(b.view(), b.characterId?.let(gifts::character)?.view(), mix)
    }

    /** READY 상자만 연다 → GENERATING, 커밋 뒤 큐 */
    @Transactional
    fun open(ctx: RoomContext, boxId: Long): GiftBoxDetail {
        myBox(ctx, boxId)
        if (!gifts.startOpening(boxId, ctx.member.id)) throw ApiException.conflict("GIFT_NOT_READY", "지금 열 수 있는 상자가 아니에요")
        sender.send(props.room.giftQueue, json.writeValueAsString(mapOf("boxId" to boxId)))
        return GiftBoxDetail(gifts.box(boxId)!!.view(), null)
    }

    /** 내 캐릭터로 얼굴 바꾸기 (null이면 기본 얼굴 원). 참가자 판·지도 얼굴이 바뀌므로 map_rev를 올린다 */
    @Transactional
    fun setAvatar(ctx: RoomContext, body: AvatarRequest): MemberView {
        body.characterId?.let { id ->
            val c = gifts.character(id)
            if (c == null || c.memberId != ctx.member.id) throw ApiException(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "내 캐릭터가 아니에요")
        }
        gifts.setAvatar(ctx.member.id, body.characterId)
        rooms.bumpMapRev(ctx.room.id)
        return ctx.member.copy(avatarCharacterId = body.characterId).view()
    }

    /** 같은 방 참가자의 캐릭터만 보인다 */
    fun image(ctx: RoomContext, characterId: Long): ResponseEntity<ByteArray> {
        val c = gifts.character(characterId)
        val owner = c?.let { rooms.member(it.memberId) }
        if (c == null || owner == null || owner.roomId != ctx.room.id) throw ApiException(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "없는 캐릭터예요")
        val (bytes, type) = if (c.s3Key.startsWith("preset:")) {
            val n = c.s3Key.removePrefix("preset:").toIntOrNull()?.takeIf { it in 1..GiftRepository.PRESETS }
                ?: throw ApiException(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "없는 캐릭터예요")
            preset(n) to "image/svg+xml"
        } else {
            try {
                val obj = s3.getObjectAsBytes { it.bucket(props.coverBucket).key(c.s3Key) }
                obj.asByteArray() to (obj.response().contentType() ?: "image/png")
            } catch (e: NoSuchKeyException) {
                throw ApiException(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "없는 캐릭터예요")
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(type))
            // 그림은 바뀌지 않는다. 비공개라 공유 캐시에는 두지 않는다
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            .body(bytes)
    }

    private fun preset(n: Int): ByteArray =
        javaClass.getResourceAsStream("/gift/preset-$n.svg")?.use { it.readBytes() } ?: error("기본 캐릭터 그림이 없습니다: $n")

    private fun myBox(ctx: RoomContext, boxId: Long): GiftBox =
        gifts.box(boxId)?.takeIf { it.memberId == ctx.member.id } ?: throw ApiException(HttpStatus.NOT_FOUND, "GIFT_NOT_FOUND", "내 상자가 아니에요")
}

@RestController
class GiftController(private val service: GiftService) {
    @GetMapping("/api/rooms/{rid}/me/gifts")
    fun list(@PathVariable rid: String, @RoomMember ctx: RoomContext): GiftsResponse = service.list(ctx)

    @GetMapping("/api/rooms/{rid}/me/gifts/{id}")
    fun box(@PathVariable rid: String, @PathVariable id: Long, @RoomMember ctx: RoomContext): GiftBoxDetail = service.box(ctx, id)

    @PostMapping("/api/rooms/{rid}/me/gifts/{id}/open")
    fun open(@PathVariable rid: String, @PathVariable id: Long, @RoomMember ctx: RoomContext): ResponseEntity<GiftBoxDetail> =
        ResponseEntity.accepted().body(service.open(ctx, id))

    @PutMapping("/api/rooms/{rid}/me/avatar")
    fun avatar(@PathVariable rid: String, @RoomMember ctx: RoomContext, @RequestBody body: AvatarRequest): MemberView = service.setAvatar(ctx, body)

    @GetMapping("/api/rooms/{rid}/characters/{id}/image")
    fun image(@PathVariable rid: String, @PathVariable id: Long, @RoomMember ctx: RoomContext): ResponseEntity<ByteArray> = service.image(ctx, id)
}
