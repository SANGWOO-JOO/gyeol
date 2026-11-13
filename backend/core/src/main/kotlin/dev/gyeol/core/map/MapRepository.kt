package dev.gyeol.core.map

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class ArtistRef(val artistId: Long, val mbid: UUID, val name: String)
data class ArtistChunk(val id: Long, val source: String, val lang: String, val url: String, val text: String)
data class Position(val artistId: Long, val x: Double, val y: Double, val galaxyId: Long?)
data class GalaxyInfo(val id: Long, val label: Int, val name: String?, val cx: Double, val cy: Double)
data class Neighbor(val artistId: Long, val name: String, val x: Double, val y: Double, val galaxyId: Long?, val distance: Double)
data class ArtistDetail(val artistId: Long, val name: String, val description: String?, val evidence: List<Long>, val country: String?)

/** 음악 지도 (V23): 가수 설명·벡터·좌표·구역·새 가수 준비 */
@Repository
class MapRepository(private val jdbc: JdbcClient) {

    private fun vec(v: FloatArray) = v.joinToString(",", "[", "]")
    private fun parseVec(s: String) = s.trim('[', ']').split(',').map { it.toFloat() }.toFloatArray()

    // ── 가수 ──

    fun upsertArtist(mbid: UUID, name: String, sortName: String?, aliases: List<String>): Long =
        jdbc.sql(
            """
            INSERT INTO artist (mbid, name, sort_name, aliases) VALUES (:mbid, :name, :sort, :aliases)
            ON CONFLICT (mbid) DO UPDATE SET aliases = COALESCE((SELECT array_agg(DISTINCT x) FROM unnest(artist.aliases || EXCLUDED.aliases) x), '{}')
            RETURNING id
            """.trimIndent(),
        ).param("mbid", mbid).param("name", name).param("sort", sortName).param("aliases", aliases.toTypedArray()).query(Long::class.java).single()

    fun artist(id: Long): ArtistRef? =
        jdbc.sql("SELECT id, mbid, name FROM artist WHERE id = :id").param("id", id)
            .query { rs, _ -> ArtistRef(rs.getLong(1), rs.getObject(2) as UUID, rs.getString(3)) }.optional().orElse(null)

    /** 초기 지도 가수로 표시한다. 이미 있으면 나라·순위만 채운다 */
    fun markSeed(artistId: Long, country: String, rank: Int) {
        jdbc.sql(
            """
            INSERT INTO artist_profile (artist_id, country, seed_country, seed_rank) VALUES (:a, :c, :c, :r)
            ON CONFLICT (artist_id) DO UPDATE SET country = EXCLUDED.country, seed_country = EXCLUDED.seed_country, seed_rank = EXCLUDED.seed_rank
            """.trimIndent(),
        ).param("a", artistId).param("c", country).param("r", rank).update()
    }

    fun ensureProfile(artistId: Long, country: String?) {
        jdbc.sql("INSERT INTO artist_profile (artist_id, country) VALUES (:a, :c) ON CONFLICT (artist_id) DO NOTHING")
            .param("a", artistId).param("c", country).update()
    }

    fun seedCount(country: String): Int =
        jdbc.sql("SELECT count(*) FROM artist_profile WHERE seed_country = :c").param("c", country).query(Int::class.java).single()

    fun seededMbids(country: String): Set<UUID> =
        jdbc.sql("SELECT a.mbid FROM artist_profile p JOIN artist a ON a.id = p.artist_id WHERE p.seed_country = :c").param("c", country)
            .query { rs, _ -> rs.getObject(1) as UUID }.set()

    fun country(artistId: Long): String? =
        jdbc.sql("SELECT country FROM artist_profile WHERE artist_id = :a").param("a", artistId).query(String::class.java).optional().orElse(null)

    // ── 설명 ──

    fun chunks(mbid: UUID): List<ArtistChunk> =
        jdbc.sql("SELECT id, source, lang, url, text FROM knowledge_chunk WHERE entity_type = 'ARTIST' AND entity_mbid = :m ORDER BY id").param("m", mbid)
            .query { rs, _ -> ArtistChunk(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }.list()

    fun chunksByIds(ids: Collection<Long>): List<ArtistChunk> =
        if (ids.isEmpty()) emptyList() else jdbc.sql("SELECT id, source, lang, url, text FROM knowledge_chunk WHERE id = ANY (:ids) ORDER BY id")
            .param("ids", ids.toTypedArray()).query { rs, _ -> ArtistChunk(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }.list()

    /** 설명이 아직 없는 가수 (초기 가수 먼저). 근거가 없는 가수는 LLM 없이 "설명 없음"으로 끝낸다 */
    fun needDescription(limit: Int): List<ArtistRef> =
        jdbc.sql(
            """
            SELECT a.id, a.mbid, a.name FROM artist_profile p JOIN artist a ON a.id = p.artist_id
            WHERE p.described_at IS NULL
            ORDER BY p.seed_country NULLS LAST, p.seed_rank, a.id LIMIT :l
            """.trimIndent(),
        ).param("l", limit).query { rs, _ -> ArtistRef(rs.getLong(1), rs.getObject(2) as UUID, rs.getString(3)) }.list()

    fun saveDescription(artistId: Long, description: String?, evidence: List<Long>, model: String) {
        jdbc.sql("UPDATE artist_profile SET description = :d, evidence = :e, model = :m, described_at = now() WHERE artist_id = :a")
            .param("a", artistId).param("d", description).param("e", evidence.toTypedArray()).param("m", model).update()
    }

    /** 설명이 있는 가수 (artistId, 설명) */
    fun describedTexts(): List<Pair<Long, String>> =
        jdbc.sql("SELECT artist_id, description FROM artist_profile WHERE description IS NOT NULL ORDER BY artist_id")
            .query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list()

    /** 설명만 지운다 (근거 번호는 남김). described_at을 새로 찍어 벡터를 다시 만들게 한다 */
    fun clearDescription(artistId: Long) {
        jdbc.sql("UPDATE artist_profile SET description = NULL, described_at = now() WHERE artist_id = :a").param("a", artistId).update()
    }

    fun detail(artistId: Long): ArtistDetail? =
        jdbc.sql(
            """
            SELECT a.id, a.name, p.description, coalesce(p.evidence, '{}'), p.country
            FROM artist a LEFT JOIN artist_profile p ON p.artist_id = a.id WHERE a.id = :a
            """.trimIndent(),
        ).param("a", artistId).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            ArtistDetail(rs.getLong(1), rs.getString(2), rs.getString(3), (rs.getArray(4).array as Array<Long>).toList(), rs.getString(5))
        }.optional().orElse(null)

    // ── 벡터 ──

    /** 벡터를 (다시) 만들어야 하는 가수: 근거가 있고, 설명이 있거나 설명을 만들 수 없는 경우 */
    fun needVector(limit: Int): List<ArtistRef> =
        jdbc.sql(
            """
            SELECT a.id, a.mbid, a.name FROM artist_profile p JOIN artist a ON a.id = p.artist_id
            LEFT JOIN artist_vector v ON v.artist_id = a.id
            WHERE p.described_at IS NOT NULL AND (v.artist_id IS NULL OR v.created_at < p.described_at)
            ORDER BY p.seed_country NULLS LAST, p.seed_rank, a.id LIMIT :l
            """.trimIndent(),
        ).param("l", limit).query { rs, _ -> ArtistRef(rs.getLong(1), rs.getObject(2) as UUID, rs.getString(3)) }.list()

    fun description(artistId: Long): String? =
        jdbc.sql("SELECT description FROM artist_profile WHERE artist_id = :a").param("a", artistId).query(String::class.java).optional().orElse(null)

    fun saveVector(artistId: Long, model: String, raw: FloatArray, descr: FloatArray?, mixed: FloatArray, hash: String) {
        jdbc.sql(
            """
            INSERT INTO artist_vector (artist_id, model, raw, descr, mixed, text_hash)
            VALUES (:a, :m, CAST(:r AS vector), CAST(:d AS vector), CAST(:x AS vector), :h)
            ON CONFLICT (artist_id) DO UPDATE SET model = EXCLUDED.model, raw = EXCLUDED.raw, descr = EXCLUDED.descr, mixed = EXCLUDED.mixed,
                text_hash = EXCLUDED.text_hash, created_at = now()
            """.trimIndent(),
        ).param("a", artistId).param("m", model).param("r", vec(raw)).param("d", descr?.let(::vec)).param("x", vec(mixed)).param("h", hash).update()
    }

    fun mixedVector(artistId: Long): FloatArray? =
        jdbc.sql("SELECT mixed::text FROM artist_vector WHERE artist_id = :a").param("a", artistId).query(String::class.java).optional().orElse(null)?.let(::parseVec)

    // ── 좌표·구역 ──

    fun activeVersion(): Long? =
        jdbc.sql("SELECT id FROM map_version WHERE active").query(Long::class.java).optional().orElse(null)

    fun positions(version: Long, artistIds: Collection<Long>): Map<Long, Position> =
        if (artistIds.isEmpty()) emptyMap() else
            jdbc.sql("SELECT artist_id, x, y, galaxy_id FROM artist_position WHERE map_version = :v AND artist_id = ANY (:ids)")
                .param("v", version).param("ids", artistIds.toTypedArray())
                .query { rs, _ -> Position(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getObject(4) as Long?) }.list().associateBy { it.artistId }

    fun galaxies(ids: Collection<Long>): List<GalaxyInfo> =
        if (ids.isEmpty()) emptyList() else jdbc.sql("SELECT id, label, name, cx, cy FROM galaxy WHERE id = ANY (:ids) ORDER BY id").param("ids", ids.toTypedArray())
            .query { rs, _ -> GalaxyInfo(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5)) }.list()

    /** 이 판에서 [vector]와 가까운, 자리가 있는 가수 (자기 자신·[exclude] 제외) */
    /**
     * 자리 잡은 가수 중 가까운 [k]명. [byRaw]면 원문 벡터끼리 비교한다:
     * 설명 없는 가수는 섞은 벡터가 원문 100%라, 섞은 벡터끼리 비교하면 설명 없는 가수끼리만 가까워진다 (11-13 지도 판 4)
     */
    fun nearestPlaced(version: Long, vector: FloatArray, k: Int, exclude: Collection<Long> = emptyList(), byRaw: Boolean = false): List<Neighbor> {
        val col = if (byRaw) "v.raw" else "v.mixed"
        return jdbc.sql(
            """
            SELECT a.id, a.name, p.x, p.y, p.galaxy_id, $col <=> CAST(:q AS vector) AS d
            FROM artist_vector v JOIN artist_position p ON p.artist_id = v.artist_id AND p.map_version = :ver JOIN artist a ON a.id = v.artist_id
            WHERE NOT (v.artist_id = ANY (:ex))
            ORDER BY $col <=> CAST(:q AS vector) LIMIT :k
            """.trimIndent(),
        ).param("q", vec(vector)).param("ver", version).param("k", k).param("ex", exclude.toTypedArray())
            .query { rs, _ -> Neighbor(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getObject(5) as Long?, rs.getDouble(6)) }.list()
    }

    /** 자리 잡기에 쓸 벡터: 설명 벡터가 있으면 (섞은 벡터, false), 없으면 (원문 벡터, true) */
    fun placeVector(artistId: Long): Pair<FloatArray, Boolean>? =
        jdbc.sql("SELECT CASE WHEN descr IS NULL THEN raw ELSE mixed END::text, descr IS NULL FROM artist_vector WHERE artist_id = :a")
            .param("a", artistId).query { rs, _ -> parseVec(rs.getString(1)) to rs.getBoolean(2) }.optional().orElse(null)

    fun savePosition(version: Long, artistId: Long, x: Double, y: Double, galaxyId: Long?, placedBy: String) {
        jdbc.sql(
            """
            INSERT INTO artist_position (map_version, artist_id, x, y, galaxy_id, placed_by) VALUES (:v, :a, :x, :y, :g, :p)
            ON CONFLICT (map_version, artist_id) DO NOTHING
            """.trimIndent(),
        ).param("v", version).param("a", artistId).param("x", x).param("y", y).param("g", galaxyId).param("p", placedBy).update()
    }

    /** 이름이 없는 구역과 소속 가수의 설명 (구역 이름 붙이기) */
    fun unnamedGalaxies(version: Long): List<GalaxyInfo> =
        jdbc.sql("SELECT id, label, name, cx, cy FROM galaxy WHERE map_version = :v AND name IS NULL ORDER BY label").param("v", version)
            .query { rs, _ -> GalaxyInfo(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5)) }.list()

    data class Member(val artistId: Long, val name: String, val description: String?, val evidence: List<Long>)

    /** 구역 중심에 가까운 가수부터 [limit]명 */
    fun galaxyMembers(galaxyId: Long, limit: Int): List<Member> =
        jdbc.sql(
            """
            SELECT a.id, a.name, p.description, coalesce(p.evidence, '{}')
            FROM artist_position ap JOIN galaxy g ON g.id = ap.galaxy_id JOIN artist a ON a.id = ap.artist_id
            LEFT JOIN artist_profile p ON p.artist_id = a.id
            WHERE ap.galaxy_id = :g ORDER BY (ap.x - g.cx) ^ 2 + (ap.y - g.cy) ^ 2 LIMIT :l
            """.trimIndent(),
        ).param("g", galaxyId).param("l", limit).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            Member(rs.getLong(1), rs.getString(2), rs.getString(3), (rs.getArray(4).array as Array<Long>).toList())
        }.list()

    fun nameGalaxy(id: Long, name: String, reason: String?, evidence: List<Long>) {
        jdbc.sql("UPDATE galaxy SET name = :n, reason = :r, evidence = :e WHERE id = :id")
            .param("id", id).param("n", name).param("r", reason).param("e", evidence.toTypedArray()).update()
    }

    /** 이 가수가 담긴 방들의 지도 rev를 올린다 (새 가수가 자리를 잡았을 때) */
    fun bumpRoomsWithArtist(artistId: Long): Int =
        jdbc.sql("UPDATE room SET map_rev = map_rev + 1 WHERE id IN (SELECT DISTINCT room_id FROM room_song WHERE artist_id = :a)").param("a", artistId).update()

    // ── 새 가수 준비 ──

    /** 준비를 요청한다. 이미 요청했으면 false */
    fun requestPrepare(artistId: Long): Boolean =
        jdbc.sql("INSERT INTO artist_prepare (artist_id) VALUES (:a) ON CONFLICT (artist_id) DO NOTHING").param("a", artistId).update() == 1

    fun prepareStep(artistId: Long, step: String) {
        jdbc.sql("UPDATE artist_prepare SET step = :s WHERE artist_id = :a").param("a", artistId).param("s", step).update()
    }

    fun finishPrepare(artistId: Long, status: String, error: String? = null) {
        jdbc.sql("UPDATE artist_prepare SET status = :s, error = :e, done_at = now() WHERE artist_id = :a")
            .param("a", artistId).param("s", status).param("e", error?.take(500)).update()
    }

    fun prepareStatus(artistIds: Collection<Long>): Map<Long, String> =
        if (artistIds.isEmpty()) emptyMap() else jdbc.sql("SELECT artist_id, status FROM artist_prepare WHERE artist_id = ANY (:ids)")
            .param("ids", artistIds.toTypedArray()).query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list().toMap()
}
