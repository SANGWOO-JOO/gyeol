package dev.gyeol.worker.parse

import dev.gyeol.core.listen.ListenEvent
import dev.gyeol.core.listen.Source
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.format.DateTimeParseException

/** 다시 시도해도 똑같이 실패하는 오류 (파일 형식이 틀림). 재시도하지 않고 바로 FAILED로 끝낸다. */
class InvalidUploadException(message: String) : RuntimeException(message)

data class ParsedUpload(val events: List<ListenEvent>, val skipped: Int)

/**
 * 브라우저가 필터를 거쳐 올린 파일을 읽는다.
 * `{"schemaVersion": 1, "items": [{"videoId", "title", "channel", "watchedAt", "source"}]}`
 * 잘못된 항목은 건너뛰고 건수만 센다. 유효한 항목이 하나도 없으면 실패.
 */
object UploadFile {
    private val VIDEO_ID = Regex("""^[\w-]{1,32}$""")

    fun parse(bytes: ByteArray, json: JsonMapper): ParsedUpload {
        val root: JsonNode = try {
            json.readTree(bytes)
        } catch (e: Exception) {
            throw InvalidUploadException("JSON으로 읽을 수 없는 파일입니다")
        }
        if (root.path("schemaVersion").asInt(-1) != 1) throw InvalidUploadException("지원하지 않는 schemaVersion입니다")
        val items = root.path("items")
        if (!items.isArray) throw InvalidUploadException("items 배열이 없습니다")

        var skipped = 0
        val events = items.toList().mapNotNull { item ->
            toEvent(item).also { if (it == null) skipped++ }
        }
        if (events.isEmpty() && items.size() > 0) throw InvalidUploadException("유효한 항목이 하나도 없습니다 (${items.size()}개 모두 형식 오류)")
        return ParsedUpload(events, skipped)
    }

    private fun toEvent(item: JsonNode): ListenEvent? {
        val title = item.path("title").asString("").trim()
        if (title.isEmpty() || title.length > 500) return null
        val watchedAt = try {
            Instant.parse(item.path("watchedAt").asString(""))
        } catch (e: DateTimeParseException) {
            return null
        }
        val source = when (item.path("source").asString("")) {
            "MUSIC_APP" -> Source.MUSIC_APP
            "YOUTUBE" -> Source.YOUTUBE
            else -> return null
        }
        val videoId = item.path("videoId").asString("").trim().takeIf { it.isNotEmpty() }
        if (videoId != null && !VIDEO_ID.matches(videoId)) return null
        val channel = item.path("channel").asString("").trim().takeIf { it.isNotEmpty() }?.take(300)
        return ListenEvent(videoId, title, channel, watchedAt, source)
    }
}
