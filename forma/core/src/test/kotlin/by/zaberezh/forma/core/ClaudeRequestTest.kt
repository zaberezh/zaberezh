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

class AiPlanPromptSize {
    @Test fun promptIsSmall() {
        val s = by.zaberezh.forma.core.store.MemoryStore()
        val p = by.zaberezh.forma.core.gym.parseProgram(javaClass.getResource("/test_program.json")!!.readText())
        by.zaberezh.forma.core.gym.PROGRAM.set(s, p)
        val day = java.time.LocalDate.of(2026, 9, 28)
        val prompt = by.zaberezh.forma.core.gym.aiPlanPrompt(Ctx(s, day), day, null)
        val method = javaClass.getResource("/method.md")!!.readText()
        // грубо: ~1 токен на 3 символа русского текста
        println("AI_PLAN prompt=${prompt.length} chars, method=${method.length} chars, ≈${(prompt.length + method.length) / 3} input tokens")
        assertTrue(prompt.length + method.length < 60_000)
    }
}
