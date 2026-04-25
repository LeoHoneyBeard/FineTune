package com.finetune.desktop.openai

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaClientTest {

    private val client = OllamaClient("http://localhost:11434")

    @Test
    fun `parse available models prefers names and removes duplicates`() {
        val body = """
            {
              "models": [
                { "name": "llama3.1:8b" },
                { "model": "mistral:7b" },
                { "name": "llama3.1:8b" },
                { "name": " " }
              ]
            }
        """.trimIndent()

        val parsed = client.parseAvailableModels(body)

        assertEquals(listOf("llama3.1:8b", "mistral:7b"), parsed)
    }
}
