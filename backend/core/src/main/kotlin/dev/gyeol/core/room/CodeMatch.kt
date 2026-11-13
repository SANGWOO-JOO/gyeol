package dev.gyeol.core.room

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.sqrt

/** 한 사람이 담은 노래 하나: 지도 자리가 없는 가수면 x·y·galaxy가 null */
data class SongPoint(val trackId: Long, val artistId: Long, val artistName: String, val x: Double?, val y: Double?, val galaxyId: Long?, val galaxyName: String?)

/** 항목별 0~1 점수와 비중 */
data class CodeMatchWeights(val genre: Double = 0.4, val distance: Double = 0.3, val artist: Double = 0.2, val song: Double = 0.1)

data class CodeMatchParts(
    val genre: Double, val distance: Double, val artist: Double, val song: Double,
    val sharedArtists: List<String>, val sharedGalaxies: List<String>, val sharedSongs: Int,
)

/**
 * ⑤ 코드 맞추기 점수 (room-plan.md ⑰): LLM 없이 담은 노래만으로 계산한다.
 * - 장르: 구역별 곡 수 비율의 코사인 유사도
 * - 지도 거리: 각자 행성(가수)마다 상대 행성 중 가장 가까운 것까지 거리의 평균 → 1 − 거리/[FAR] (0 아래는 0)
 * - 겹친 가수·노래: 겹친 수 ÷ 둘 중 적은 쪽 수
 */
object CodeMatchScore {
    /** 지도 좌표(0~1)에서 이 거리 이상이면 "가깝지 않음" 0점. 판 5 구역 크기(지름 약 0.1~0.2)를 보고 정함 */
    const val FAR = 0.3

    fun parts(a: List<SongPoint>, b: List<SongPoint>): CodeMatchParts {
        val ga = a.mapNotNull { it.galaxyId }.groupingBy { it }.eachCount()
        val gb = b.mapNotNull { it.galaxyId }.groupingBy { it }.eachCount()
        val genre = cosine(ga, gb)

        val pa = a.filter { it.x != null }.distinctBy { it.artistId }
        val pb = b.filter { it.x != null }.distinctBy { it.artistId }
        val distance = if (pa.isEmpty() || pb.isEmpty()) 0.0 else {
            val d = (pa.map { p -> pb.minOf { dist(p, it) } } + pb.map { p -> pa.minOf { dist(p, it) } }).average()
            (1 - d / FAR).coerceIn(0.0, 1.0)
        }

        val aa = a.map { it.artistId }.toSet()
        val ab = b.map { it.artistId }.toSet()
        val sharedArtistIds = aa intersect ab
        val artist = sharedArtistIds.size.toDouble() / minOf(aa.size, ab.size).coerceAtLeast(1)

        val ta = a.map { it.trackId }.toSet()
        val tb = b.map { it.trackId }.toSet()
        val sharedSongs = (ta intersect tb).size
        val song = sharedSongs.toDouble() / minOf(ta.size, tb.size).coerceAtLeast(1)

        val names = a.associate { it.artistId to it.artistName }
        val galaxyNames = (a + b).filter { it.galaxyId != null && it.galaxyName != null }.associate { it.galaxyId!! to it.galaxyName!! }
        val sharedGalaxies = (ga.keys intersect gb.keys).sortedByDescending { minOf(ga[it]!!, gb[it]!!) }.mapNotNull(galaxyNames::get)
        return CodeMatchParts(genre, distance, artist, song, sharedArtistIds.mapNotNull(names::get).sorted(), sharedGalaxies, sharedSongs)
    }

    fun score(p: CodeMatchParts, w: CodeMatchWeights): Int {
        val total = w.genre + w.distance + w.artist + w.song
        val raw = (w.genre * p.genre + w.distance * p.distance + w.artist * p.artist + w.song * p.song) / total
        return (raw * 100).let { Math.round(it).toInt() }.coerceIn(0, 100)
    }

    /** 둘 중 적은 쪽 곡 수로 정하는 신뢰도 (11-13 시작값: 5~9 낮음, 10~19 보통, 20 이상 높음) */
    fun confidence(minSongs: Int): String = when {
        minSongs >= 20 -> "HIGH"
        minSongs >= 10 -> "MEDIUM"
        else -> "LOW"
    }

    /** 두 사람의 곡 목록 + 지도 판. 같으면 저장 결과를 다시 쓴다 */
    fun inputHash(a: List<SongPoint>, b: List<SongPoint>, mapVersion: Long?): String {
        val s = a.map { it.trackId }.sorted().joinToString(",") + "|" + b.map { it.trackId }.sorted().joinToString(",") + "|" + (mapVersion ?: 0)
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun dist(p: SongPoint, q: SongPoint): Double {
        val dx = p.x!! - q.x!!
        val dy = p.y!! - q.y!!
        return sqrt(dx * dx + dy * dy)
    }

    private fun cosine(a: Map<Long, Int>, b: Map<Long, Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dot = a.entries.sumOf { (k, v) -> v.toDouble() * (b[k] ?: 0) }
        val na = sqrt(a.values.sumOf { it.toDouble() * it })
        val nb = sqrt(b.values.sumOf { it.toDouble() * it })
        return dot / (na * nb)
    }
}

data class CodeMatchRow(
    val id: Long, val roomId: Long, val memberA: Long, val memberB: Long, val inputHash: String, val score: Int,
    val partsJson: String, val reason: String?, val evidence: List<Long>, val status: String, val createdAt: java.time.Instant,
)

/** 이유 쓰기 재료: 겹친 가수(없으면 각자 대표 가수)의 근거 기반 설명 */
data class ArtistEvidence(val artistId: Long, val name: String, val description: String, val evidence: List<Long>)

@Repository
class CodeMatchRepository(private val jdbc: JdbcClient) {

    private val mapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        CodeMatchRow(
            rs.getLong("id"), rs.getLong("room_id"), rs.getLong("member_a"), rs.getLong("member_b"), rs.getString("input_hash"),
            rs.getInt("score"), rs.getString("parts"), rs.getString("reason"),
            (rs.getArray("evidence").array as Array<Long>).toList(), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
        )
    }

    /** 이 사람이 담은 노래와 활성 지도 판의 자리·구역 */
    fun songPoints(memberId: Long): List<SongPoint> =
        jdbc.sql(
            """
            SELECT s.track_id, s.artist_id, a.name, p.x, p.y, p.galaxy_id, g.name
            FROM room_song s JOIN artist a ON a.id = s.artist_id
            LEFT JOIN map_version v ON v.active
            LEFT JOIN artist_position p ON p.artist_id = s.artist_id AND p.map_version = v.id
            LEFT JOIN galaxy g ON g.id = p.galaxy_id
            WHERE s.member_id = :m
            """.trimIndent(),
        ).param("m", memberId).query { rs, _ ->
            SongPoint(
                rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4) as Double?, rs.getObject(5) as Double?,
                rs.getObject(6) as Long?, rs.getString(7),
            )
        }.list()

    fun find(roomId: Long, a: Long, b: Long, hash: String): CodeMatchRow? =
        jdbc.sql("SELECT * FROM code_match WHERE room_id = :r AND member_a = :a AND member_b = :b AND input_hash = :h")
            .param("r", roomId).param("a", a).param("b", b).param("h", hash).query(mapper).optional().orElse(null)

    fun latest(roomId: Long, a: Long, b: Long): CodeMatchRow? =
        jdbc.sql("SELECT * FROM code_match WHERE room_id = :r AND member_a = :a AND member_b = :b ORDER BY created_at DESC, id DESC LIMIT 1")
            .param("r", roomId).param("a", a).param("b", b).query(mapper).optional().orElse(null)

    fun get(id: Long): CodeMatchRow? = jdbc.sql("SELECT * FROM code_match WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    /** 방의 오늘(한국 날짜) 새 계산 수 */
    fun countToday(roomId: Long, today: LocalDate): Int =
        jdbc.sql("SELECT count(*) FROM code_match WHERE room_id = :r AND (created_at AT TIME ZONE 'Asia/Seoul')::date = :d")
            .param("r", roomId).param("d", today).query(Int::class.java).single()

    /** 같은 입력이 동시에 들어오면 하나만 들어가고 null */
    fun insert(roomId: Long, a: Long, b: Long, hash: String, score: Int, partsJson: String): Long? =
        jdbc.sql(
            """
            INSERT INTO code_match (room_id, member_a, member_b, input_hash, score, parts, status)
            VALUES (:r, :a, :b, :h, :s, CAST(:p AS jsonb), 'REASONING')
            ON CONFLICT (room_id, member_a, member_b, input_hash) DO NOTHING RETURNING id
            """.trimIndent(),
        ).param("r", roomId).param("a", a).param("b", b).param("h", hash).param("s", score).param("p", partsJson)
            .query(Long::class.java).optional().orElse(null)

    fun finish(id: Long, reason: String?, evidence: List<Long>) {
        jdbc.sql("UPDATE code_match SET reason = :t, evidence = :e, status = :s WHERE id = :id AND status = 'REASONING'")
            .param("t", reason).param("e", evidence.toTypedArray()).param("s", if (reason == null) "NO_REASON" else "DONE").param("id", id).update()
    }

    /** 설명이 있는 가수만, 근거 번호와 함께 */
    fun artistEvidence(artistIds: Collection<Long>): List<ArtistEvidence> {
        if (artistIds.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            SELECT a.id, a.name, p.description, p.evidence FROM artist a JOIN artist_profile p ON p.artist_id = a.id
            WHERE a.id = ANY (:ids) AND p.description IS NOT NULL AND cardinality(p.evidence) > 0
            """.trimIndent(),
        ).param("ids", artistIds.toTypedArray()).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            ArtistEvidence(rs.getLong(1), rs.getString(2), rs.getString(3), (rs.getArray(4).array as Array<Long>).toList())
        }.list()
    }
}
