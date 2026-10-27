package dev.gyeol.core.knowledge

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** 곡 설명 중 임베딩할 문장 */
data class DescriptorText(val trackId: Long, val text: String)

data class Neighbor(val trackId: Long, val distance: Double)

/** L3-3 곡 벡터 (pgvector) */
@Repository
class VectorRepository(private val jdbc: JdbcClient) {

    /**
     * 곡 설명에서 임베딩할 문장: 요약과 값이 있는 칸 (근거 부족 칸은 빠진다). 내용이 하나도 없으면 null
     * 예: "요약: … 장르: 발라드, 포크. 분위기: 잔잔함. 보컬: 여성 솔로." (값이 없는 칸은 JSON null이라 종류부터 확인한다)
     */
    fun descriptorText(trackId: Long): DescriptorText? =
        jdbc.sql(
            """
            SELECT concat_ws(' ',
                     CASE WHEN summary IS NOT NULL THEN '요약: ' || summary END,
                     CASE WHEN jsonb_typeof(fields -> 'genres' -> 'value') = 'array' AND jsonb_array_length(fields -> 'genres' -> 'value') > 0
                          THEN '장르: ' || (SELECT string_agg(x, ', ') FROM jsonb_array_elements_text(fields -> 'genres' -> 'value') x) || '.' END,
                     CASE WHEN jsonb_typeof(fields -> 'moods' -> 'value') = 'array' AND jsonb_array_length(fields -> 'moods' -> 'value') > 0
                          THEN '분위기: ' || (SELECT string_agg(x, ', ') FROM jsonb_array_elements_text(fields -> 'moods' -> 'value') x) || '.' END,
                     CASE WHEN jsonb_typeof(fields -> 'vocal' -> 'value') = 'string' THEN '보컬: ' || (fields -> 'vocal' ->> 'value') || '.' END)
            FROM track_descriptor WHERE track_id = :id
            """.trimIndent(),
        ).param("id", trackId).query { rs, _ -> rs.getString(1) }.optional().orElse(null)
            ?.takeIf { it.isNotBlank() }?.let { DescriptorText(trackId, it) }

    fun currentHash(trackId: Long): String? =
        jdbc.sql("SELECT text_hash FROM track_vector WHERE track_id = :id").param("id", trackId).query(String::class.java).optional().orElse(null)

    fun save(trackId: Long, model: String, vector: FloatArray, hash: String) {
        jdbc.sql(
            """
            INSERT INTO track_vector (track_id, model, embedding, text_hash) VALUES (:t, :m, CAST(:v AS vector), :h)
            ON CONFLICT (track_id) DO UPDATE SET model = EXCLUDED.model, embedding = EXCLUDED.embedding, text_hash = EXCLUDED.text_hash, created_at = now()
            """.trimIndent(),
        ).param("t", trackId).param("m", model).param("v", vector.joinToString(",", "[", "]")).param("h", hash).update()
    }

    /** 코사인 거리 순으로 가까운 곡 (HNSW 인덱스). 자기 자신과 [exclude]는 뺀다 */
    fun nearest(trackId: Long, limit: Int, exclude: Collection<Long> = emptyList()): List<Neighbor> =
        jdbc.sql(
            """
            SELECT v.track_id, v.embedding <=> (SELECT embedding FROM track_vector WHERE track_id = :id) AS d
            FROM track_vector v WHERE v.track_id <> :id AND NOT (v.track_id = ANY (:ex))
            ORDER BY v.embedding <=> (SELECT embedding FROM track_vector WHERE track_id = :id) LIMIT :lim
            """.trimIndent(),
        ).param("id", trackId).param("ex", exclude.toTypedArray()).param("lim", limit)
            .query { rs, _ -> Neighbor(rs.getLong(1), rs.getDouble(2)) }.list()
}
