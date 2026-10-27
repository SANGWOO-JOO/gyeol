package dev.gyeol.core.knowledge

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** 곡 설명에 넣을 청크 (번호는 DB의 청크 ID, LLM이 이 번호로 인용한다) */
data class EvidenceChunk(val id: Long, val entityType: EntityType, val source: String, val lang: String, val url: String, val text: String)

/** 곡 설명을 만들 곡: 이름과 근거 청크 */
data class DescribeTarget(val trackId: Long, val title: String, val artist: String, val releaseDate: String?, val chunks: List<EvidenceChunk>)

/** L3-2 곡 설명 저장소 */
@Repository
class DescriptorRepository(private val jdbc: JdbcClient) {

    /**
     * 곡 설명 근거: 곡 > 작품 > 음반 > 가수 순으로 최대 [limit]개. 같은 대상에서는 한국어 > 영어 > 일본어, MusicBrainz 문장도 포함
     */
    fun target(trackId: Long, limit: Int = 8): DescribeTarget? {
        val head = jdbc.sql(
            """
            SELECT t.id, coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_title) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), t.title) AS title,
                   coalesce((SELECT mode() WITHIN GROUP (ORDER BY ta.query_artist) FROM title_alias ta WHERE ta.track_id = t.id AND ta.status = 'MATCHED'), a.name) AS artist,
                   t.release_date
            FROM track t JOIN artist a ON a.id = t.artist_id WHERE t.id = :id
            """.trimIndent(),
        ).param("id", trackId).query { rs, _ -> Triple(rs.getString("title"), rs.getString("artist"), rs.getString("release_date")) }.optional().orElse(null) ?: return null
        val chunks = jdbc.sql(
            """
            SELECT c.id, c.entity_type, c.source, c.lang, c.url, c.text
            FROM track t JOIN artist a ON a.id = t.artist_id
            JOIN knowledge_chunk c ON (c.entity_type = 'TRACK' AND c.entity_mbid = t.recording_mbid)
                                   OR (c.entity_type = 'WORK' AND c.entity_mbid = ANY (t.work_mbids))
                                   OR (c.entity_type = 'RELEASE_GROUP' AND c.entity_mbid = t.release_group_mbid)
                                   OR (c.entity_type = 'ARTIST' AND c.entity_mbid = a.mbid)
            WHERE t.id = :id
            ORDER BY CASE c.entity_type WHEN 'TRACK' THEN 0 WHEN 'WORK' THEN 1 WHEN 'RELEASE_GROUP' THEN 2 ELSE 3 END,
                     CASE c.lang WHEN 'ko' THEN 0 WHEN 'en' THEN 1 ELSE 2 END, c.id
            LIMIT :lim
            """.trimIndent(),
        ).param("id", trackId).param("lim", limit).query { rs, _ ->
            EvidenceChunk(rs.getLong(1), EntityType.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
        }.list()
        return DescribeTarget(trackId, head.first, head.second, head.third, chunks)
    }

    /** 설명이 없거나, 마지막 설명 뒤에 관련 지식을 다시 가져왔으면 true */
    fun needsDescribe(trackId: Long): Boolean =
        jdbc.sql(
            """
            SELECT NOT EXISTS (
                SELECT 1 FROM track_descriptor d WHERE d.track_id = :id AND d.created_at >= coalesce((
                    SELECT max(f.fetched_at) FROM track t JOIN artist a ON a.id = t.artist_id
                    JOIN knowledge_fetch f ON (f.entity_type = 'TRACK' AND f.entity_mbid = t.recording_mbid)
                                           OR (f.entity_type = 'WORK' AND f.entity_mbid = ANY (t.work_mbids))
                                           OR (f.entity_type = 'RELEASE_GROUP' AND f.entity_mbid = t.release_group_mbid)
                                           OR (f.entity_type = 'ARTIST' AND f.entity_mbid = a.mbid)
                    WHERE t.id = :id), '-infinity'))
            """.trimIndent(),
        ).param("id", trackId).query(Boolean::class.java).single()

    fun save(trackId: Long, lang: String, summary: String?, fieldsJson: String, model: String, rag: Boolean) {
        jdbc.sql(
            """
            INSERT INTO track_descriptor (track_id, lang, summary, fields, model, rag, created_at) VALUES (:t, :l, :s, CAST(:f AS jsonb), :m, :r, now())
            ON CONFLICT (track_id) DO UPDATE SET lang = EXCLUDED.lang, summary = EXCLUDED.summary, fields = EXCLUDED.fields,
                model = EXCLUDED.model, rag = EXCLUDED.rag, created_at = now()
            """.trimIndent(),
        ).param("t", trackId).param("l", lang).param("s", summary).param("f", fieldsJson).param("m", model).param("r", rag).update()
    }
}
