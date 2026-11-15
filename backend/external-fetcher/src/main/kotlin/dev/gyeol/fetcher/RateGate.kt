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
    private var lastNormal = Long.MIN_VALUE

    /** [limit]는 이번 호출만의 대기 상한 (백그라운드 식별은 즉시 검색보다 오래 기다려도 된다) */
    fun acquire(limit: Duration = maxWait) {
        val wait = lock.withLock {
            val now = clock()
            val slot = if (nextSlot == Long.MIN_VALUE) now else maxOf(now, nextSlot)
            val wait = slot - now
            if (wait > limit.toNanos()) throw TooBusyException("요청이 몰려 ${limit.toMillis()}ms 안에 차례가 오지 않습니다")
            nextSlot = slot + minInterval.toNanos()
            lastNormal = now
            wait
        }
        sleeper(wait)
    }

    /**
     * I-72 B-3: 낮은 순위(검색 때 미리 준비). 줄이 비어 있고 보통 순위 요청(담기·업로드 식별)이 [quiet] 동안 없을 때만 차례를 잡는다.
     * 보통 순위 식별은 호출 사이 간격이 1초 남짓이라, 그 사이에 끼어들지 않게 조용한 시간을 둔다.
     * 끼어든 뒤 바로 보통 요청이 오면 그 요청은 최대 한 칸([minInterval])만 늦어진다. [limit] 안에 차례가 없으면 거절
     */
    fun acquireLow(limit: Duration, quiet: Duration, poll: Duration = Duration.ofMillis(100)) {
        val started = clock()
        while (true) {
            val reserved = lock.withLock {
                val now = clock()
                val idle = nextSlot == Long.MIN_VALUE || nextSlot <= now
                val calm = lastNormal == Long.MIN_VALUE || now - lastNormal >= quiet.toNanos()
                if (idle && calm) { nextSlot = now + minInterval.toNanos(); true } else false
            }
            if (reserved) return
            if (clock() - started > limit.toNanos()) throw TooBusyException("낮은 순위 요청이 ${limit.toMillis()}ms 안에 차례를 얻지 못했습니다")
            sleeper(poll.toNanos())
        }
    }
}
