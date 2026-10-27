package dev.gyeol.worker.describe

import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.SpecificToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.Tool
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class ToolResult(val input: JsonNode, val model: String, val inputTokens: Int, val outputTokens: Int, val latencyMs: Int)

/**
 * Bedrock Converse로 도구 하나를 반드시 호출하게 해서 그 입력(JSON)을 받는다.
 * Bedrock Claude는 structured outputs가 없어 이 방식으로 형식을 받고, 내용 검증은 부르는 쪽이 한다.
 */
class BedrockTool(region: String, private val json: JsonMapper) {
    private val client = BedrockRuntimeClient.builder().region(Region.of(region)).build()

    fun call(model: String, system: String, user: String, tool: String, schema: Map<String, Any?>, maxTokens: Int): ToolResult {
        val res = client.converse(
            ConverseRequest.builder()
                .modelId(model)
                .system(SystemContentBlock.fromText(system))
                .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText(user)).build())
                .toolConfig(
                    ToolConfiguration.builder()
                        .tools(Tool.fromToolSpec(ToolSpecification.builder().name(tool).inputSchema(ToolInputSchema.fromJson(doc(schema))).build()))
                        .toolChoice(ToolChoice.fromTool(SpecificToolChoice.builder().name(tool).build()))
                        .build(),
                )
                .inferenceConfig { it.maxTokens(maxTokens).temperature(0f) }
                .build(),
        )
        val input = res.output().message().content().firstNotNullOfOrNull { it.toolUse()?.input() }
            ?: error("도구 호출 응답이 없습니다 (stop=${res.stopReasonAsString()})")
        return ToolResult(json.valueToTree(input.unwrap()), model, res.usage().inputTokens(), res.usage().outputTokens(), res.metrics().latencyMs().toInt())
    }

    /** JSON 스키마(Map·List·문자열·숫자)를 SDK Document로 */
    private fun doc(v: Any?): Document = when (v) {
        null -> Document.fromNull()
        is String -> Document.fromString(v)
        is Number -> Document.fromNumber(v.toString())
        is Boolean -> Document.fromBoolean(v)
        is Map<*, *> -> Document.fromMap(v.entries.associate { (k, x) -> k.toString() to doc(x) })
        is List<*> -> Document.fromList(v.map(::doc))
        else -> error("스키마에 쓸 수 없는 값: $v")
    }
}
