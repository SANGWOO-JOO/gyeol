package dev.gyeol.api.room

import dev.gyeol.core.room.CodeMatchScore
import dev.gyeol.core.room.CodeMatchWeights
import dev.gyeol.core.room.SongPoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeMatchScoreTest {
    private fun s(track: Long, artist: Long, x: Double?, y: Double?, galaxy: Long?) =
        SongPoint(track, artist, "가수$artist", x, y, galaxy, galaxy?.let { "구역$it" })

    @Test
    fun `같은 곡 목록이면 모든 항목 1, 100점`() {
        val a = listOf(s(1, 1, 0.1, 0.1, 1), s(2, 2, 0.5, 0.5, 2))
        val p = CodeMatchScore.parts(a, a)
        assertEquals(1.0, p.genre, 1e-9)
        assertEquals(1.0, p.distance, 1e-9)
        assertEquals(1.0, p.artist, 1e-9)
        assertEquals(1.0, p.song, 1e-9)
        assertEquals(100, CodeMatchScore.score(p, CodeMatchWeights()))
        assertEquals(listOf("가수1", "가수2"), p.sharedArtists)
    }

    @Test
    fun `겹침 없고 멀면 0점, 겹친 가수·노래는 적은 쪽 기준`() {
        val a = listOf(s(1, 1, 0.0, 0.0, 1))
        val b = listOf(s(2, 2, 1.0, 1.0, 2))
        assertEquals(0, CodeMatchScore.score(CodeMatchScore.parts(a, b), CodeMatchWeights()))

        val c = listOf(s(10, 1, 0.0, 0.0, 1), s(11, 2, 0.0, 0.0, 1), s(12, 3, 0.0, 0.0, 1), s(13, 4, 0.0, 0.0, 1))
        val d = listOf(s(10, 1, 0.0, 0.0, 1), s(20, 2, 0.0, 0.0, 1))
        val p = CodeMatchScore.parts(c, d)
        assertEquals(1.0, p.artist, 1e-9) // d의 가수 2명이 모두 c에 있음
        assertEquals(0.5, p.song, 1e-9)   // d의 노래 2곡 중 1곡 겹침
        assertEquals(1, p.sharedSongs)
    }

    @Test
    fun `지도 자리가 없는 곡은 장르·거리에서 빠지고, 구역 비율은 코사인`() {
        val a = listOf(s(1, 1, null, null, null), s(2, 2, 0.2, 0.2, 1), s(3, 3, 0.2, 0.2, 1))
        val b = listOf(s(4, 4, 0.2, 0.2, 1), s(5, 5, 0.2, 0.5, 2))
        val p = CodeMatchScore.parts(a, b)
        assertEquals(2 / (2 * sqrt(2.0)), p.genre, 1e-9) // (2,0)·(1,1)
        assertTrue(p.distance in 0.0..1.0)
        assertEquals(listOf("구역1"), p.sharedGalaxies)
    }

    @Test
    fun `신뢰도 단계와 입력 해시`() {
        assertEquals("LOW", CodeMatchScore.confidence(5))
        assertEquals("MEDIUM", CodeMatchScore.confidence(10))
        assertEquals("HIGH", CodeMatchScore.confidence(20))
        val a = listOf(s(2, 1, null, null, null), s(1, 1, null, null, null))
        assertEquals(CodeMatchScore.inputHash(a, a, 5), CodeMatchScore.inputHash(a.reversed(), a, 5), "곡 순서는 상관없음")
        assertTrue(CodeMatchScore.inputHash(a, a, 5) != CodeMatchScore.inputHash(a, a, 6), "지도 판이 바뀌면 다시 계산")
    }

    /**
     * 안정성 측정 (11-13 관리 세션 요청): 로컬 DB의 지도 자리가 있는 곡으로 40곡짜리 두 사람을 만들고,
     * 5·10·20곡만 무작위로 30번 뽑아 점수의 평균·표준편차·전체(40곡) 점수와의 차이를 본다. 로컬 DB가 있을 때만:
     * CODE_MATCH_MEASURE=1 ./gradlew :backend:api:test --tests '*CodeMatchScoreTest*'
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "CODE_MATCH_MEASURE", matches = "1")
    fun `곡 수별 점수 안정성 측정`() {
        val pool = DriverManager.getConnection("jdbc:postgresql://localhost:15432/gyeol", "gyeol", "gyeol").use { c ->
            c.createStatement().executeQuery(
                """
                SELECT t.id, t.artist_id, a.name, p.x, p.y, p.galaxy_id, g.name FROM track t JOIN artist a ON a.id = t.artist_id
                JOIN map_version v ON v.active JOIN artist_position p ON p.artist_id = t.artist_id AND p.map_version = v.id
                LEFT JOIN galaxy g ON g.id = p.galaxy_id ORDER BY t.id
                """.trimIndent(),
            ).use { rs ->
                generateSequence { if (rs.next()) SongPoint(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6), rs.getString(7)) else null }.toList()
            }
        }
        val w = CodeMatchWeights()
        val rnd = Random(42)
        val galaxies = pool.groupBy { it.galaxyId }.entries.sortedByDescending { it.value.size }.map { it.key }
        val half = galaxies.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        val scenarios = mapOf(
            "무작위 두 사람(곡 풀 공유)" to (pool.shuffled(rnd).take(40) to pool.shuffled(rnd).take(40)),
            "구역이 갈린 두 사람" to (pool.filter { it.galaxyId in half }.shuffled(rnd).take(40) to pool.filter { it.galaxyId !in half }.shuffled(rnd).take(40)),
            "취향이 겹치는 두 사람(같은 구역 쪽)" to pool.filter { it.galaxyId in half }.shuffled(rnd).let { it.take(40) to it.drop(20).take(40) },
        )
        println("곡 풀 ${pool.size}곡, 가수 ${pool.map { it.artistId }.toSet().size}명, 구역 ${galaxies.size}개, 비중 $w")
        for ((name, ab) in scenarios) {
            val (a, b) = ab
            val full = CodeMatchScore.score(CodeMatchScore.parts(a, b), w)
            println("[$name] 각자 ${a.size}곡 전체 점수 $full")
            for (n in listOf(5, 10, 20)) {
                val scores = (1..30).map { CodeMatchScore.score(CodeMatchScore.parts(a.shuffled(rnd).take(n), b.shuffled(rnd).take(n)), w) }
                val mean = scores.average()
                val sd = sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
                val gap = scores.map { abs(it - full) }.average()
                val partsAvg = (1..30).map { CodeMatchScore.parts(a.shuffled(rnd).take(n), b.shuffled(rnd).take(n)) }
                println(
                    "  ${n}곡: 평균 %.1f, 표준편차 %.1f, 최소 %d, 최대 %d, 전체와 평균 차이 %.1f | 항목 평균 장르 %.2f 거리 %.2f 가수 %.2f 노래 %.2f, 가수 0인 비율 %.0f%%".format(
                        mean, sd, scores.min(), scores.max(), gap,
                        partsAvg.map { it.genre }.average(), partsAvg.map { it.distance }.average(), partsAvg.map { it.artist }.average(), partsAvg.map { it.song }.average(),
                        100.0 * partsAvg.count { it.artist == 0.0 } / partsAvg.size,
                    ),
                )
            }
        }
    }
}
