package dev.gyeol.worker.vibe

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.ResourceAccessException
import kotlin.test.assertEquals

/** I-84: 연결 오류만 짧게 재시도 */
class ConnectRetryTest {
    @Test
    fun `연결 오류는 두 번까지 기다렸다 다시 시도하고, 세 번째에 성공하면 그 값`() {
        var calls = 0
        val slept = mutableListOf<Long>()
        val v = ConnectRetry.run(sleep = { slept += it }) { calls++; if (calls < 3) throw ResourceAccessException("Connection refused") else "ok" }
        assertEquals("ok", v)
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 2000L), slept)
    }

    @Test
    fun `세 번 모두 연결 오류면 예외를 던져 SQS 재시도에 맡긴다`() {
        var calls = 0
        assertThrows<ResourceAccessException> { ConnectRetry.run(sleep = {}) { calls++; throw ResourceAccessException("x") } }
        assertEquals(3, calls)
    }

    @Test
    fun `연결 오류가 아니면 바로 던진다`() {
        var calls = 0
        assertThrows<IllegalStateException> { ConnectRetry.run(sleep = {}) { calls++; error("embed 응답이 비어 있습니다") } }
        assertEquals(1, calls)
    }
}
