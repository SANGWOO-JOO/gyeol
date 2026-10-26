package dev.gyeol.fetcher

import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TooBusyException(message: String) : RuntimeException(message)

/**
 * 외부 API별 속도 제한. 호출 사이 간격을 [minInterval] 이상으로 벌린다.
 * 요청이 몰리면 차례를 예약해 기다리게 하고, 기다릴 시간이 [maxWait]를 넘으면 바로 거절한다.
 * (기다리는 동안 잠금을 잡고 있지 않아, 예약만 순서대로 하고 대기는 각자 한다)
 */
class RateGate(
    private val minInterval: Duration,
    private val maxWait: Duration,
    private val clock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { nanos -> if (nanos > 0) Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt()) },
) {
    private val lock = ReentrantLock()
    private var nextSlot = Long.MIN_VALUE

    /** [limit]는 이번 호출만의 대기 상한 (백그라운드 식별은 즉시 검색보다 오래 기다려도 된다) */
    fun acquire(limit: Duration = maxWait) {
        val wait = lock.withLock {
            val now = clock()
            val slot = if (nextSlot == Long.MIN_VALUE) now else maxOf(now, nextSlot)
            val wait = slot - now
            if (wait > limit.toNanos()) throw TooBusyException("요청이 몰려 ${limit.toMillis()}ms 안에 차례가 오지 않습니다")
            nextSlot = slot + minInterval.toNanos()
            wait
        }
        sleeper(wait)
    }
}
