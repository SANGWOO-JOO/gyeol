package dev.gyeol.worker.llm

import dev.gyeol.worker.WorkerProperties
import dev.gyeol.worker.describe.BedrockDescriber
import dev.gyeol.worker.describe.FakeDescriber
import dev.gyeol.worker.describe.TrackDescriber
import tools.jackson.databind.json.JsonMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Configuration
class LlmConfig {
    /** gyeol.worker.llm.mode: bedrock이면 실제 호출, 그 밖에는 가짜 (기본) */
    @Bean
    fun titleCleaner(props: WorkerProperties): TitleCleaner =
        if (props.llm.mode == "bedrock") BedrockTitleCleaner(props.llm) else FakeTitleCleaner()

    /** L3-2 곡 설명. 같은 모드 전환을 쓴다 */
    @Bean
    fun trackDescriber(props: WorkerProperties, json: JsonMapper): TrackDescriber =
        if (props.llm.mode == "bedrock") BedrockDescriber(props.llm, json) else FakeDescriber()

    /** L3-4 추천 재정렬 */
    @Bean
    fun reranker(props: WorkerProperties, json: JsonMapper): dev.gyeol.worker.recommend.Reranker =
        if (props.llm.mode == "bedrock") dev.gyeol.worker.recommend.BedrockReranker(props, json) else dev.gyeol.worker.recommend.FakeReranker()
}

data class CleanRequest(val model: String?, val items: List<RawTitle>)
data class CleanResponse(val raw: CleanResult, val accepted: List<CleanedTitle?>)

/**
 * 정확도 측정용 (tools/llm-eval). 모델을 바꿔 가며 같은 제목 묶음을 정리하고 검증 결과를 함께 돌려준다.
 * 로컬에서만 켠다 (gyeol.worker.llm.debug-endpoint=true). 호출마다 Bedrock 비용이 든다.
 */
@RestController
@ConditionalOnProperty("gyeol.worker.llm.debug-endpoint", havingValue = "true")
class LlmDebugController(private val cleaner: TitleCleaner) {
    private val fake = FakeTitleCleaner()

    /** model이 "fake"면 규칙만 쓰는 가짜 정리기 (비교 기준) */
    @PostMapping("/internal/llm/clean")
    fun clean(@RequestBody req: CleanRequest): CleanResponse {
        val r = if (req.model == "fake") fake.clean(req.items) else cleaner.clean(req.items, req.model)
        val raws = req.items.associateBy { it.index }
        return CleanResponse(r, r.items.map { c -> raws[c.index]?.let { CleanedValidator.accept(it, c) } })
    }
}
