package dev.gyeol.worker.parse

import dev.gyeol.core.listen.Source
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 브라우저가 올린 파일의 검증 규칙 (docs/l1-design.md 8장 "업로드 JSON 검증") */
class UploadFileTest {
    private val json = JsonMapper.builder().build()

    private fun parse(body: String) = UploadFile.parse(body.toByteArray(), json)
    private fun file(vararg items: String) = """{"schemaVersion":1,"items":[${items.joinToString(",")}]}"""
    private fun item(
        title: String = "곡",
        watchedAt: String = "2026-10-01T00:00:00Z",
        source: String = "MUSIC_APP",
        videoId: String? = "abc_DEF-123",
        channel: String? = "가수 - Topic",
    ) = buildString {
        append("""{"title":${json.writeValueAsString(title)},"watchedAt":"$watchedAt","source":"$source"""")
        if (videoId != null) append(""","videoId":"$videoId"""")
        if (channel != null) append(""","channel":${json.writeValueAsString(channel)}""")
        append("}")
    }

    @Test
    fun `정상 항목은 그대로 읽는다`() {
        val r = parse(file(item(), item(title = " 곡2 ", source = "YOUTUBE", videoId = null, channel = null)))
        assertEquals(0, r.skipped)
        assertEquals(2, r.events.size)
        with(r.events[0]) {
            assertEquals("abc_DEF-123", videoId)
            assertEquals(Instant.parse("2026-10-01T00:00:00Z"), watchedAt)
            assertEquals(Source.MUSIC_APP, source)
        }
        assertEquals("곡2", r.events[1].title) // 앞뒤 공백 제거
        assertNull(r.events[1].videoId)
        assertNull(r.events[1].channel)
    }

    @Test
    fun `잘못된 항목은 건너뛰고 건수만 센다`() {
        val r = parse(
            file(
                item(),
                item(title = ""), // 빈 제목
                item(title = "가".repeat(501)), // 제목 500자 초과
                item(watchedAt = "어제"), // 시각 형식
                item(watchedAt = "2026-10-01 00:00:00"), // ISO 아님
                item(source = "TV"), // 출처 값
                item(videoId = "공백 있는 id"), // 영상 ID 형식
            ),
        )
        assertEquals(1, r.events.size)
        assertEquals(6, r.skipped)
    }

    @Test
    fun `채널 이름은 300자로 자른다`() {
        val r = parse(file(item(channel = "가".repeat(400))))
        assertEquals(300, r.events[0].channel!!.length)
    }

    @Test
    fun `빈 items는 저장할 것 없이 통과한다`() {
        val r = parse(file())
        assertEquals(0, r.events.size)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `파일 자체가 틀리면 InvalidUploadException`() {
        assertFailsWith<InvalidUploadException> { parse("JSON 아님") }
        assertFailsWith<InvalidUploadException> { parse("""{"schemaVersion":2,"items":[]}""") }
        assertFailsWith<InvalidUploadException> { parse("""{"items":[]}""") }
        assertFailsWith<InvalidUploadException> { parse("""{"schemaVersion":1,"items":{}}""") }
        // 항목이 있는데 전부 틀림
        assertFailsWith<InvalidUploadException> { parse(file(item(title = ""), item(source = "X"))) }
    }
}
