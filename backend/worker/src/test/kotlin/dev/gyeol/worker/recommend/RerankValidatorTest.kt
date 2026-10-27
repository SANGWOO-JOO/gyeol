package dev.gyeol.worker.recommend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RerankValidatorTest {
    private val seeds = listOf(RerankSeed(1, 100, "s", "x"), RerankSeed(2, 200, "t", "y"))
    private val cands = (1..4).map { RerankCandidate(it, 10L + it, "곡$it", "가수$it", null, listOf(1000L + it to "근거"), score = 1.0 - it * 0.1) }

    private fun result(vararg picks: Pick) = RerankResult(picks.toList(), "요약", "m", 0, 0, 0)

    @Test
    fun `없는 후보·중복은 버리고, 근거는 그 후보 것만, 모자라면 점수 순으로 채운다`() {
        val out = RerankValidator.validate(
            result(
                Pick(3, "이유3", listOf(1003, 1001), listOf(2, 9)),
                Pick(3, "중복", listOf(1003), emptyList()),
                Pick(99, "없는 후보", emptyList(), emptyList()),
                Pick(2, "근거 없는 이유", listOf(1004), emptyList()),
            ),
            seeds, cands, picks = 3,
        )
        assertEquals(listOf(13L, 12L, 11L), out.map { it.candidate.trackId })
        assertEquals(listOf(1003L), out[0].evidence)
        assertEquals(listOf(200L), out[0].basedOn)
        assertEquals("이유3", out[0].reason)
        assertEquals(null, out[1].reason) // 남의 근거만 댄 이유는 쓰지 않는다
        assertEquals(null, out[2].reason) // 점수 순으로 채운 곡
    }
}
