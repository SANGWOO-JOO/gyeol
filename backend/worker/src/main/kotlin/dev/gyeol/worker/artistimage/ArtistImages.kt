package dev.gyeol.worker.artistimage

import dev.gyeol.core.identify.ArtistImage
import dev.gyeol.core.identify.ArtistImageRepository
import dev.gyeol.core.identify.ArtistImageTarget
import dev.gyeol.core.identify.ChannelImageTarget
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.identify.FetcherClient
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class ArtistImageMessage(val items: List<ArtistImageTarget> = emptyList(), val channels: List<ChannelImageTarget> = emptyList())

/** 식별이 끝난 세션에서 많이 들은 가수의 이미지 찾기를 큐에 넣는다 (가수당 한 번, 전역 캐시) */
@Component
class ArtistImagePlanner(private val repo: ArtistImageRepository, private val sqs: SqsTemplate, private val json: JsonMapper, private val props: WorkerProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun plan(sessionId: Long) {
        val a = props.artistImage
        val targets = repo.targets(sessionId, a.top, a.retry.toDays().toInt())
        targets.chunked(10).forEach { items -> sqs.send { it.queue(a.queue).payload(json.writeValueAsString(ArtistImageMessage(items = items))) } }
        // 가수 기록 없이 Topic 채널로만 들은 가수 (곡이 식별 대상 밖이라 가수가 정해지지 않음)
        val channels = repo.channelTargets(sessionId, a.top, a.retry.toDays().toInt())
        channels.chunked(10).forEach { cs -> sqs.send { it.queue(a.queue).payload(json.writeValueAsString(ArtistImageMessage(channels = cs))) } }
        log.info("가수 이미지 계획: 세션 {}, 가수 {}명, Topic 채널 {}곳", sessionId, targets.size, channels.size)
    }
}

/**
 * SQS artist-image 큐 (WORKER_ROLE에 artist가 있을 때만). docs/todo/artist-images.md 순서:
 * Wikimedia Commons 사진(S3 저장) → YouTube 채널 프로필(주소만, 30일 뒤 갱신) → 없음 (화면은 대표 앨범 표지 → 글자 타일)
 */
@Component
@ConditionalOnExpression("'\${gyeol.worker.role:parse}'.contains('artist')")
class ArtistImageListener(
    private val repo: ArtistImageRepository,
    private val fetcher: FetcherClient,
    private val s3: S3Client,
    private val json: JsonMapper,
    private val props: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${gyeol.worker.artist-image.queue}", maxConcurrentMessages = "1", maxMessagesPerPoll = "1")
    fun onMessage(body: String) {
        val msg = json.readValue(body, ArtistImageMessage::class.java)
        var failure: Exception? = null
        val counts = mutableMapOf<String, Int>()
        msg.items.forEach { t ->
            try {
                val image = find(t)
                repo.save(t.artistId, image)
                counts.merge(image.source, 1, Int::plus)
            } catch (e: HttpClientErrorException) {
                log.warn("가수 이미지 요청 거절: 가수 {}, {}", t.artistId, e.statusCode.value())
                repo.save(t.artistId, ArtistImage("NONE")) // 다시 물어도 같다
            } catch (e: RuntimeException) {
                failure = e // 나머지 가수는 계속하고, 끝난 뒤 메시지를 다시 받는다
            }
        }
        msg.channels.forEach { c ->
            try {
                val (mbid, image) = findChannel(c)
                repo.saveChannel(c.channel, mbid, image)
                counts.merge("channel:" + image.source, 1, Int::plus)
            } catch (e: HttpClientErrorException) {
                log.warn("채널 가수 이미지 요청 거절: {}, {}", c.channel, e.statusCode.value())
                repo.saveChannel(c.channel, null, ArtistImage("NONE"))
            } catch (e: RuntimeException) {
                failure = e
            }
        }
        log.info("가수 이미지: 가수 {}명, 채널 {}곳, {}", msg.items.size, msg.channels.size, counts)
        failure?.let { throw it }
    }

    private fun find(t: ArtistImageTarget): ArtistImage =
        commons(t.mbid) { "artists/${t.artistId}" } ?: youtube(t.videoId)

    /** Topic 채널 이름으로 MusicBrainz 가수를 찾을 수 있으면 그 가수의 Commons 사진, 아니면 그 Topic 채널의 프로필 */
    private fun findChannel(c: ChannelImageTarget): Pair<UUID?, ArtistImage> {
        val mbid = fetcher.findArtist(c.channel.removeSuffix(" - Topic"), c.titles)
        val photo = mbid?.let { m -> commons(m) { "channels/${repo.channelId(c.channel)}" } }
        return (mbid?.let(UUID::fromString)) to (photo ?: youtube(c.videoId))
    }

    private fun commons(mbid: String, key: () -> String): ArtistImage? {
        if (repo.skipCommons(mbid)) return null // 직접 지정 (ZARD 등)
        val photo = fetcher.artistCommons(mbid) ?: return null
        val file = fetcher.artistFile(photo.path("url").asString())
        val k = key()
        s3.putObject({ it.bucket(props.cover.bucket).key(k).contentType(file.headers.contentType?.toString() ?: "image/jpeg") }, RequestBody.fromBytes(file.body ?: error("사진 응답이 비어 있습니다")))
        fun text(f: String) = photo.path(f).asString("").ifEmpty { null }
        return ArtistImage("COMMONS", s3Key = k, pageUrl = text("pageUrl"), author = text("author"), license = text("license"))
    }

    // 기본 프로필(채널 이름 첫 글자 그림)은 fetcher가 이미지 내용으로 걸러 없음으로 돌려준다 (DefaultAvatar)
    private fun youtube(videoId: String?): ArtistImage {
        val url = videoId?.let { fetcher.channelImage(it) }
        return if (url != null) ArtistImage("YOUTUBE", url = url) else ArtistImage("NONE")
    }
}
