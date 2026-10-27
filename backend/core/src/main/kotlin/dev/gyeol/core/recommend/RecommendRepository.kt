package dev.gyeol.core.recommend

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** 세션에서 들은(고른) 곡: 가중치와 벡터 */
data class Seed(val trackId: Long, val weight: Double, val title: String, val artist: String, val vector: FloatArray?)

data class Candidate(
    val key: String,
    val title: String,
    val artist: String,
    val trackId: Long?,
    val source: String,
    val lastfmMatch: Double,
    val status: String,
)

/** 점수를 매길 후보 (식별·보강이 끝난 곡) */
data class ScoredInput(val trackId: Long, val title: String, val artist: String, val artistMbid: String, val vector: FloatArray?, val lastfmMatch: Double, val creditOverlap: Int)

data class RecRow(val rank: Int, val trackId: Long, val score: Double, val reason: String?, val evidence: List<Long>, val basedOn: List<Long>, val model: String)

/** L3-4 추천 저장소 (docs/l3-design.md 11장) */
@Repository
class RecommendRepository(private val jdbc: JdbcClient) {

    private fun parseVector(s: String?): FloatArray? = s?.trim('[', ']')?.split(',')?.map { it.toFloat() }?.toFloatArray()
    private fun vectorText(v: FloatArray) = v.joinToString(",", "[", "]")

    /**
     * 프로필 재료: 청취 기록 세션은 곡별 재생 수로 log(1 + 재생 수), 고른 곡은 1.
     * 표시 이름은 사용자가 본 표기 (Last.fm 조회에도 쓴다)
     */
    fun seeds(sessionId: Long): List<Seed> =
        jdbc.sql(
            """
            WITH w AS (
                SELECT track_id, ln(1 + count(*)) AS weight FROM listen_event WHERE session_id = :sid AND track_id IS NOT NULL GROUP BY track_id
                UNION ALL
                SELECT track_id, 1.0 FROM seed_track WHERE session_id = :sid AND track_id IS NOT NULL
            )
            SELECT w.track_id, max(w.weight),
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_title) FROM title_alias ta WHERE ta.track_id = w.track_id AND ta.status = 'MATCHED'), min(t.title)),
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta WHERE ta.track_id = w.track_id AND ta.status = 'MATCHED'), min(a.name)),
                   min(v.embedding::text)
            FROM w JOIN track t ON t.id = w.track_id JOIN artist a ON a.id = t.artist_id LEFT JOIN track_vector v ON v.track_id = w.track_id
            GROUP BY w.track_id ORDER BY max(w.weight) DESC, w.track_id
            """.trimIndent(),
        ).param("sid", sessionId).query { rs, _ -> Seed(rs.getLong(1), rs.getDouble(2), rs.getString(3), rs.getString(4), parseVector(rs.getString(5))) }.list()

    /** 이미 들은 곡의 정규화 키 후보: 청취 기록 제목·채널, 고른 곡, 식별된 곡의 이름들 (key 함수는 부르는 쪽이 준다) */
    fun heardNames(sessionId: Long): List<Pair<String, String>> =
        jdbc.sql(
            """
            SELECT DISTINCT title, regexp_replace(coalesce(channel, ''), ' - Topic$', '') FROM listen_event WHERE session_id = :sid
            UNION SELECT title, artist FROM seed_track WHERE session_id = :sid
            UNION SELECT t.title, a.name FROM track t JOIN artist a ON a.id = t.artist_id
                  WHERE t.id IN (SELECT track_id FROM listen_event WHERE session_id = :sid UNION SELECT track_id FROM seed_track WHERE session_id = :sid)
            UNION SELECT ta.query_title, ta.query_artist FROM title_alias ta
                  WHERE ta.status = 'MATCHED' AND ta.track_id IN (SELECT track_id FROM listen_event WHERE session_id = :sid UNION SELECT track_id FROM seed_track WHERE session_id = :sid)
            """.trimIndent(),
        ).param("sid", sessionId).query { rs, _ -> rs.getString(1).orEmpty() to rs.getString(2).orEmpty() }.list()

    /** 프로필 벡터와 가까운 곡 (들은 곡 제외) */
    fun vectorNeighbors(profile: FloatArray, limit: Int, exclude: Collection<Long>): List<Pair<Long, Double>> =
        jdbc.sql(
            """
            SELECT track_id, embedding <=> CAST(:v AS vector) AS d FROM track_vector
            WHERE NOT (track_id = ANY (:ex)) ORDER BY embedding <=> CAST(:v AS vector) LIMIT :lim
            """.trimIndent(),
        ).param("v", vectorText(profile)).param("ex", exclude.toTypedArray()).param("lim", limit)
            .query { rs, _ -> rs.getLong(1) to rs.getDouble(2) }.list()

    /** 곡 설명에서 장르·분위기를, 가수 이름을 가중 집계 (근거 부족 칸은 빠진다) */
    fun facets(sessionId: Long, weights: Map<Long, Double>): Map<String, List<Pair<String, Double>>> {
        if (weights.isEmpty()) return emptyMap()
        val rows = jdbc.sql(
            """
            SELECT d.track_id, f.key, x.value
            FROM track_descriptor d, LATERAL (VALUES ('genres'), ('moods')) f(key),
                 LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(d.fields -> f.key -> 'value') = 'array' THEN d.fields -> f.key -> 'value' ELSE '[]' END) x(value)
            WHERE d.track_id = ANY (:ids)
            """.trimIndent(),
        ).param("ids", weights.keys.toTypedArray()).query { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getString(3)) }.list()
        return rows.groupBy { it.second }.mapValues { (_, xs) ->
            xs.groupBy { it.third.trim() }.mapValues { (_, ys) -> ys.sumOf { weights[it.first] ?: 0.0 } }.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
        }
    }

    fun saveProfile(sessionId: Long, vector: FloatArray?, facetsJson: String, summary: String?) {
        jdbc.sql(
            """
            INSERT INTO taste_profile (session_id, embedding, facets, summary) VALUES (:s, CAST(:v AS vector), CAST(:f AS jsonb), :sum)
            ON CONFLICT (session_id) DO UPDATE SET embedding = EXCLUDED.embedding, facets = EXCLUDED.facets, summary = coalesce(EXCLUDED.summary, taste_profile.summary), created_at = now()
            """.trimIndent(),
        ).param("s", sessionId).param("v", vector?.let(::vectorText)).param("f", facetsJson).param("sum", summary).update()
    }

    fun setSummary(sessionId: Long, summary: String?) {
        jdbc.sql("UPDATE taste_profile SET summary = :s WHERE session_id = :id").param("s", summary).param("id", sessionId).update()
    }

    fun profileVector(sessionId: Long): FloatArray? =
        jdbc.sql("SELECT embedding::text FROM taste_profile WHERE session_id = :s").param("s", sessionId).query { rs, _ -> parseVector(rs.getString(1)) }.optional().orElse(null)

    fun summary(sessionId: Long): String? =
        jdbc.sql("SELECT summary FROM taste_profile WHERE session_id = :s").param("s", sessionId).query { rs, _ -> rs.getString(1) }.optional().orElse(null)

    // ── 후보 ──

    fun replaceCandidates(sessionId: Long, cands: List<Candidate>) {
        jdbc.sql("DELETE FROM rec_candidate WHERE session_id = :s").param("s", sessionId).update()
        cands.forEach { c ->
            jdbc.sql(
                "INSERT INTO rec_candidate (session_id, cand_key, title, artist, track_id, source, lastfm_match, status) VALUES (:s, :k, :t, :a, :tid, :src, :m, :st)",
            ).param("s", sessionId).param("k", c.key).param("t", c.title).param("a", c.artist).param("tid", c.trackId)
                .param("src", c.source).param("m", c.lastfmMatch).param("st", c.status).update()
        }
    }

    fun candidates(sessionId: Long, status: String? = null): List<Candidate> =
        jdbc.sql("SELECT cand_key, title, artist, track_id, source, lastfm_match, status FROM rec_candidate WHERE session_id = :s AND (CAST(:st AS varchar) IS NULL OR status = :st) ORDER BY cand_key")
            .param("s", sessionId).param("st", status)
            .query { rs, _ -> Candidate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4) as Long?, rs.getString(5), rs.getDouble(6), rs.getString(7)) }.list()

    fun finishCandidate(sessionId: Long, key: String, trackId: Long?, status: String) {
        jdbc.sql("UPDATE rec_candidate SET track_id = coalesce(:t, track_id), status = :st WHERE session_id = :s AND cand_key = :k")
            .param("t", trackId).param("st", status).param("s", sessionId).param("k", key).update()
    }

    fun candidateProgress(sessionId: Long): Pair<Int, Int> =
        jdbc.sql("SELECT count(*) FILTER (WHERE status <> 'PENDING'), count(*) FROM rec_candidate WHERE session_id = :s").param("s", sessionId)
            .query { rs, _ -> rs.getInt(1) to rs.getInt(2) }.single()

    /** 점수 재료: 식별된 후보의 벡터, Last.fm 유사도, 들은 곡과 같은 작곡·작사·편곡·프로듀서 수 */
    fun scoringInputs(sessionId: Long, seedIds: Collection<Long>): List<ScoredInput> =
        jdbc.sql(
            """
            SELECT c.track_id, max(c.title), max(c.artist), max(a.mbid::text), max(v.embedding::text), max(c.lastfm_match),
                   (SELECT count(*) FROM track_credit x JOIN track_credit y ON y.track_id = ANY (:seeds)
                         AND (x.artist_mbid = y.artist_mbid OR (x.artist_mbid IS NULL AND x.name = y.name))
                    WHERE x.track_id = c.track_id)
            FROM rec_candidate c JOIN track t ON t.id = c.track_id JOIN artist a ON a.id = t.artist_id LEFT JOIN track_vector v ON v.track_id = c.track_id
            WHERE c.session_id = :s AND c.status = 'READY' AND NOT (c.track_id = ANY (:seeds))
            GROUP BY c.track_id
            """.trimIndent(),
        ).param("s", sessionId).param("seeds", seedIds.toTypedArray())
            .query { rs, _ -> ScoredInput(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), parseVector(rs.getString(5)), rs.getDouble(6), rs.getInt(7)) }.list()

    // ── 추천 ──

    fun replaceRecommendations(sessionId: Long, rows: List<RecRow>) {
        jdbc.sql("DELETE FROM recommendation WHERE session_id = :s").param("s", sessionId).update()
        rows.forEach { r ->
            jdbc.sql(
                "INSERT INTO recommendation (session_id, rank, track_id, score, reason, evidence, based_on, model) VALUES (:s, :r, :t, :sc, :re, :ev, :b, :m)",
            ).param("s", sessionId).param("r", r.rank).param("t", r.trackId).param("sc", r.score).param("re", r.reason)
                .param("ev", r.evidence.toTypedArray()).param("b", r.basedOn.toTypedArray()).param("m", r.model).update()
        }
    }

    fun recommendations(sessionId: Long): List<RecRow> =
        jdbc.sql("SELECT rank, track_id, score, reason, evidence, based_on, model FROM recommendation WHERE session_id = :s ORDER BY rank").param("s", sessionId)
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                fun longs(i: Int) = (rs.getArray(i)?.array as Array<Long>?)?.toList().orEmpty()
                RecRow(rs.getInt(1), rs.getLong(2), rs.getDouble(3), rs.getString(4), longs(5), longs(6), rs.getString(7))
            }.list()

    /** 곡 id → (제목, 가수 이름). 벡터 이웃 후보의 이름을 채울 때 */
    fun trackNames(ids: Collection<Long>): Map<Long, Pair<String, String>> =
        if (ids.isEmpty()) emptyMap() else jdbc.sql("SELECT t.id, t.title, a.name FROM track t JOIN artist a ON a.id = t.artist_id WHERE t.id = ANY (:ids)")
            .param("ids", ids.toTypedArray()).query { rs, _ -> rs.getLong(1) to (rs.getString(2) to rs.getString(3)) }.list().toMap()

    /** 설명과 벡터가 이미 있는 곡 (후보 보강을 건너뛴다) */
    fun withVector(ids: Collection<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet() else jdbc.sql("SELECT track_id FROM track_vector WHERE track_id = ANY (:ids)")
            .param("ids", ids.toTypedArray()).query { rs, _ -> rs.getLong(1) }.list().toSet()
}
