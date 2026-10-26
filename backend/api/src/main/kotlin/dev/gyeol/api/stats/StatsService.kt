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

/** L2-2 곡 단위 순위. 식별된 곡은 곡으로 묶고(trackId 있음), 못 찾은 곡은 "제목 + 채널" 그대로 섞는다 */
data class Release(val title: String?, val year: Int?)
data class SongCount(val trackId: Long?, val title: String, val artist: String?, val release: Release?, val coverUrl: String?, val plays: Int)
data class ArtistCount(val artistId: Long?, val name: String, val plays: Int)
/** 곡이 확인된 재생 수 / 전체 재생 수 */
data class Identified(val plays: Int, val totalPlays: Int)

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
    val topSongs: List<SongCount>,
    val topArtists: List<ArtistCount>,
    val identified: Identified,
)

/**
 * 기본 통계 (시간은 한국 시간 기준).
 * L1 항목(topTitles·topChannels)은 "제목 + 채널" 단위, L2-2 항목(topSongs·topArtists)은 식별된 곡·가수 단위.
 * 표시 이름은 사용자가 YouTube에서 본 이름을 우선한다 (MusicBrainz 공식 표기 "IU"보다 "아이유").
 */
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
            topSongs = topSongs(sid, top),
            topArtists = topArtists(sid, top),
            identified = jdbc.sql("SELECT count(track_id), count(*) FROM listen_event WHERE session_id = :sid").param("sid", sid)
                .query { rs, _ -> Identified(rs.getInt(1), rs.getInt(2)) }.single(),
        )
    }

    // Topic 채널에서 얻은 가수 이름 (YouTube 표기). 일반 채널은 가수 이름이 아닐 수 있어 쓰지 않는다
    private val topicArtist = "mode() WITHIN GROUP (ORDER BY regexp_replace(l.channel, ' - Topic$', '')) FILTER (WHERE l.channel LIKE '% - Topic')"

    private fun topSongs(sid: Long, top: Int): List<SongCount> =
        jdbc.sql(
            """
            SELECT g.track_id, g.title, coalesce(g.topic_artist, a.name, g.channel) AS artist,
                   t.release_title, t.release_date, t.cover_url, g.plays
            FROM (
                SELECT l.track_id, count(*) AS plays,
                       mode() WITHIN GROUP (ORDER BY l.title) AS title,
                       $topicArtist AS topic_artist,
                       min(regexp_replace(l.channel, ' - Topic$', '')) AS channel
                FROM listen_event l WHERE l.session_id = :sid
                -- 식별된 곡은 곡으로, 못 찾은 곡은 제목+채널로 묶는다
                GROUP BY l.track_id, CASE WHEN l.track_id IS NULL THEN l.title END, CASE WHEN l.track_id IS NULL THEN l.channel END
            ) g
            LEFT JOIN track t ON t.id = g.track_id
            LEFT JOIN artist a ON a.id = t.artist_id
            ORDER BY g.plays DESC, g.title LIMIT :top
            """.trimIndent(),
        ).param("sid", sid).param("top", top).query { rs, _ ->
            val trackId = rs.getObject("track_id") as Long?
            SongCount(
                trackId = trackId,
                title = rs.getString("title"),
                artist = rs.getString("artist"),
                release = trackId?.let { Release(rs.getString("release_title"), rs.getString("release_date")?.take(4)?.toIntOrNull()) },
                coverUrl = rs.getString("cover_url"),
                plays = rs.getInt("plays"),
            )
        }.list()

    private fun topArtists(sid: Long, top: Int): List<ArtistCount> =
        jdbc.sql(
            """
            SELECT t.artist_id, coalesce($topicArtist, min(a.name), min(regexp_replace(l.channel, ' - Topic$', ''))) AS name, count(*) AS plays
            FROM listen_event l
            LEFT JOIN track t ON t.id = l.track_id
            LEFT JOIN artist a ON a.id = t.artist_id
            WHERE l.session_id = :sid AND (t.artist_id IS NOT NULL OR l.channel IS NOT NULL)
            -- 식별된 가수는 가수로(같은 가수의 여러 채널이 합쳐짐), 나머지는 채널 이름으로
            GROUP BY t.artist_id, CASE WHEN t.artist_id IS NULL THEN regexp_replace(l.channel, ' - Topic$', '') END
            ORDER BY plays DESC, name LIMIT :top
            """.trimIndent(),
        ).param("sid", sid).param("top", top)
            .query { rs, _ -> ArtistCount(rs.getObject("artist_id") as Long?, rs.getString("name"), rs.getInt("plays")) }.list()

    /** 빈 칸 없이 범위 전체를 채운 개수 목록 (예: 0~23시) */
    private fun buckets(sid: Long, expr: String, range: IntRange): List<Int> {
        val counts = jdbc.sql("SELECT $expr AS k, count(*) AS n FROM listen_event WHERE session_id = :sid GROUP BY k")
            .param("sid", sid)
            .query { rs, _ -> rs.getInt("k") to rs.getInt("n") }
            .list().toMap()
        return range.map { counts[it] ?: 0 }
    }
}
