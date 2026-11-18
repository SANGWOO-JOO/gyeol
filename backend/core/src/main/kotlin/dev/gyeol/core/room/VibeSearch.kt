package dev.gyeol.core.room

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/**
 * 분위기로 가수 찾기 (docs/todo/vibe-search-rag.md). 같은 질문인지 정할 때 쓰는 정규화:
 * 앞뒤 공백 제거, 공백 묶음은 한 칸, 영문은 소문자
 */
object VibeQuery {
    fun normalize(q: String): String = q.trim().replace(Regex("\\s+"), " ").lowercase()

    fun hash(q: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalize(q).toByteArray()).joinToString("") { "%02x".format(it) }

    /** 글자 수 (한글·이모지 모두 한 글자) */
    fun length(q: String): Int = q.trim().let { it.codePointCount(0, it.length) }
}

data class VibeSearchRow(
    val id: Long, val roomId: Long, val memberId: Long, val query: String, val queryHash: String, val mapVersion: Long,
    val status: String, val hitsJson: String?, val picksJson: String?, val evidence: List<Long>, val createdAt: Instant,
)

/** 검색 결과 한 줄: 코사인 거리 (0에 가까울수록 질문과 가까움) */
data class VibeHit(val artistId: Long, val d: Double)

/** LLM이 고른 가수 (검증 뒤). reason이 null이면 카드만 */
data class VibePick(val artistId: Long, val reason: String?, val evidence: List<Long> = emptyList())

@Repository
class VibeSearchRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        VibeSearchRow(
            rs.getLong("id"), rs.getLong("room_id"), rs.getLong("member_id"), rs.getString("query"), rs.getString("query_hash"),
            rs.getLong("map_version"), rs.getString("status"), rs.getString("hits"), rs.getString("picks"),
            (rs.getArray("evidence").array as Array<Long>).toList(), rs.getTimestamp("created_at").toInstant(),
        )
    }

    fun get(id: Long): VibeSearchRow? = jdbc.sql("SELECT * FROM vibe_search WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    /** 같은 방·같은 질문·같은 지도 판의 최근 결과 (FAILED는 다시 쓰지 않는다) */
    fun findReusable(roomId: Long, hash: String, mapVersion: Long): VibeSearchRow? =
        jdbc.sql(
            """
            SELECT * FROM vibe_search WHERE room_id = :r AND query_hash = :h AND map_version = :v AND status <> 'FAILED'
            ORDER BY created_at DESC, id DESC LIMIT 1
            """.trimIndent(),
        ).param("r", roomId).param("h", hash).param("v", mapVersion).query(mapper).optional().orElse(null)

    /** 이 멤버의 오늘(한국 날짜) 새 요청 수 */
    fun countToday(memberId: Long, today: LocalDate): Int =
        jdbc.sql("SELECT count(*) FROM vibe_search WHERE member_id = :m AND (created_at AT TIME ZONE 'Asia/Seoul')::date = :d")
            .param("m", memberId).param("d", today).query(Int::class.java).single()

    fun insert(roomId: Long, memberId: Long, query: String, hash: String, mapVersion: Long): Long =
        jdbc.sql(
            """
            INSERT INTO vibe_search (room_id, member_id, query, query_hash, map_version, status)
            VALUES (:r, :m, :q, :h, :v, 'PENDING') RETURNING id
            """.trimIndent(),
        ).param("r", roomId).param("m", memberId).param("q", query).param("h", hash).param("v", mapVersion).query(Long::class.java).single()

    /** PENDING일 때만 끝낸다 (같은 메시지를 두 번 받아도 처음 결과 유지) */
    fun finish(id: Long, status: String, hitsJson: String?, picksJson: String?, evidence: List<Long>): Boolean =
        jdbc.sql(
            """
            UPDATE vibe_search SET status = :s, hits = CAST(:h AS jsonb), picks = CAST(:p AS jsonb), evidence = :e
            WHERE id = :id AND status = 'PENDING'
            """.trimIndent(),
        ).param("s", status).param("h", hitsJson).param("p", picksJson).param("e", evidence.toTypedArray()).param("id", id).update() == 1

    /**
     * 질문 벡터와 가까운 가수 [limit]명 (설명·근거 있는 가수만). [column]은 artist_vector의 descr·mixed·raw 중 하나.
     * [mapVersion]이 0보다 크면 그 지도 판에 자리가 있는 가수만 (지도 전체 검색, D5)
     */
    fun search(vector: FloatArray, column: String, limit: Int, mapVersion: Long): List<VibeHit> {
        require(column in COLUMNS) { "vector-column은 ${COLUMNS.joinToString("·")} 중 하나여야 합니다: $column" }
        val onMap = if (mapVersion > 0) "AND EXISTS (SELECT 1 FROM artist_position ap WHERE ap.artist_id = v.artist_id AND ap.map_version = :mv)" else ""
        val spec = jdbc.sql(
            """
            SELECT v.artist_id, v.$column <=> CAST(:q AS vector) AS d
            FROM artist_vector v JOIN artist_profile p ON p.artist_id = v.artist_id
            WHERE v.$column IS NOT NULL AND p.description IS NOT NULL AND cardinality(p.evidence) > 0 $onMap
            ORDER BY v.$column <=> CAST(:q AS vector)
            LIMIT :l
            """.trimIndent(),
        ).param("q", vector.joinToString(",", "[", "]")).param("l", limit)
        return (if (mapVersion > 0) spec.param("mv", mapVersion) else spec)
            .query { rs, _ -> VibeHit(rs.getLong(1), rs.getDouble(2)) }.list()
    }

    /** 방에서 이 가수들의 노래를 담은 사람 닉네임 (나가지 않은 사람만, 담은 순) */
    fun inRoomBy(roomId: Long, artistIds: Collection<Long>): Map<Long, List<String>> {
        if (artistIds.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT s.artist_id, m.nickname, min(s.added_at) AS t
            FROM room_song s JOIN member m ON m.id = s.member_id AND m.removed_at IS NULL
            WHERE s.room_id = :r AND s.artist_id = ANY (:ids)
            GROUP BY s.artist_id, m.nickname ORDER BY t
            """.trimIndent(),
        ).param("r", roomId).param("ids", artistIds.toTypedArray())
            .query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list()
            .groupBy({ it.first }, { it.second })
    }

    fun artistNames(ids: Collection<Long>): Map<Long, String> =
        if (ids.isEmpty()) emptyMap() else jdbc.sql("SELECT id, name FROM artist WHERE id = ANY (:ids)").param("ids", ids.toTypedArray())
            .query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list().toMap()

    companion object {
        val COLUMNS = setOf("descr", "mixed", "raw")
    }
}
