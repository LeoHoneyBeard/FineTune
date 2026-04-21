package com.finetune.desktop.batch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class BatchDatasetIOTest {
    @Test
    fun `parse extracts system and user prompts from dataset`() {
        val file = createTempFile(
            """
            {"messages":[{"role":"system","content":"rules"},{"role":"user","content":"hello"},{"role":"assistant","content":"hi"}]}
            {"messages":[{"role":"system","content":"policy"},{"role":"user","content":"question"}]}
            """.trimIndent()
        )

        val result = BatchDatasetIO.parse(file)

        assertEquals(2, result.size)
        assertEquals("rules", result[0].systemPrompt)
        assertEquals("hello", result[0].userPrompt)
        assertEquals("hi", result[0].expectedAssistant)
        assertEquals("policy", result[1].systemPrompt)
        assertEquals("question", result[1].userPrompt)
        assertEquals(null, result[1].expectedAssistant)
    }

    @Test
    fun `export writes response payload as json`() {
        val file = kotlin.io.path.createTempFile(suffix = ".json").toFile().apply {
            deleteOnExit()
        }

        BatchDatasetIO.export(
            file = file,
            results = listOf(
                BatchResultExport(
                    lineNumber = 3,
                    selected = true,
                    systemPrompt = "rules",
                    userPrompt = "prompt",
                    expectedAssistant = "expected",
                    response = "actual",
                    error = null,
                )
            ),
        )

        val content = file.readText()
        assertContains(content, "\"lineNumber\": 3")
        assertContains(content, "\"userPrompt\": \"prompt\"")
        assertContains(content, "\"response\": \"actual\"")
    }

    private fun createTempFile(content: String): File {
        return kotlin.io.path.createTempFile(suffix = ".jsonl").toFile().apply {
            writeText(content)
            deleteOnExit()
        }
    }
}
