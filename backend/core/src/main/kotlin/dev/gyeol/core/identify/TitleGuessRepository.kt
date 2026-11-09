package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class GuessTarget(val title: String, val channel: String)

data class TitleGuess(
    val title: String,
    val channel: String,
    val artist: String?,
    val artistAlt: String?,
    val song: String?,
    val songAlt: String?,
    val version: String,
)

/** 방송·일반 채널 영상에서 LLM이 뽑은 가수·곡. 같은 영상은 다시 묻지 않는다 */
@Repository
class TitleGuessRepository(private val jdbc: JdbcClient) {

    /** 원곡 식별 대상이 아니었던 일반 채널 영상 중 많이 들은 것. 최근에 이미 뽑은 영상은 뺀다 */
    fun targets(sessionId: Long, minPlays: Int, max: Int, retryDays: Int): List<GuessTarget> =
        jdbc.sql(
            """
            SELECT l.title, l.channel
            FROM listen_event l
            WHERE l.session_id = :sid AND l.track_id IS NULL AND l.channel IS NOT NULL AND l.channel NOT LIKE '% - Topic'
              AND NOT EXISTS (SELECT 1 FROM identify_target it WHERE it.session_id = :sid AND it.status = 'DONE' AND it.title = l.title AND it.channel = l.channel)
              AND NOT EXISTS (SELECT 1 FROM title_guess g WHERE g.title = l.title AND g.channel = l.channel AND g.checked_at > now() - make_interval(days => :days))
            GROUP BY l.title, l.channel
            HAVING count(*) >= :min
            ORDER BY count(*) DESC, l.title
            LIMIT :max
            """.trimIndent(),
        ).param("sid", sessionId).param("min", minPlays).param("max", max).param("days", retryDays)
            .query { rs, _ -> GuessTarget(rs.getString("title"), rs.getString("channel")) }.list()

    fun save(g: TitleGuess) {
        jdbc.sql(
            """
            INSERT INTO title_guess (title, channel, artist, artist_alt, song, song_alt, version)
            VALUES (:t, :c, :a, :aa, :s, :sa, :v)
            ON CONFLICT (title, channel) DO UPDATE SET artist = EXCLUDED.artist, artist_alt = EXCLUDED.artist_alt,
                song = EXCLUDED.song, song_alt = EXCLUDED.song_alt, version = EXCLUDED.version, checked_at = now()
            """.trimIndent(),
        ).param("t", g.title).param("c", g.channel).param("a", g.artist).param("aa", g.artistAlt)
            .param("s", g.song).param("sa", g.songAlt).param("v", g.version).update()
    }
}
