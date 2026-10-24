package dev.gyeol.fetcher

import java.time.Duration
import java.util.Collections

/** 최근 결과를 잠깐 기억하는 작은 캐시. 가장 오래 안 쓴 항목부터 버리고, 시간이 지나면 버린다. */
class TtlCache<K : Any, V : Any>(private val ttl: Duration, private val maxEntries: Int) {
    private data class Entry<V>(val value: V, val storedAt: Long)

    private val map: MutableMap<K, Entry<V>> = Collections.synchronizedMap(
        object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>) = size > maxEntries
        },
    )

    fun get(key: K): V? {
        val e = map[key] ?: return null
        if (System.nanoTime() - e.storedAt >= ttl.toNanos()) {
            map.remove(key)
            return null
        }
        return e.value
    }

    fun put(key: K, value: V) {
        map[key] = Entry(value, System.nanoTime())
    }
}
