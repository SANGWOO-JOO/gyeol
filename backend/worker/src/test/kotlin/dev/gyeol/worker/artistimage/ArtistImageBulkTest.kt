package dev.gyeol.worker.artistimage

import dev.gyeol.core.identify.ArtistImage
import dev.gyeol.core.identify.ArtistImageRepository
import dev.gyeol.core.identify.ArtistImageTarget
import dev.gyeol.core.identify.ChannelImageTarget
import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.identify.FetcherClient
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.ResponseEntity
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/** I-44 지도 가수 일괄 채우기: commonsOnly 메시지는 Commons만 찾고(YouTube 안 부름), 없으면 NONE */
class ArtistImageBulkTest {
    private val json = JsonMapper.builder().addModule(tools.jackson.module.kotlin.KotlinModule.Builder().build()).build()
    private val props = WorkerProperties(artistImage = WorkerProperties.ArtistImageProps(bulkPause = Duration.ofMillis(1)))

    @Test
    fun `commonsOnly - 사진 있으면 COMMONS, 없으면 NONE, YouTube 프로필은 부르지 않음`() {
        val repo = mock(ArtistImageRepository::class.java)
        val fetcher = mock(FetcherClient::class.java)
        val s3 = mock(S3Client::class.java)
        val withPhoto = UUID.randomUUID().toString(); val without = UUID.randomUUID().toString()
        `when`(fetcher.artistCommons(withPhoto)).thenReturn(json.readTree("""{"url":"https://upload.wikimedia.org/x.jpg","pageUrl":"https://commons.wikimedia.org/wiki/File:x.jpg","author":"작가","license":"CC BY 4.0"}"""))
        `when`(fetcher.artistCommons(without)).thenReturn(null)
        `when`(fetcher.artistFile(anyString())).thenReturn(ResponseEntity.ok(byteArrayOf(1, 2)))
        val listener = ArtistImageListener(repo, fetcher, s3, json, props)

        val msg = ArtistImageMessage(items = listOf(ArtistImageTarget(1, withPhoto, "vid1"), ArtistImageTarget(2, without, "vid2")), commonsOnly = true)
        listener.onMessage(json.writeValueAsString(msg))

        verify(repo).save(1, ArtistImage("COMMONS", s3Key = "artists/1", pageUrl = "https://commons.wikimedia.org/wiki/File:x.jpg", author = "작가", license = "CC BY 4.0"))
        verify(repo).save(2, ArtistImage("NONE"))
        verify(fetcher, never()).channelImage(anyString())
    }

    @Test
    fun `I-45 보통 메시지 - Commons가 없으면 NONE, YouTube 프로필은 부르지 않음 (가수·Topic 채널 둘 다)`() {
        val repo = mock(ArtistImageRepository::class.java)
        val fetcher = mock(FetcherClient::class.java)
        val mbid = UUID.randomUUID().toString()
        `when`(fetcher.artistCommons(mbid)).thenReturn(null)
        `when`(fetcher.findArtist("Someone", emptyList())).thenReturn(null)
        ArtistImageListener(repo, fetcher, mock(S3Client::class.java), json, props).onMessage(
            json.writeValueAsString(ArtistImageMessage(items = listOf(ArtistImageTarget(3, mbid, "vid")), channels = listOf(ChannelImageTarget("Someone - Topic", "vid2")))),
        )
        verify(repo).save(3, ArtistImage("NONE"))
        verify(repo).saveChannel("Someone - Topic", null, ArtistImage("NONE"))
        verify(fetcher, never()).channelImage(anyString())
    }

    @Test
    fun `I-50 map-fill retryNone - NONE 가수도 큐에 넣고, 겹치면 한 번만`() {
        val repo = mock(ArtistImageRepository::class.java)
        val sqs = mock(io.awspring.cloud.sqs.operations.SqsTemplate::class.java)
        `when`(repo.mapTargets(5000)).thenReturn(listOf(ArtistImageTarget(1, "m1", null)))
        `when`(repo.mapCommonsTargets()).thenReturn(emptyList())
        `when`(repo.mapNoneTargets(5000)).thenReturn((2L..12L).map { ArtistImageTarget(it, "m$it", null) } + ArtistImageTarget(1, "m1", null))
        val c = ArtistImageBulkController(repo, sqs, json, mock(S3Client::class.java), props)
        assertEquals(mapOf("unchecked" to 1, "missingFile" to 0, "retryNone" to 12, "queued" to 12), c.mapFill(5000, retryNone = true))
        verify(sqs, org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.any<java.util.function.Consumer<io.awspring.cloud.sqs.operations.SqsSendOptions<Any>>>())
        val off = ArtistImageBulkController(repo, mock(io.awspring.cloud.sqs.operations.SqsTemplate::class.java), json, mock(S3Client::class.java), props)
        assertEquals(1, off.mapFill(5000, retryNone = false)["queued"])
    }

    @Test
    fun `채널 메시지에 mbid가 있으면 이름 검색 없이 그 가수의 Commons 사진을 다시 받는다`() {
        val repo = mock(ArtistImageRepository::class.java)
        val fetcher = mock(FetcherClient::class.java)
        val mbid = UUID.randomUUID().toString()
        `when`(repo.channelId("가수 - Topic")).thenReturn(7)
        `when`(fetcher.artistCommons(mbid)).thenReturn(json.readTree("""{"url":"https://upload.wikimedia.org/x.jpg","pageUrl":"p","author":"작가","license":"CC BY 4.0"}"""))
        `when`(fetcher.artistFile(anyString())).thenReturn(ResponseEntity.ok(byteArrayOf(1)))
        ArtistImageListener(repo, fetcher, mock(S3Client::class.java), json, props)
            .onMessage(json.writeValueAsString(ArtistImageMessage(channels = listOf(ChannelImageTarget("가수 - Topic", null, mbid = mbid)))))
        verify(fetcher, never()).findArtist(anyString(), org.mockito.ArgumentMatchers.anyList())
        verify(repo).saveChannel("가수 - Topic", UUID.fromString(mbid), ArtistImage("COMMONS", s3Key = "channels/7", pageUrl = "p", author = "작가", license = "CC BY 4.0"))
    }
}
