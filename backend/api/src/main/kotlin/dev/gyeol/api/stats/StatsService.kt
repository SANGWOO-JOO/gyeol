package dev.gyeol.api.stats

import dev.gyeol.api.config.GyeolProperties
import dev.gyeol.api.web.ApiException
import dev.gyeol.core.session.Entry
import dev.gyeol.core.session.Session
import dev.gyeol.core.session.SessionStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
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
data class SongCount(
    val trackId: Long?,
    val title: String,
    val artist: String?,
    val release: Release?,
    val coverUrl: String?,
    val plays: Int,
    /** INSTRUMENTAL(연주) 또는 COVER(커버). 원곡 음원이 아닌 영상. 원곡 가수는 제목에서 읽은 경우에만 있다 */
    val performance: String? = null,
    val originalArtist: String? = null,
    /** S3에 저장된 표지가 있으면 true (API로 제공), 아니면 [coverUrl]을 직접 불러온다 */
    val storedCover: Boolean = false,
    /** artist가 가수가 아니라 일반 채널 이름인 경우 */
    val channelOnly: Boolean = false,
)
data class ArtistCount(
    val artistId: Long?,
    val name: String,
    val plays: Int,
    /** COMMONS(저장한 사진, API로 제공) · YOUTUBE(채널 프로필 주소) · 없으면 null */
    val imageSource: String? = null,
    val imageUrl: String? = null,
    val imagePage: String? = null,
    val imageAuthor: String? = null,
    val imageLicense: String? = null,
    /** 이미지를 다시 받으면 바뀌는 값. 브라우저 캐시를 넘기 위해 주소에 붙인다 */
    val imageVersion: Long? = null,
    /** Topic 채널 가수의 저장된 사진 id (API로 제공) */
    val channelImageId: Long? = null,
    /** 사진이 없을 때 쓰는 대표 앨범 표지 (그 가수에서 가장 많이 들은 곡) */
    val coverTrackId: Long? = null,
    val coverStored: Boolean = false,
    val coverUrl: String? = null,
)
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
    val topSongsTotal: Int,
    val topArtists: List<ArtistCount>,
    val topArtistsTotal: Int,
    /** 가수를 정하지 못해 가수 순위에서 뺀 방송·일반 채널 재생 수 */
    val excludedChannelPlays: Int,
    val identified: Identified,
)

class CoverFile(val bytes: ByteArray, val contentType: String)

/** 순위 목록의 한 페이지. total은 전체 항목 수 */
data class RankPage<T>(val items: List<T>, val page: Int, val size: Int, val total: Int)

private data class Ranked<T>(val items: List<T>, val total: Int)

/**
 * 기본 통계 (시간은 한국 시간 기준).
 * L1 항목(topTitles·topChannels)은 "제목 + 채널" 단위, L2-2 항목(topSongs·topArtists)은 식별된 곡·가수 단위.
 * 표시 이름은 사용자가 YouTube에서 본 이름을 우선한다 (MusicBrainz 공식 표기 "IU"보다 "아이유").
 */
@Service
class StatsService(private val jdbc: JdbcClient, private val s3: S3Client, private val props: GyeolProperties) {

    /** 이 세션에서 들은 가수의 저장된 사진 (Commons). 없으면 null */
    fun artistImage(session: Session, artistId: Long): CoverFile? {
        val key = jdbc.sql(
            """
            SELECT ai.s3_key FROM artist_image ai
            WHERE ai.artist_id = :a AND ai.s3_key IS NOT NULL
              AND EXISTS (SELECT 1 FROM listen_event l JOIN track t ON t.id = l.track_id WHERE l.session_id = :s AND t.artist_id = :a)
            """.trimIndent(),
        ).param("a", artistId).param("s", session.id).query(String::class.java).optional().orElse(null) ?: return null
        val obj = s3.getObjectAsBytes { it.bucket(props.coverBucket).key(key) }
        return CoverFile(obj.asByteArray(), obj.response().contentType() ?: "image/jpeg")
    }

    /** 이 세션에서 들은 Topic 채널 가수의 저장된 사진. 없으면 null */
    fun channelImage(session: Session, id: Long): CoverFile? {
        val key = jdbc.sql(
            """
            SELECT ci.s3_key FROM channel_image ci
            WHERE ci.id = :id AND ci.s3_key IS NOT NULL
              AND EXISTS (SELECT 1 FROM listen_event l WHERE l.session_id = :s AND l.channel = ci.channel)
            """.trimIndent(),
        ).param("id", id).param("s", session.id).query(String::class.java).optional().orElse(null) ?: return null
        val obj = s3.getObjectAsBytes { it.bucket(props.coverBucket).key(key) }
        return CoverFile(obj.asByteArray(), obj.response().contentType() ?: "image/jpeg")
    }

    /** 이 세션의 곡 표지 이미지. 다른 세션의 곡이거나 저장 전이면 null */
    fun cover(session: Session, trackId: Long): CoverFile? {
        val key = jdbc.sql(
            """
            SELECT t.cover_key FROM track t
            WHERE t.id = :t AND t.cover_key IS NOT NULL
              AND (EXISTS (SELECT 1 FROM listen_event l WHERE l.session_id = :s AND l.track_id = :t)
                   OR EXISTS (SELECT 1 FROM recommendation r WHERE r.session_id = :s AND r.track_id = :t))
            """.trimIndent(),
        ).param("t", trackId).param("s", session.id).query(String::class.java).optional().orElse(null) ?: return null
        val obj = s3.getObjectAsBytes { it.bucket(props.coverBucket).key(key) }
        return CoverFile(obj.asByteArray(), obj.response().contentType() ?: "image/jpeg")
    }

    private val kst = "AT TIME ZONE 'Asia/Seoul'"
    private val channelName = "regexp_replace(channel, ' - Topic$', '')"

    private fun requireReady(session: Session) {
        if (session.entry == Entry.SEEDS) throw ApiException.invalidState("곡 고르기 세션에는 통계가 없습니다")
        if (session.status != SessionStatus.DONE) throw ApiException.notReady()
    }

    fun songs(session: Session, page: Int, size: Int): RankPage<SongCount> = paged(session, page, size) { limit, offset -> songRows(session.id, limit, offset) }

    fun artists(session: Session, page: Int, size: Int): RankPage<ArtistCount> = paged(session, page, size) { limit, offset -> artistRows(session.id, limit, offset) }

    private fun <T> paged(session: Session, page: Int, size: Int, rows: (Int, Int) -> Ranked<T>): RankPage<T> {
        requireReady(session)
        if (page < 1 || size !in 1..50) throw ApiException.validation("page는 1 이상, size는 1~50이어야 합니다")
        val r = rows(size, (page - 1) * size)
        // 범위를 벗어난 페이지는 행이 없어 전체 수를 따로 구한다
        val total = if (r.items.isEmpty() && page > 1) rows(1, 0).total else r.total
        return RankPage(r.items, page, size, total)
    }

    fun of(session: Session, top: Int = 20): Stats {
        requireReady(session)
        val sid = session.id
        val songs = songRows(sid, top, 0)
        val artists = artistRows(sid, top, 0)

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
            topSongs = songs.items,
            topSongsTotal = songs.total,
            topArtists = artists.items,
            topArtistsTotal = artists.total,
            excludedChannelPlays = excludedChannelPlays(sid),
            identified = jdbc.sql("SELECT count(track_id), count(*) FROM listen_event WHERE session_id = :sid").param("sid", sid)
                .query { rs, _ -> Identified(rs.getInt(1), rs.getInt(2)) }.single(),
        )
    }

    // Topic 채널에서 얻은 가수 이름 (YouTube 표기). 일반 채널은 가수 이름이 아닐 수 있어 쓰지 않는다
    private val topicArtist = "mode() WITHIN GROUP (ORDER BY regexp_replace(l.channel, ' - Topic$', '')) FILTER (WHERE l.channel LIKE '% - Topic')"

    private fun songRows(sid: Long, limit: Int, offset: Int): Ranked<SongCount> {
        var total = 0
        val items = jdbc.sql(
            """
            WITH $knownName,
            $guessed,
            $songLabels
            SELECT g.track_id, count(*) OVER () AS total, g.performance, g.original_artist, t.cover_key IS NOT NULL AS stored_cover,
                   (g.track_id IS NULL AND g.topic_artist IS NULL AND g.guess_artist_id IS NULL AND g.performance IS NULL) AS channel_only,
                   -- 식별된 곡은 정리된 이름(캐시의 곡명·가수)을 먼저: 일반 영상 제목에는 "[MV] … _ …" 같은 장식이 붙어 있다 (L2-3)
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_title) FROM title_alias ta WHERE ta.track_id = g.track_id AND ta.status = 'MATCHED'), g.label_title, g.title) AS title,
                   coalesce(g.topic_artist, (SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta WHERE ta.track_id = g.track_id AND ta.status = 'MATCHED'), a.name, al.name, ga.name, g.channel) AS artist,
                   t.release_title, t.release_date, t.cover_url, g.plays
            FROM (
                SELECT l.track_id, count(*) AS plays,
                       mode() WITHIN GROUP (ORDER BY l.title) AS title,
                       $topicArtist AS topic_artist,
                       min(regexp_replace(l.channel, ' - Topic$', '')) AS channel,
                       min(l.label_title) AS label_title, min(l.guess_artist_id) AS guess_artist_id,
                       min(l.performance) AS performance, min(l.original_artist) AS original_artist
                FROM (
                    SELECT coalesce(e.track_id, m.track_id) AS track_id, e.title, e.channel, m.artist_id AS guess_artist_id,
                           coalesce(m.song, i.song) AS label_title, i.kind AS performance, i.original AS original_artist
                    FROM listen_event e
                    LEFT JOIN guess m ON m.title = e.title AND m.channel = e.channel
                    LEFT JOIN performance i ON i.title = e.title AND i.channel = e.channel
                    WHERE e.session_id = :sid AND NOT EXISTS (SELECT 1 FROM medley_video mv WHERE mv.title = e.title AND mv.channel = e.channel)
                    UNION ALL
                    SELECT md.track_id, mt.title, e.channel, NULL::bigint, NULL::text, NULL::text, NULL::text
                    FROM listen_event e JOIN medley md ON md.title = e.title AND md.channel = e.channel JOIN track mt ON mt.id = md.track_id
                    WHERE e.session_id = :sid
                ) l
                -- 식별된 곡은 곡으로, 못 찾은 곡은 제목+채널로 묶는다
                GROUP BY l.track_id, CASE WHEN l.track_id IS NULL THEN l.title END, CASE WHEN l.track_id IS NULL THEN l.channel END
            ) g
            LEFT JOIN track t ON t.id = g.track_id
            LEFT JOIN artist a ON a.id = t.artist_id
            LEFT JOIN artist_label al ON al.artist_id = g.guess_artist_id
            LEFT JOIN artist ga ON ga.id = g.guess_artist_id
            ORDER BY g.plays DESC, g.title, g.track_id NULLS LAST, g.channel LIMIT :top OFFSET :offset
            """.trimIndent(),
        ).param("sid", sid).param("top", limit).param("offset", offset).query { rs, _ ->
            total = rs.getInt("total")
            val trackId = rs.getObject("track_id") as Long?
            SongCount(
                trackId = trackId,
                title = rs.getString("title"),
                artist = rs.getString("artist"),
                release = trackId?.let { Release(rs.getString("release_title"), rs.getString("release_date")?.take(4)?.toIntOrNull()) },
                coverUrl = rs.getString("cover_url"),
                plays = rs.getInt("plays"),
                performance = rs.getString("performance"),
                originalArtist = rs.getString("original_artist"),
                storedCover = rs.getBoolean("stored_cover"),
                channelOnly = rs.getBoolean("channel_only"),
            )
        }.list()
        return Ranked(items, total)
    }

    private fun nameKey(expr: String) = "lower(regexp_replace($expr, '[[:space:][:punct:]’]', '', 'g'))"

    /** 이 세션에서 원곡 식별로 확인된 가수의 이름·별칭 (다른 가수와 겹치는 이름은 쓰지 않는다) */
    private val knownName = """
        known_name AS (
            SELECT ${nameKey("n")} AS key, min(a.id) AS artist_id
            FROM artist a CROSS JOIN LATERAL unnest(ARRAY[a.name, a.sort_name] || a.aliases) AS n
            WHERE n IS NOT NULL AND a.id IN (SELECT t.artist_id FROM listen_event l JOIN track t ON t.id = l.track_id WHERE l.session_id = :sid)
            GROUP BY 1 HAVING count(DISTINCT a.id) = 1
        )
    """.trimIndent()

    /**
     * 원곡을 못 찾았지만 LLM이 가수·곡을 읽은 영상을, 이 세션에서 확인된 가수와 그 가수의 식별된 곡에 잇는다. 커버는 가수에만 더한다.
     * 곡 이름은 곡명의 병기 부분, 다른 영상에서 얻은 표기, 싱글일 때만 음반명까지 대조한다 (MusicBrainz에 영어 제목만 있는 곡).
     */
    private val guessed = """
        session_track AS (SELECT DISTINCT track_id FROM listen_event WHERE session_id = :sid AND track_id IS NOT NULL),
        track_name AS (
            SELECT t.id AS track_id, t.artist_id, t.title AS name FROM track t JOIN session_track s ON s.track_id = t.id
            UNION ALL
            SELECT t.id, t.artist_id, ta.query_title FROM track t JOIN session_track s ON s.track_id = t.id JOIN title_alias ta ON ta.track_id = t.id AND ta.status = 'MATCHED'
            UNION ALL
            SELECT t.id, t.artist_id, ta.query_title_alt FROM track t JOIN session_track s ON s.track_id = t.id JOIN title_alias ta ON ta.track_id = t.id AND ta.status = 'MATCHED'
            UNION ALL
            SELECT t.id, t.artist_id, t.release_title FROM track t JOIN session_track s ON s.track_id = t.id WHERE t.release_type = 'Single'
        ),
        track_key AS (
            SELECT n.artist_id, ${nameKey("p")} AS key, min(n.track_id) AS track_id
            FROM track_name n CROSS JOIN LATERAL unnest(array_append(regexp_split_to_array(n.name, '\s*[/(（)）]\s*'), n.name)) AS p
            WHERE n.name IS NOT NULL AND p <> ''
            GROUP BY 1, 2 HAVING count(DISTINCT n.track_id) = 1
        ),
        unidentified AS (SELECT DISTINCT title, channel FROM listen_event WHERE session_id = :sid AND track_id IS NULL AND channel IS NOT NULL),
        guess_source AS (
            SELECT it.title, it.channel, ta.query_artist AS artist, NULL AS artist_alt, ta.query_title AS song, ta.query_title_alt AS song_alt, 'LIVE' AS version, 0 AS pref
            FROM identify_target it JOIN title_alias ta ON ta.alias_key = it.alias_key
            WHERE it.session_id = :sid AND ta.method = 'LLM' AND ta.status = 'NOT_FOUND' AND ta.reason = 'NOT_IN_MUSICBRAINZ'
            UNION ALL
            SELECT g.title, g.channel, g.artist, g.artist_alt, g.song, g.song_alt, g.version, 1 FROM title_guess g WHERE g.version <> 'INSTRUMENTAL'
            UNION ALL
            -- 식별 대상 밖의 Topic 영상도 같은 가수의 식별된 곡과 제목이 같으면 그 곡으로 본다
            SELECT u.title, u.channel, regexp_replace(u.channel, ' - Topic$', ''), NULL, u.title, NULL, 'LIVE', 2 FROM unidentified u WHERE u.channel LIKE '% - Topic'
        ),
        guess AS (
            SELECT DISTINCT ON (g.title, g.channel) g.title, g.channel, coalesce(ka.artist_id, kb.artist_id) AS artist_id, g.song,
                   CASE WHEN g.version <> 'COVER' THEN coalesce(ta.track_id, tb.track_id) END AS track_id
            FROM guess_source g
            JOIN unidentified u ON u.title = g.title AND u.channel = g.channel
            LEFT JOIN known_name ka ON ka.key = ${nameKey("g.artist")}
            LEFT JOIN known_name kb ON kb.key = ${nameKey("g.artist_alt")}
            LEFT JOIN track_key ta ON ta.artist_id = coalesce(ka.artist_id, kb.artist_id) AND ta.key = ${nameKey("g.song")}
            LEFT JOIN track_key tb ON tb.artist_id = coalesce(ka.artist_id, kb.artist_id) AND tb.key = ${nameKey("g.song_alt")}
            WHERE coalesce(ka.artist_id, kb.artist_id) IS NOT NULL
            ORDER BY g.title, g.channel, g.pref
        ),
        -- 메들리 영상에 든 곡: MusicBrainz로 찾은 곡, 아니면 확인된 가수의 같은 이름 곡
        -- 같은 곡을 여러 언어로 적은 메들리("봄날 | ハルノヒ")는 같은 곡으로 이어지므로 한 번만 센다
        medley AS (
            SELECT DISTINCT m.title, m.channel, coalesce(m.track_id, tk.track_id) AS track_id
            FROM medley_song m
            JOIN unidentified u ON u.title = m.title AND u.channel = m.channel
            LEFT JOIN known_name k ON k.key = ${nameKey("m.artist")}
            LEFT JOIN track_key tk ON tk.artist_id = k.artist_id AND tk.key = ${nameKey("m.song")}
            WHERE coalesce(m.track_id, tk.track_id) IS NOT NULL
        ),
        medley_video AS (SELECT DISTINCT title, channel FROM medley)
    """.trimIndent()

    /** 곡 순위 표시용: 연주·커버 영상의 정리된 곡명과 원곡 가수, 확인된 가수의 YouTube 표기. title_guess의 커버 가수는 부른 사람이라 원곡 가수로 쓰지 않는다 */
    private val songLabels = """
        performance AS (
            SELECT DISTINCT ON (x.title, x.channel) x.title, x.channel, x.song, x.kind, NULLIF(x.artist, '') AS original
            FROM (
                SELECT it.title, it.channel, ta.query_title AS song, ta.query_artist AS artist, ta.reason AS kind, 0 AS pref
                FROM identify_target it JOIN title_alias ta ON ta.alias_key = it.alias_key
                WHERE it.session_id = :sid AND ta.reason IN ('INSTRUMENTAL', 'COVER')
                UNION ALL
                SELECT g.title, g.channel, g.song, CASE WHEN g.version = 'INSTRUMENTAL' THEN g.artist END, g.version, 1
                FROM title_guess g JOIN unidentified u ON u.title = g.title AND u.channel = g.channel WHERE g.version IN ('INSTRUMENTAL', 'COVER')
            ) x
            ORDER BY x.title, x.channel, x.pref
        ),
        artist_label AS (
            SELECT t.artist_id, mode() WITHIN GROUP (ORDER BY regexp_replace(l.channel, ' - Topic$', '')) AS name
            FROM listen_event l JOIN track t ON t.id = l.track_id
            WHERE l.session_id = :sid AND l.channel LIKE '% - Topic'
            GROUP BY t.artist_id
        )
    """.trimIndent()

    /**
     * 재생마다 가수를 정한다. 원곡 식별 → 같은 Topic 채널에서 식별된 가수 → LLM이 뽑은 가수 → 가수 이름과 같은 채널 순.
     * LLM 가수와 채널 이름은 이 세션에서 원곡 식별로 확인된 가수와 이름이 같을 때만 인정한다 (프로그램 진행자를 가수로 잘못 뽑는 경우를 막는다).
     */
    private val artistResolved get() = """
        WITH $knownName,
        $guessed,
        topic AS (
            SELECT l.channel, mode() WITHIN GROUP (ORDER BY t.artist_id) AS artist_id
            FROM listen_event l JOIN track t ON t.id = l.track_id
            WHERE l.session_id = :sid AND l.channel LIKE '% - Topic'
            GROUP BY l.channel
        ),
        resolved AS (
            SELECT l.channel, coalesce(t.artist_id, tp.artist_id, g.artist_id, cn.artist_id) AS artist_id
            FROM listen_event l
            LEFT JOIN track t ON t.id = l.track_id
            LEFT JOIN topic tp ON tp.channel = l.channel
            LEFT JOIN guess g ON g.title = l.title AND g.channel = l.channel
            LEFT JOIN known_name cn ON cn.key = ${nameKey("l.channel")}
            WHERE l.session_id = :sid AND NOT EXISTS (SELECT 1 FROM medley_video mv WHERE mv.title = l.title AND mv.channel = l.channel)
            UNION ALL
            -- 메들리를 재생하면 든 곡을 모두 들은 것으로 본다: 곡마다 그 곡 가수의 재생으로 센다
            SELECT l.channel, t.artist_id FROM listen_event l JOIN medley md ON md.title = l.title AND md.channel = l.channel JOIN track t ON t.id = md.track_id
            WHERE l.session_id = :sid
        )
    """.trimIndent()

    private fun artistRows(sid: Long, limit: Int, offset: Int): Ranked<ArtistCount> {
        var total = 0
        val items = jdbc.sql(
            """
            $artistResolved,
            page AS (
            SELECT l.artist_id, count(*) OVER () AS total,
                   coalesce($topicArtist,
                            (SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta JOIN track tt ON tt.id = ta.track_id WHERE tt.artist_id = l.artist_id AND ta.status = 'MATCHED'),
                            min(a.name), min(regexp_replace(l.channel, ' - Topic$', ''))) AS name,
                   count(*) AS plays,
                   CASE WHEN l.artist_id IS NULL THEN min(l.channel) END AS channel
            FROM resolved l
            LEFT JOIN artist a ON a.id = l.artist_id
            -- 가수를 정하지 못한 Topic 채널은 채널 이름이 곧 가수다. 방송·일반 채널은 가수가 아니므로 뺀다
            WHERE l.artist_id IS NOT NULL OR l.channel LIKE '% - Topic'
            GROUP BY l.artist_id, CASE WHEN l.artist_id IS NULL THEN regexp_replace(l.channel, ' - Topic$', '') END
            ORDER BY plays DESC, name, l.artist_id NULLS LAST LIMIT :top OFFSET :offset
            )
            -- 이미지와 대표 앨범 표지는 이 페이지의 가수만 붙인다
            SELECT p.*, coalesce(ai.source, ci.source) AS image_source, coalesce(ai.url, ci.url) AS image_url,
                   extract(epoch FROM coalesce(ai.checked_at, ci.checked_at))::bigint AS image_version,
                   coalesce(ai.page_url, ci.page_url) AS page_url, coalesce(ai.author, ci.author) AS author, coalesce(ai.license, ci.license) AS license,
                   CASE WHEN ci.s3_key IS NOT NULL THEN ci.id END AS channel_image_id,
                   c.track_id AS cover_track_id, c.stored, c.cover_url
            FROM page p
            LEFT JOIN artist_image ai ON ai.artist_id = p.artist_id AND ai.source <> 'NONE'
            -- 가수 기록 없는 Topic 채널 가수는 채널 이름 기준 이미지
            LEFT JOIN channel_image ci ON p.artist_id IS NULL AND ci.channel = p.channel AND ci.source <> 'NONE'
            LEFT JOIN LATERAL (
                SELECT t.id AS track_id, t.cover_key IS NOT NULL AS stored, t.cover_url
                FROM listen_event e JOIN track t ON t.id = e.track_id
                WHERE e.session_id = :sid AND t.artist_id = p.artist_id AND (t.cover_key IS NOT NULL OR t.cover_url IS NOT NULL)
                GROUP BY t.id ORDER BY count(*) DESC, t.id LIMIT 1
            ) c ON p.artist_id IS NOT NULL
            ORDER BY p.plays DESC, p.name, p.artist_id NULLS LAST
            """.trimIndent(),
        ).param("sid", sid).param("top", limit).param("offset", offset)
            .query { rs, _ ->
                total = rs.getInt("total")
                ArtistCount(
                    rs.getObject("artist_id") as Long?, rs.getString("name"), rs.getInt("plays"),
                    imageSource = rs.getString("image_source"), imageUrl = rs.getString("image_url"), imagePage = rs.getString("page_url"),
                    imageAuthor = rs.getString("author"), imageLicense = rs.getString("license"), imageVersion = rs.getObject("image_version") as Long?,
                    channelImageId = rs.getObject("channel_image_id") as Long?,
                    coverTrackId = rs.getObject("cover_track_id") as Long?, coverStored = rs.getBoolean("stored"), coverUrl = rs.getString("cover_url"),
                )
            }.list()
        return Ranked(items, total)
    }

    private fun excludedChannelPlays(sid: Long): Int =
        jdbc.sql("$artistResolved SELECT count(*) FROM resolved WHERE artist_id IS NULL AND channel IS NOT NULL AND channel NOT LIKE '% - Topic'")
            .param("sid", sid).query(Int::class.java).single()

    /** 빈 칸 없이 범위 전체를 채운 개수 목록 (예: 0~23시) */
    private fun buckets(sid: Long, expr: String, range: IntRange): List<Int> {
        val counts = jdbc.sql("SELECT $expr AS k, count(*) AS n FROM listen_event WHERE session_id = :sid GROUP BY k")
            .param("sid", sid)
            .query { rs, _ -> rs.getInt("k") to rs.getInt("n") }
            .list().toMap()
        return range.map { counts[it] ?: 0 }
    }
}
