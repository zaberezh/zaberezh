package by.zaberezh.forma.core

import by.zaberezh.forma.core.ai.Claude
import com.anthropic.models.messages.MessageCreateParams
import kotlin.test.Test
import kotlin.test.assertTrue

class ClaudeRequestTest {
    @Test fun reportToolSchemaIsStrictAndComplete() {
        val body = MessageCreateParams.builder().model("claude-opus-5-5").maxTokens(100L)
            .addTool(Claude.REPORT_TOOL).addUserMessage("x").build()._body()
        val mapper = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as com.fasterxml.jackson.databind.ObjectMapper
        val json = mapper.writeValueAsString(body)
        assertTrue("\"strict\":true" in json, json)
        assertTrue("\"additionalProperties\":false" in json, json)
        assertTrue("\"required\":[\"items\",\"note\"]" in json, json)
        println(json)
    }
}
