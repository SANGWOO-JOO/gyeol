package dev.gyeol.core.identify

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/** 우리 DB 검색 결과 한 곡. 표시 이름은 사용자가 YouTube에서 본 표기를 우선한다 */
data class DbTrackHit(
    val trackId: Long,
    val title: String,
    val artist: String,
    val recordingMbid: UUID,
    val releaseYear: Int?,
    val coverUrl: String?,
    val score: Int,
    /** 가수 묶기·같은 곡 합치기용 (I-30): 우리 가수 id, MusicBrainz 원래 제목 */
    val artistId: Long = 0,
    val mbTitle: String = "",
)

/** 가수 이름·별칭 (I-30 가수 묶기). plays: 그 가수 곡의 누적 재생 수 */
data class ArtistNames(val id: Long, val name: String, val aliases: List<String>, val plays: Long)

/** L2-4 우리 DB 곡 검색 (docs/l2-design.md). 색인은 곡을 저장하거나 캐시를 기록할 때 함께 넣는다 */
@Repository
class TrackSearchRepository(private val jdbc: JdbcClient) {
    companion object {
        /** 이 점수 이상이면 확실한 결과 (완전·앞부분·부분 일치, 가수가 맞는 오타) */
        const val STRONG = 2
    }


    /** I-48 4-2: 지운 별칭의 YouTube 표기가 남지 않게 곡 색인을 비우고 다시 만든다 */
    fun rebuild(trackId: Long) {
        jdbc.sql("DELETE FROM track_search_term WHERE track_id = :id").param("id", trackId).update()
        reindex(trackId)
    }

    /** 곡의 모든 표기를 색인에 넣는다 (이미 있는 표기는 그대로) */
    fun reindex(trackId: Long) {
        val (titles, artists) = jdbc.sql(
            """
            SELECT t.title, a.name, a.sort_name, a.aliases,
                   coalesce(array_agg(DISTINCT ta.query_title) FILTER (WHERE ta.query_title IS NOT NULL), '{}') AS yt_titles,
                   coalesce(array_agg(DISTINCT ta.query_title_alt) FILTER (WHERE ta.query_title_alt IS NOT NULL), '{}') AS alt_titles,
                   coalesce(array_agg(DISTINCT ta.query_artist) FILTER (WHERE ta.query_artist IS NOT NULL), '{}') AS yt_artists
            FROM track t JOIN artist a ON a.id = t.artist_id
            LEFT JOIN title_alias ta ON ta.track_id = t.id AND ta.status = 'MATCHED'
            WHERE t.id = :id GROUP BY t.id, a.id
            """.trimIndent(),
        ).param("id", trackId).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            fun arr(col: String) = (rs.getArray(col)?.array as Array<String?>?)?.filterNotNull().orEmpty()
            (listOf(rs.getString("title")) + arr("yt_titles") + arr("alt_titles")) to
                (listOfNotNull(rs.getString("name"), rs.getString("sort_name")) + arr("aliases") + arr("yt_artists"))
        }.optional().orElse(null) ?: return

        SearchText.terms(titles, artists).forEach { t ->
            jdbc.sql("INSERT INTO track_search_term (track_id, term, chosung, kind) VALUES (:id, :term, :cho, :kind) ON CONFLICT (track_id, term) DO NOTHING")
                .param("id", trackId).param("term", t.text).param("cho", SearchText.chosung(t.text)).param("kind", t.kind)
                .update()
        }
    }

    /** 색인이 없는 곡 (색인 기능이 생기기 전에 저장된 곡) */
    fun unindexedTrackIds(): List<Long> =
        jdbc.sql("SELECT id FROM track t WHERE NOT EXISTS (SELECT 1 FROM track_search_term s WHERE s.track_id = t.id)")
            .query { rs, _ -> rs.getLong(1) }.list()

    fun addPlays(trackId: Long, plays: Int) {
        if (plays > 0) jdbc.sql("UPDATE track SET plays_total = plays_total + :n WHERE id = :id").param("n", plays).param("id", trackId).update()
    }

    /**
     * 오타 보완: 띄어 쓴 단어 하나가 가수 이름과 정확히 같으면, 나머지 단어를 그 가수의 곡 제목과 편집 거리로 비교한다.
     * 짧은 단어의 글자 순서 바뀜("rian" ↔ "rain")은 3글자 조각 유사도로는 못 잡는다. 가수가 정확히 맞아야 하므로 엉뚱한 곡이 섞이기 어렵다.
     * 허용 거리: 제목 길이의 절반 이하, 최대 2
     */
    private fun typoMatches(query: String): List<Long> {
        val tokens = SearchText.tokens(query)
        if (tokens.size < 2) return emptyList()
        return tokens.indices.flatMap { i ->
            val rest = tokens.filterIndexed { j, _ -> j != i }.joinToString("")
            jdbc.sql(
                """
                SELECT DISTINCT t.track_id FROM track_search_term a JOIN track_search_term t ON t.track_id = a.track_id
                WHERE a.kind = 'ARTIST' AND a.term = :artist AND t.kind = 'TITLE'
                  AND levenshtein(t.term, :rest) <= least(2, length(t.term) / 2)
                """.trimIndent(),
            ).param("artist", tokens[i]).param("rest", rest).query { rs, _ -> rs.getLong(1) }.list()
        }.distinct()
    }

    /**
     * 점수: 완전 일치 4 > 앞부분 3 > 부분 2(가수가 맞는 오타 포함) > 곡 제목 조각 유사도(pg_trgm 기본 0.3 이상) 1.
     * 점수 1은 약한 결과라 호출하는 쪽에서 다른 출처 결과 뒤에 둔다 ([STRONG] 미만).
     * 한 곡의 여러 표기 중 가장 높은 점수만 쓰고, 같으면 유사도 → 많이 들린 곡 순.
     */
    fun search(query: String, limit: Int): List<DbTrackHit> {
        val chosungOnly = SearchText.isChosungQuery(query)
        val q = if (chosungOnly) SearchText.chosung(query) else SearchText.norm(query)
        if (q.isEmpty()) return emptyList()
        val col = if (chosungOnly) "chosung" else "term"
        val typo = if (chosungOnly) emptyList() else typoMatches(query)
        return jdbc.sql(
            """
            WITH hits AS (
                SELECT track_id,
                       CASE WHEN $col = :q THEN 4 WHEN $col LIKE :q || '%' THEN 3 WHEN $col LIKE '%' || :q || '%' THEN 2 ELSE 1 END AS score,
                       similarity($col, :q) AS sim
                FROM track_search_term
                -- 조각 유사도는 곡 제목에만: "가수+곡" 조합에 쓰면 같은 가수의 다른 곡이 걸린다 ("아이유 라일락" → 아이유밤편지)
                WHERE $col LIKE '%' || :q || '%' OR (kind = 'TITLE' AND $col % :q)
                UNION ALL
                -- 가수가 정확히 맞는 오타는 확실한 결과로 본다
                SELECT id, 2, 0 FROM track WHERE id IN (:typo)
            ), m AS (
                SELECT track_id, max(score) AS score, max(sim) AS sim FROM hits GROUP BY track_id
            )
            SELECT t.id, t.recording_mbid, t.release_date, t.cover_url, m.score, t.artist_id, t.title AS mb_title,
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_title) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), t.title) AS title,
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), a.name) AS artist
            FROM m JOIN track t ON t.id = m.track_id JOIN artist a ON a.id = t.artist_id
            ORDER BY m.score DESC, m.sim DESC, t.plays_total DESC, t.id
            LIMIT :limit
            """.trimIndent(),
        ).param("q", q).param("limit", limit).param("typo", typo.ifEmpty { listOf(-1L) }).query { rs, _ ->
            DbTrackHit(
                trackId = rs.getLong("id"),
                title = rs.getString("title"),
                artist = rs.getString("artist"),
                recordingMbid = rs.getObject("recording_mbid", UUID::class.java),
                releaseYear = rs.getString("release_date")?.take(4)?.toIntOrNull(),
                coverUrl = rs.getString("cover_url"),
                score = rs.getInt("score"),
                artistId = rs.getLong("artist_id"),
                mbTitle = rs.getString("mb_title"),
            )
        }.list()
    }

    private val hitSelect = """
        SELECT t.id, t.recording_mbid, t.release_date, t.cover_url, t.artist_id, t.title AS mb_title,
               coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_title) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), t.title) AS title,
               coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), a.name) AS artist
        FROM track t JOIN artist a ON a.id = t.artist_id
    """.trimIndent()

    private fun hitOf(rs: java.sql.ResultSet, score: Int) = DbTrackHit(
        rs.getLong("id"), rs.getString("title"), rs.getString("artist"), rs.getObject("recording_mbid", UUID::class.java),
        rs.getString("release_date")?.take(4)?.toIntOrNull(), rs.getString("cover_url"), score, rs.getLong("artist_id"), rs.getString("mb_title"),
    )

    /** 곡 id로 (검색 결과와 같은 표시 제목·가수). 점수는 0 */
    fun hits(ids: Collection<Long>): List<DbTrackHit> =
        if (ids.isEmpty()) emptyList()
        else jdbc.sql("$hitSelect WHERE t.id IN (:ids)").param("ids", ids).query { rs, _ -> hitOf(rs, 0) }.list()

    /** 한 가수의 곡 전부 (가수 화면에서 Last.fm 곡을 우리 곡과 맞출 때) */
    fun hitsOfArtist(artistId: Long): List<DbTrackHit> =
        jdbc.sql("$hitSelect WHERE t.artist_id = :a ORDER BY t.plays_total DESC, t.id LIMIT 300").param("a", artistId).query { rs, _ -> hitOf(rs, 0) }.list()

    /** 녹음 MBID → 곡 id */
    fun trackIdsByMbids(mbids: Collection<UUID>): Map<UUID, Long> =
        if (mbids.isEmpty()) emptyMap()
        else jdbc.sql("SELECT recording_mbid, id FROM track WHERE recording_mbid IN (:m)").param("m", mbids)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getLong(2) }.list().toMap()

    /** 식별 캐시(title_alias) 키 → 곡 id (MATCHED만) */
    fun aliasTracks(keys: Collection<String>): Map<String, Long> =
        if (keys.isEmpty()) emptyMap()
        else jdbc.sql("SELECT alias_key, track_id FROM title_alias WHERE status = 'MATCHED' AND alias_key IN (:k)").param("k", keys)
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()

    /**
     * 이름·별칭이 [lowered] 중 하나와 (소문자로) 같은 가수 후보. 최종 비교는 호출하는 쪽이 SearchText.norm으로 한다.
     * 정규화(공백·문장부호 제거)는 SQL로 똑같이 만들기 어려워 소문자 비교로 후보만 좁힌다
     */
    fun artistsByNames(lowered: Collection<String>): List<ArtistNames> =
        if (lowered.isEmpty()) emptyList()
        else jdbc.sql(
            """
            SELECT a.id, a.name, a.aliases, coalesce((SELECT sum(plays_total) FROM track t WHERE t.artist_id = a.id), 0)
            FROM artist a
            WHERE lower(a.name) IN (:l) OR EXISTS (SELECT 1 FROM unnest(a.aliases) x WHERE lower(x) IN (:l))
            """.trimIndent(),
        ).param("l", lowered).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            ArtistNames(rs.getLong(1), rs.getString(2), (rs.getArray(3).array as Array<String>).toList(), rs.getLong(4))
        }.list()

    fun artistNames(id: Long): ArtistNames? =
        jdbc.sql("SELECT a.id, a.name, a.aliases, coalesce((SELECT sum(plays_total) FROM track t WHERE t.artist_id = a.id), 0) FROM artist a WHERE a.id = :id")
            .param("id", id).query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                ArtistNames(rs.getLong(1), rs.getString(2), (rs.getArray(3).array as Array<String>).toList(), rs.getLong(4))
            }.optional().orElse(null)
}
