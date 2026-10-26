package dev.gyeol.core.identify

import dev.gyeol.core.listen.Source
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class AliasStatus { MATCHED, NOT_FOUND }
enum class AliasMethod { RULE, LLM, MANUAL }
enum class TargetStatus { PENDING, DONE, FAILED }

data class ArtistInfo(val mbid: UUID, val name: String, val sortName: String?, val aliases: List<String>)

data class TrackInfo(
    val recordingMbid: UUID,
    val title: String,
    val artist: ArtistInfo,
    val releaseMbid: UUID?,
    val releaseGroupMbid: UUID?,
    val releaseTitle: String?,
    val releaseDate: String?,
    val releaseType: String?,
)

data class Alias(val key: String, val status: AliasStatus, val trackId: Long?, val checkedAt: Instant) {
    /** 찾은 곡은 계속 유효, 못 찾음은 [retryBefore] 이후 기록일 때만 유효 (그 전이면 다시 시도) */
    fun isFresh(retryBefore: Instant) = status == AliasStatus.MATCHED || checkedAt.isAfter(retryBefore)
}

/** 세션의 고유 (제목, 채널)과 재생 수 */
data class PlayGroup(val title: String, val channel: String?, val source: Source, val plays: Int)

data class Target(val sessionId: Long, val key: String, val title: String, val channel: String?, val source: Source, val plays: Int, val status: TargetStatus)

data class IdentifyProgress(val target: Int, val done: Int, val matched: Int, val notFound: Int, val failed: Int)

@Repository
class IdentifyRepository(private val jdbc: JdbcClient) {

    // ── 전역 캐시 ──

    fun findAlias(key: String): Alias? =
        jdbc.sql("SELECT alias_key, status, track_id, checked_at FROM title_alias WHERE alias_key = :k").param("k", key)
            .query { rs, _ ->
                Alias(rs.getString(1), AliasStatus.valueOf(rs.getString(2)), rs.getObject(3) as Long?, rs.getTimestamp(4).toInstant())
            }.optional().orElse(null)

    fun saveAlias(key: String, trackId: Long?, method: AliasMethod, query: Query?) {
        jdbc.sql(
            """
            INSERT INTO title_alias (alias_key, track_id, status, method, query_artist, query_title, checked_at)
            VALUES (:k, :t, :s, :m, :qa, :qt, now())
            ON CONFLICT (alias_key) DO UPDATE SET track_id = EXCLUDED.track_id, status = EXCLUDED.status, method = EXCLUDED.method,
                query_artist = EXCLUDED.query_artist, query_title = EXCLUDED.query_title, checked_at = now()
            """.trimIndent(),
        )
            .param("k", key).param("t", trackId).param("s", (if (trackId != null) AliasStatus.MATCHED else AliasStatus.NOT_FOUND).name)
            .param("m", method.name).param("qa", query?.artist).param("qt", query?.title)
            .update()
    }

    /** 가수·곡을 넣거나(이미 있으면 갱신) 곡 id를 돌려준다. 같은 녹음은 여러 키가 함께 쓴다 */
    fun upsertTrack(t: TrackInfo): Long {
        val artistId = jdbc.sql(
            """
            INSERT INTO artist (mbid, name, sort_name, aliases) VALUES (:mbid, :name, :sort, :aliases)
            ON CONFLICT (mbid) DO UPDATE SET name = EXCLUDED.name, sort_name = EXCLUDED.sort_name, aliases = EXCLUDED.aliases
            RETURNING id
            """.trimIndent(),
        )
            .param("mbid", t.artist.mbid).param("name", t.artist.name).param("sort", t.artist.sortName)
            .param("aliases", t.artist.aliases.toTypedArray())
            .query(Long::class.java).single()
        return jdbc.sql(
            """
            INSERT INTO track (recording_mbid, title, artist_id, release_mbid, release_group_mbid, release_title, release_date, release_type)
            VALUES (:rec, :title, :artist, :rel, :rg, :rt, :rd, :rty)
            ON CONFLICT (recording_mbid) DO UPDATE SET title = EXCLUDED.title, artist_id = EXCLUDED.artist_id,
                release_mbid = EXCLUDED.release_mbid, release_group_mbid = EXCLUDED.release_group_mbid,
                release_title = EXCLUDED.release_title, release_date = EXCLUDED.release_date, release_type = EXCLUDED.release_type
            RETURNING id
            """.trimIndent(),
        )
            .param("rec", t.recordingMbid).param("title", t.title).param("artist", artistId)
            .param("rel", t.releaseMbid).param("rg", t.releaseGroupMbid).param("rt", t.releaseTitle)
            .param("rd", t.releaseDate).param("rty", t.releaseType)
            .query(Long::class.java).single()
    }

    // ── 세션별 식별 대상 ──

    fun playGroups(sessionId: Long): List<PlayGroup> =
        jdbc.sql(
            """
            SELECT title, channel, max(source) AS source, count(*) AS plays
            FROM listen_event WHERE session_id = :sid GROUP BY title, channel
            """.trimIndent(),
        ).param("sid", sessionId)
            .query { rs, _ -> PlayGroup(rs.getString(1), rs.getString(2), Source.valueOf(rs.getString(3)), rs.getInt(4)) }.list()

    /** 이미 있으면 그대로 둔다 (계획이 두 번 돌아도 같은 결과) */
    fun insertTargets(sessionId: Long, targets: List<Pair<String, PlayGroup>>) {
        targets.forEach { (key, g) ->
            jdbc.sql(
                """
                INSERT INTO identify_target (session_id, alias_key, title, channel, source, plays)
                VALUES (:sid, :k, :t, :c, :s, :p) ON CONFLICT (session_id, alias_key) DO NOTHING
                """.trimIndent(),
            ).param("sid", sessionId).param("k", key).param("t", g.title).param("c", g.channel).param("s", g.source.name).param("p", g.plays)
                .update()
        }
    }

    fun targets(sessionId: Long, keys: Collection<String>): List<Target> {
        if (keys.isEmpty()) return emptyList()
        return jdbc.sql("SELECT session_id, alias_key, title, channel, source, plays, status FROM identify_target WHERE session_id = :sid AND alias_key IN (:keys)")
            .param("sid", sessionId).param("keys", keys)
            .query { rs, _ ->
                Target(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), Source.valueOf(rs.getString(5)), rs.getInt(6), TargetStatus.valueOf(rs.getString(7)))
            }.list()
    }

    fun pendingKeys(sessionId: Long): List<String> =
        jdbc.sql("SELECT alias_key FROM identify_target WHERE session_id = :sid AND status = 'PENDING' ORDER BY plays DESC, alias_key")
            .param("sid", sessionId).query { rs, _ -> rs.getString(1) }.list()

    /** PENDING일 때만 바꾼다. 바꿨으면 true */
    fun finishTarget(sessionId: Long, key: String, status: TargetStatus): Boolean =
        jdbc.sql("UPDATE identify_target SET status = :s, updated_at = now() WHERE session_id = :sid AND alias_key = :k AND status = 'PENDING'")
            .param("s", status.name).param("sid", sessionId).param("k", key).update() == 1

    /** 세션의 해당 (제목, 채널) 청취 기록을 곡에 연결한다 */
    fun linkListens(target: Target, trackId: Long) {
        jdbc.sql("UPDATE listen_event SET track_id = :t WHERE session_id = :sid AND title = :title AND channel IS NOT DISTINCT FROM :ch")
            .param("t", trackId).param("sid", target.sessionId).param("title", target.title).param("ch", target.channel)
            .update()
    }

    fun progress(sessionId: Long): IdentifyProgress =
        jdbc.sql(
            """
            SELECT count(*),
                   count(*) FILTER (WHERE t.status = 'DONE'),
                   count(*) FILTER (WHERE t.status = 'DONE' AND a.status = 'MATCHED'),
                   count(*) FILTER (WHERE t.status = 'DONE' AND (a.status IS NULL OR a.status = 'NOT_FOUND')),
                   count(*) FILTER (WHERE t.status = 'FAILED')
            FROM identify_target t LEFT JOIN title_alias a ON a.alias_key = t.alias_key
            WHERE t.session_id = :sid
            """.trimIndent(),
        ).param("sid", sessionId)
            .query { rs, _ -> IdentifyProgress(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)) }.single()
}
