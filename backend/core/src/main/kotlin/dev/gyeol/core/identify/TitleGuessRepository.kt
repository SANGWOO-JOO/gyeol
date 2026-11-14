package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** [categoryCheckedAt]: I-48 4-2 판정 시각 (판정 없는 재생이 섞이면 null) */
data class GuessTarget(val title: String, val channel: String, val categoryCheckedAt: java.time.Instant? = null)

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
            SELECT l.title, l.channel,
                   CASE WHEN bool_and(l.source = 'YOUTUBE') THEN coalesce(max(vc.checked_at), now()) END AS category_checked_at
            FROM listen_event l LEFT JOIN video_check vc ON vc.video_id = l.video_id
            WHERE l.session_id = :sid AND l.track_id IS NULL AND l.channel IS NOT NULL AND l.channel NOT LIKE '% - Topic'
              AND NOT EXISTS (SELECT 1 FROM identify_target it WHERE it.session_id = :sid AND it.status = 'DONE' AND it.title = l.title AND it.channel = l.channel)
              AND NOT EXISTS (SELECT 1 FROM title_guess g WHERE g.title = l.title AND g.channel = l.channel AND g.checked_at > now() - make_interval(days => :days))
            GROUP BY l.title, l.channel
            HAVING count(*) >= :min
            ORDER BY count(*) DESC, l.title
            LIMIT :max
            """.trimIndent(),
        ).param("sid", sessionId).param("min", minPlays).param("max", max).param("days", retryDays)
            .query { rs, _ -> GuessTarget(rs.getString("title"), rs.getString("channel"), rs.getTimestamp("category_checked_at")?.toInstant()) }.list()

    fun save(g: TitleGuess, categoryCheckedAt: java.time.Instant? = null) {
        jdbc.sql(
            """
            INSERT INTO title_guess (title, channel, artist, artist_alt, song, song_alt, version, category_checked_at)
            VALUES (:t, :c, :a, :aa, :s, :sa, :v, :cat)
            ON CONFLICT (title, channel) DO UPDATE SET artist = EXCLUDED.artist, artist_alt = EXCLUDED.artist_alt,
                song = EXCLUDED.song, song_alt = EXCLUDED.song_alt, version = EXCLUDED.version, checked_at = now(),
                category_checked_at = ${categoryMergeSql("title_guess")}
            """.trimIndent(),
        ).param("t", g.title).param("c", g.channel).param("a", g.artist).param("aa", g.artistAlt)
            .param("s", g.song).param("sa", g.songAlt).param("v", g.version).param("cat", categoryCheckedAt?.let(java.sql.Timestamp::from)).update()
    }

    /**
     * I-48 4-2: 이 세션의 재생으로 추측·메들리 행의 판정 시각을 합친다 (다시 뽑지 않고 재사용하는 경로).
     * 판정 없는 재생(음악 앱)이 있으면 null로 바뀌어 계속 보관, 판정 경로뿐이면 더 늦은 판정 시각
     */
    fun touchCategories(sessionId: Long) {
        val groups = """
            WITH g AS (
                SELECT l.title, l.channel,
                       CASE WHEN bool_and(l.source = 'YOUTUBE' AND l.channel NOT LIKE '% - Topic') THEN coalesce(max(vc.checked_at), now()) END AS cat
                FROM listen_event l LEFT JOIN video_check vc ON vc.video_id = l.video_id
                WHERE l.session_id = :sid AND l.channel IS NOT NULL GROUP BY l.title, l.channel
            )
        """.trimIndent()
        listOf("title_guess", "medley_song").forEach { table ->
            jdbc.sql(
                """
                $groups
                UPDATE $table t SET category_checked_at = CASE WHEN g.cat IS NULL THEN NULL ELSE greatest(t.category_checked_at, g.cat) END
                FROM g WHERE t.title = g.title AND t.channel = g.channel AND t.category_checked_at IS NOT NULL
                """.trimIndent(),
            ).param("sid", sessionId).update()
        }
    }

    /** I-48 4-2: 판정 시각이 [before]보다 오래된 추측과 메들리 곡 */
    fun deleteExpired(before: java.time.Instant): Pair<Int, Int> {
        val b = java.sql.Timestamp.from(before)
        return jdbc.sql("DELETE FROM title_guess WHERE category_checked_at < :b").param("b", b).update() to
            jdbc.sql("DELETE FROM medley_song WHERE category_checked_at < :b").param("b", b).update()
    }
}
