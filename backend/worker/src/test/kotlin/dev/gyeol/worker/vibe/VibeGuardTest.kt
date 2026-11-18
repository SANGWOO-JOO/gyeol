package dev.gyeol.worker.vibe

import dev.gyeol.core.room.VibeHit
import dev.gyeol.core.room.VibePick
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 분위기 찾기 출력 검증 (docs/todo/vibe-search-rag.md 6절) */
class VibeGuardTest {
    private val hits = listOf(VibeHit(1, 0.1), VibeHit(2, 0.2), VibeHit(3, 0.3), VibeHit(4, 0.4), VibeHit(5, 0.5), VibeHit(6, 0.6))
    private val allowed = setOf(11L, 12L, 13L)

    @Test
    fun `top-k 밖 가수와 중복은 버리고 최대 수까지`() {
        val raw = listOf(
            RawVibePick(99, "지어낸 가수예요.", listOf(11)),
            RawVibePick(2, "몽환적인 기타가 비 오는 밤과 닿아요.", listOf(11)),
            RawVibePick(2, "중복", listOf(12)),
            RawVibePick(3, "느린 템포예요.", listOf(12)),
            RawVibePick(4, "a", listOf(13)), RawVibePick(5, "b", listOf(13)), RawVibePick(6, "c", listOf(13)), RawVibePick(1, "d", listOf(13)),
        )
        val out = VibeGuard.validate(raw, hits, allowed, 3, 5)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), out.map { it.artistId })
        assertEquals("몽환적인 기타가 비 오는 밤과 닿아요.", out.first().reason)
    }

    @Test
    fun `준 적 없는 청크 번호는 지우고 유효 번호가 0개면 이유 없이 카드만`() {
        val raw = listOf(
            RawVibePick(1, "이유 하나", listOf(11, 777)),
            RawVibePick(2, "근거 없는 이유", listOf(888)),
            RawVibePick(3, "근거 번호 없음", emptyList()),
        )
        val out = VibeGuard.validate(raw, hits, allowed, 3, 5)
        assertEquals(VibePick(1, "이유 하나", listOf(11)), out[0])
        assertEquals(VibePick(2, null), out[1])
        assertEquals(VibePick(3, null), out[2])
    }

    @Test
    fun `거절문·빈 이유는 버린다`() {
        val raw = listOf(RawVibePick(1, "죄송하지만 근거 자료에 정보가 없어요", listOf(11)), RawVibePick(2, "   ", listOf(12)), RawVibePick(3, null, listOf(13)))
        assertEquals(List(3) { null }, VibeGuard.validate(raw, hits, allowed, 3, 5).map { it.reason })
    }

    @Test
    fun `적게 고르거나 아무도 안 고르면 검색 순서대로 이유 없는 카드로 최소 수를 채운다`() {
        assertEquals(listOf(4L, 1L, 2L), VibeGuard.validate(listOf(RawVibePick(4, "x", listOf(11))), hits, allowed, 3, 5).map { it.artistId })
        val none = VibeGuard.validate(emptyList(), hits, allowed, 3, 5)
        assertEquals(listOf(1L, 2L, 3L), none.map { it.artistId })
        assertEquals(listOf(VibePick(1, null)), VibeGuard.validate(emptyList(), hits.take(1), allowed, 3, 5), "검색 결과가 적으면 있는 만큼")
    }
}
