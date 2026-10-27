package dev.gyeol.core.knowledge

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class EntityType { TRACK, WORK, RELEASE_GROUP, ARTIST }

data class Chunk(val source: String, val lang: String, val url: String, val license: String, val text: String)
data class Credit(val role: String, val artistMbid: UUID?, val name: String)

/** 보강할 곡의 대상 ID와 이름 (Last.fm 조회에는 사용자가 본 이름과 MusicBrainz 이름을 함께 쓴다) */
data class TrackRefs(
    val trackId: Long,
    val recordingMbid: UUID,
    val releaseGroupMbid: UUID?,
    val artistMbid: UUID,
    val artistName: String,
    val displayArtist: String?,
    val title: String,
    val displayTitle: String?,
    val releaseTitle: String?,
    val workMbids: List<UUID>,
)

data class EnrichProgress(val target: Int, val done: Int, val failed: Int)

/** L3-1 지식 인입 저장소 (docs/l3-design.md) */
@Repository
class KnowledgeRepository(private val jdbc: JdbcClient) {

    /** 이 대상을 [since] 이후에 가져온 적이 있는지 (없음도 기록되어 있으면 true) */
    fun isFresh(type: EntityType, mbid: UUID, since: Instant): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM knowledge_fetch WHERE entity_type = :t AND entity_mbid = :m AND fetched_at > :s)")
            .param("t", type.name).param("m", mbid).param("s", Timestamp.from(since)).query(Boolean::class.java).single()

    /** 대상의 청크를 새로 가져온 것으로 바꾼다 (출처·언어별 한 줄) */
    fun saveEntity(type: EntityType, mbid: UUID, chunks: List<Chunk>) {
        jdbc.sql("DELETE FROM knowledge_chunk WHERE entity_type = :t AND entity_mbid = :m").param("t", type.name).param("m", mbid).update()
        chunks.distinctBy { it.source to it.lang }.forEach { c ->
            jdbc.sql(
                """
                INSERT INTO knowledge_chunk (entity_type, entity_mbid, source, lang, url, license, text)
                VALUES (:t, :m, :s, :l, :u, :lic, :x)
                """.trimIndent(),
            ).param("t", type.name).param("m", mbid).param("s", c.source).param("l", c.lang).param("u", c.url).param("lic", c.license).param("x", c.text)
                .update()
        }
        jdbc.sql(
            """
            INSERT INTO knowledge_fetch (entity_type, entity_mbid, status, fetched_at) VALUES (:t, :m, :s, now())
            ON CONFLICT (entity_type, entity_mbid) DO UPDATE SET status = EXCLUDED.status, fetched_at = now()
            """.trimIndent(),
        ).param("t", type.name).param("m", mbid).param("s", if (chunks.isEmpty()) "EMPTY" else "OK").update()
    }

    fun saveCredits(trackId: Long, credits: List<Credit>) {
        credits.forEach { c ->
            jdbc.sql("INSERT INTO track_credit (track_id, role, artist_mbid, name) VALUES (:t, :r, :a, :n) ON CONFLICT DO NOTHING")
                .param("t", trackId).param("r", c.role).param("a", c.artistMbid).param("n", c.name).update()
        }
    }

    /**
     * 작품(work)의 작곡·작사 크레딧은 작품 단위로 한 번만 가져오므로, 이미 가져온 작품이면
     * 같은 작품을 공유하는 다른 곡의 크레딧을 이 곡에도 붙인다 (같은 곡의 다른 녹음·음반)
     */
    fun copyWorkCredits(trackId: Long, work: UUID) {
        jdbc.sql(
            """
            INSERT INTO track_credit (track_id, role, artist_mbid, name)
            SELECT DISTINCT :t, tc.role, tc.artist_mbid, tc.name
            FROM track_credit tc JOIN track o ON o.id = tc.track_id
            WHERE :w = ANY (o.work_mbids) AND o.id <> :t AND tc.role IN ('COMPOSER', 'LYRICIST', 'WRITER')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("t", trackId).param("w", work).update()
    }

    fun setWorks(trackId: Long, works: List<UUID>) {
        jdbc.sql("UPDATE track SET work_mbids = :w WHERE id = :id").param("w", works.toTypedArray()).param("id", trackId).update()
    }

    fun trackRefs(trackId: Long): TrackRefs? =
        jdbc.sql(
            """
            SELECT t.id, t.recording_mbid, t.release_group_mbid, a.mbid AS artist_mbid, a.name AS artist_name, t.title, t.release_title, t.work_mbids,
                   (SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED') AS display_artist,
                   (SELECT mode() WITHIN GROUP (ORDER BY ta.query_title) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED') AS display_title
            FROM track t JOIN artist a ON a.id = t.artist_id WHERE t.id = :id
            """.trimIndent(),
        ).param("id", trackId).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            val works = (rs.getArray("work_mbids")?.array as Array<UUID>?)?.toList().orEmpty()
            TrackRefs(
                rs.getLong("id"), rs.getObject("recording_mbid", UUID::class.java), rs.getObject("release_group_mbid", UUID::class.java),
                rs.getObject("artist_mbid", UUID::class.java), rs.getString("artist_name"), rs.getString("display_artist"),
                rs.getString("title"), rs.getString("display_title"), rs.getString("release_title"), works,
            )
        }.optional().orElse(null)

    // ── 세션별 보강 대상 ──

    /** 보강할 곡: 청취 기록 세션은 재생 상위 [limit]곡, 곡 고르기 세션은 고른 곡 전부 */
    fun candidateTracks(sessionId: Long, limit: Int): List<Long> =
        jdbc.sql(
            """
            SELECT track_id FROM (
                SELECT track_id, count(*) AS n FROM listen_event WHERE session_id = :sid AND track_id IS NOT NULL GROUP BY track_id
                UNION ALL
                SELECT track_id, 1000000 FROM seed_track WHERE session_id = :sid AND track_id IS NOT NULL
            ) x GROUP BY track_id ORDER BY sum(n) DESC, track_id LIMIT :lim
            """.trimIndent(),
        ).param("sid", sessionId).param("lim", limit).query { rs, _ -> rs.getLong(1) }.list()

    fun insertTargets(sessionId: Long, trackIds: List<Long>) {
        trackIds.forEach {
            jdbc.sql("INSERT INTO enrich_target (session_id, track_id) VALUES (:s, :t) ON CONFLICT DO NOTHING").param("s", sessionId).param("t", it).update()
        }
    }

    fun pendingTargets(sessionId: Long): List<Long> =
        jdbc.sql("SELECT track_id FROM enrich_target WHERE session_id = :s AND status = 'PENDING' ORDER BY track_id").param("s", sessionId)
            .query { rs, _ -> rs.getLong(1) }.list()

    fun finishTarget(sessionId: Long, trackId: Long, status: String): Boolean =
        jdbc.sql("UPDATE enrich_target SET status = :st, updated_at = now() WHERE session_id = :s AND track_id = :t AND status = 'PENDING'")
            .param("st", status).param("s", sessionId).param("t", trackId).update() == 1

    fun progress(sessionId: Long): EnrichProgress =
        jdbc.sql("SELECT count(*), count(*) FILTER (WHERE status = 'DONE'), count(*) FILTER (WHERE status = 'FAILED') FROM enrich_target WHERE session_id = :s")
            .param("s", sessionId).query { rs, _ -> EnrichProgress(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.single()
}
