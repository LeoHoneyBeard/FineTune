package com.finetune.desktop.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiClientTest {

    private val client = OpenAiClient()

    @Test
    fun `parse scored chat response extracts status and answer`() {
        val response = """
            {
              "status": "UNSURE",
              "answer": "Need more context."
            }
        """.trimIndent()

        val parsed = client.parseScoredChatResponse(response)

        assertEquals(ConfidenceStatus.UNSURE, parsed.status)
        assertEquals("Need more context.", parsed.answer)
    }

    @Test
    fun `parse scored chat response supports fenced json`() {
        val response = """
            ```json
            {"status":"SURE","answer":"All good."}
            ```
        """.trimIndent()

        val parsed = client.parseScoredChatResponse(response)

        assertEquals(ConfidenceStatus.SURE, parsed.status)
        assertEquals("All good.", parsed.answer)
    }

    @Test
    fun `parse scored chat response rejects unknown status`() {
        val response = """{"status":"MAYBE","answer":"text"}"""

        assertFailsWith<IllegalStateException> {
            client.parseScoredChatResponse(response)
        }
    }

    @Test
    fun `parse redundant chat response returns sure when all responses match`() {
        val parsed = client.parseRedundantChatResponse(
            listOf("Same answer.", "Same answer.", "Same answer.")
        )

        assertEquals(ConfidenceStatus.SURE, parsed.status)
        assertEquals("Same answer.", parsed.answer)
    }

    @Test
    fun `parse redundant chat response returns fail when responses differ`() {
        val parsed = client.parseRedundantChatResponse(
            listOf("First answer.", "Second answer.", "First answer.")
        )

        assertEquals(ConfidenceStatus.FAIL, parsed.status)
        assertEquals("First answer.", parsed.answer)
    }
}
