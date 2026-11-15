package dev.gyeol.fetcher

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 가짜 시계로 초당 1회 제한을 확인한다 (실제로 기다리지 않아 테스트가 빠르다) */
class RateGateTest {

    private var now = 0L
    private val waits = mutableListOf<Long>()
    private val gate = RateGate(
        minInterval = Duration.ofSeconds(1),
        maxWait = Duration.ofMillis(2500),
        clock = { now },
        sleeper = { waits += it },
    )

    @Test
    fun `동시에 몰린 요청은 1초 간격으로 차례를 받는다`() {
        repeat(3) { gate.acquire() }
        assertEquals(listOf(0L, 1_000_000_000L, 2_000_000_000L), waits)
    }

    @Test
    fun `기다릴 시간이 2_5초를 넘으면 바로 거절한다`() {
        repeat(3) { gate.acquire() } // 차례: 0초, 1초, 2초
        assertFailsWith<TooBusyException> { gate.acquire() } // 3초 뒤 차례 → 2.5초 초과
    }

    @Test
    fun `시간이 지나면 다시 바로 통과한다`() {
        gate.acquire()
        now += 5_000_000_000L // 5초 뒤
        gate.acquire()
        assertEquals(listOf(0L, 0L), waits)
    }

    /** 기다리면 시계가 흐르는 게이트 (낮은 순위는 짧게 쉬며 다시 본다) */
    private fun flowingGate(): Pair<RateGate, () -> Long> {
        var t = 0L
        val g = RateGate(Duration.ofSeconds(1), Duration.ofMillis(2500), clock = { t }, sleeper = { t += it })
        return g to { t }
    }

    @Test
    fun `I-72 낮은 순위는 보통 요청이 1_5초 조용해야 차례를 잡는다`() {
        val (g, clock) = flowingGate()
        g.acquire() // 보통 요청 t=0
        g.acquireLow(Duration.ofSeconds(60), Duration.ofMillis(1500))
        assertTrue(clock() >= 1_500_000_000L, "보통 요청 뒤 1.5초가 지나야 한다: ${clock()}")
    }

    @Test
    fun `I-72 낮은 순위가 잡은 뒤 온 보통 요청은 한 칸(1초)까지만 늦는다`() {
        var t = 10_000_000_000L
        val sleeps = mutableListOf<Long>()
        val g = RateGate(Duration.ofSeconds(1), Duration.ofMillis(2500), clock = { t }, sleeper = { sleeps += it; t += it })
        g.acquireLow(Duration.ofSeconds(60), Duration.ofMillis(1500)) // 아무도 없으면 바로
        assertEquals(emptyList(), sleeps)
        g.acquire()
        assertEquals(listOf(1_000_000_000L), sleeps)
    }

    @Test
    fun `I-72 낮은 순위는 상한 안에 조용해지지 않으면 거절된다`() {
        val (g, _) = flowingGate()
        g.acquire()
        assertFailsWith<TooBusyException> { g.acquireLow(Duration.ofSeconds(1), Duration.ofSeconds(5)) }
    }
}
