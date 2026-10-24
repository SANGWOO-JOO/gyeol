package dev.gyeol.fetcher

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
