package dev.gyeol.api.stats

import dev.gyeol.api.web.ApiException
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.Session
import dev.gyeol.core.session.SessionStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.LocalDate

data class Period(val start: LocalDate?, val end: LocalDate?, val days: Long?)
data class Totals(val plays: Int, val uniqueTitles: Int, val uniqueChannels: Int)
data class TitleCount(val title: String, val channel: String?, val plays: Int)
data class ChannelCount(val channel: String, val plays: Int)
data class MonthCount(val month: String, val plays: Int)
data class SourceCount(val musicApp: Int, val youtube: Int)
data class Coverage(val music: Int?, val unclassified: Int?)

data class Stats(
    val period: Period,
    val totals: Totals,
    val topTitles: List<TitleCount>,
    val topChannels: List<ChannelCount>,
    val byHour: List<Int>,
    val byWeekday: List<Int>,
    val byMonth: List<MonthCount>,
    val bySource: SourceCount,
    val coverage: Coverage,
)

/** L1 기본 통계. 곡 식별 전이라 "제목 + 채널" 단위로 센다. 시간은 한국 시간 기준. */
@Service
class StatsService(private val jdbc: JdbcClient) {

    private val kst = "AT TIME ZONE 'Asia/Seoul'"
    private val channelName = "regexp_replace(channel, ' - Topic$', '')"

    fun of(session: Session, top: Int = 20): Stats {
        if (session.entry == Entry.SEEDS) throw ApiException.invalidState("곡 고르기 세션에는 통계가 없습니다")
        if (session.status != SessionStatus.DONE) throw ApiException.notReady()
        val sid = session.id

        val totals = jdbc.sql(
            """
            SELECT count(*) AS plays,
                   count(DISTINCT (title, coalesce(channel, ''))) AS titles,
                   count(DISTINCT $channelName) AS channels
            FROM listen_event WHERE session_id = :sid
            """.trimIndent(),
        ).param("sid", sid).query { rs, _ -> Totals(rs.getInt("plays"), rs.getInt("titles"), rs.getInt("channels")) }.single()

        val topTitles = jdbc.sql(
            """
            SELECT title, $channelName AS channel, count(*) AS plays
            FROM listen_event WHERE session_id = :sid
            GROUP BY title, $channelName ORDER BY plays DESC, title LIMIT :top
            """.trimIndent(),
        ).param("sid", sid).param("top", top).query { rs, _ -> TitleCount(rs.getString("title"), rs.getString("channel"), rs.getInt("plays")) }.list()

        val topChannels = jdbc.sql(
            """
            SELECT $channelName AS channel, count(*) AS plays
            FROM listen_event WHERE session_id = :sid AND channel IS NOT NULL
            GROUP BY $channelName ORDER BY plays DESC, channel LIMIT :top
            """.trimIndent(),
        ).param("sid", sid).param("top", top).query { rs, _ -> ChannelCount(rs.getString("channel"), rs.getInt("plays")) }.list()

        val byHour = buckets(sid, "extract(hour FROM watched_at $kst)::int", 0..23)
        // ISO 요일: 1=월요일 ~ 7=일요일
        val byWeekday = buckets(sid, "extract(isodow FROM watched_at $kst)::int", 1..7)

        val byMonth = jdbc.sql(
            """
            SELECT to_char(watched_at $kst, 'YYYY-MM') AS month, count(*) AS plays
            FROM listen_event WHERE session_id = :sid GROUP BY month ORDER BY month
            """.trimIndent(),
        ).param("sid", sid).query { rs, _ -> MonthCount(rs.getString("month"), rs.getInt("plays")) }.list()

        val bySource = jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE source = 'MUSIC_APP') AS music_app,
                   count(*) FILTER (WHERE source = 'YOUTUBE') AS youtube
            FROM listen_event WHERE session_id = :sid
            """.trimIndent(),
        ).param("sid", sid).query { rs, _ -> SourceCount(rs.getInt("music_app"), rs.getInt("youtube")) }.single()

        val zone = java.time.ZoneId.of("Asia/Seoul")
        val start = session.periodStart?.atZone(zone)?.toLocalDate()
        val end = session.periodEnd?.atZone(zone)?.toLocalDate()
        val days = if (start != null && end != null) java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1 else null

        return Stats(
            period = Period(start, end, days),
            totals = totals,
            topTitles = topTitles,
            topChannels = topChannels,
            byHour = byHour,
            byWeekday = byWeekday,
            byMonth = byMonth,
            bySource = bySource,
            coverage = Coverage(session.musicCount, session.unclassifiedCount),
        )
    }

    /** 빈 칸 없이 범위 전체를 채운 개수 목록 (예: 0~23시) */
    private fun buckets(sid: Long, expr: String, range: IntRange): List<Int> {
        val counts = jdbc.sql("SELECT $expr AS k, count(*) AS n FROM listen_event WHERE session_id = :sid GROUP BY k")
            .param("sid", sid)
            .query { rs, _ -> rs.getInt("k") to rs.getInt("n") }
            .list().toMap()
        return range.map { counts[it] ?: 0 }
    }
}
