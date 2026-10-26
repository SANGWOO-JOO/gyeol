package dev.gyeol.core.llm

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class LlmUsage(val model: String, val items: Int, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

/** LLM 호출 기록 (비용 계산용, 사용자와 연결하지 않음) */
@Repository
class LlmCallRepository(private val jdbc: JdbcClient) {
    fun record(purpose: String, u: LlmUsage) {
        jdbc.sql("INSERT INTO llm_call (purpose, model, items, input_tokens, output_tokens, latency_ms) VALUES (:p, :m, :n, :i, :o, :l)")
            .param("p", purpose).param("m", u.model).param("n", u.items).param("i", u.inputTokens).param("o", u.outputTokens).param("l", u.latencyMs)
            .update()
    }
}
