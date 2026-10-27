package dev.gyeol.worker.recommend

import dev.gyeol.core.recommend.ScoredInput
import dev.gyeol.core.recommend.Seed
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScoringTest {
    private fun v(vararg x: Float) = floatArrayOf(*x)
    private fun input(id: Long, artist: String, vec: FloatArray?, lastfm: Double = 0.0, credit: Int = 0) = ScoredInput(id, "곡$id", "가수", artist, vec, lastfm, credit)

    @Test
    fun `프로필은 들은 곡 벡터의 가중 평균을 길이 1로, 벡터가 없으면 null`() {
        val p = Scoring.profile(listOf(Seed(1, 3.0, "a", "x", v(1f, 0f)), Seed(2, 1.0, "b", "x", v(0f, 1f)), Seed(3, 9.0, "c", "x", null)))!!
        assertEquals(0.949, p[0].toDouble(), 0.001)
        assertEquals(0.316, p[1].toDouble(), 0.001)
        assertNull(Scoring.profile(listOf(Seed(1, 1.0, "a", "x", null))))
    }

    @Test
    fun `점수는 벡터·Last fm·크레딧 가중합, 같은 가수는 정한 수까지`() {
        val profile = v(1f, 0f)
        val r = Scoring.rank(
            listOf(input(1, "A", v(1f, 0f)), input(2, "A", v(1f, 0f), lastfm = 0.5), input(3, "A", v(1f, 0f), lastfm = 1.0), input(4, "B", null, lastfm = 1.0, credit = 4)),
            profile, Triple(0.5, 0.3, 0.2), perArtist = 2, limit = 10,
        )
        assertEquals(listOf(3L, 2L, 4L), r.map { it.input.trackId }) // A의 세 번째 곡(1)은 빠진다
        assertEquals(0.8, r[0].score, 1e-9)
        assertEquals(0.5, r[2].score, 1e-9) // 벡터 없음: 0.3 + 0.2 × min(1, 4/2)
    }
}
