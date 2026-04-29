package com.finetune.desktop.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.net.InetSocketAddress

class GatewayClientTest {

    @Test
    fun `build gateway payload preserves chat roles`() {
        val client = GatewayClient()

        val payload = client.buildGatewayPayload(
            model = "gpt-4o-mini",
            mode = "mask",
            history = listOf(
                ChatTurn("system", "Be concise."),
                ChatTurn("user", "Say \"hi\"."),
            ),
        )

        assertTrue(payload.contains(""""model":"gpt-4o-mini""""))
        assertTrue(payload.contains(""""mode":"mask""""))
        assertTrue(payload.contains(""""role":"system","content":"Be concise.""""))
        assertTrue(payload.contains(""""role":"user","content":"Say \"hi\".""""))
    }

    @Test
    fun `build gateway payload supports cyrillic message`() {
        val client = GatewayClient()

        val payload = client.buildGatewayPayload(
            model = "gpt-4o-mini",
            mode = "mask",
            history = listOf(ChatTurn("user", "Привет, как дела")),
        )

        assertTrue(payload.contains("Привет, как дела"))
    }

    @Test
    fun `send chat completion returns gateway response`() = runBlocking {
        withGatewayResponse("""{"status":"ok","response":"Hello","input_findings":[],"output_findings":[],"action":"allowed"}""") { client, requestBody ->
            val response = client.sendChatCompletionDetailed(
                model = "gpt-4o-mini",
                mode = "mask",
                history = listOf(ChatTurn("user", "Hi")),
            )

            assertEquals("Hello", response.answer)
            assertTrue(requestBody().contains(""""messages""""))
        }
    }

    @Test
    fun `send chat completion rejects blocked gateway response`() = runBlocking {
        withGatewayResponse("""{"status":"blocked","warning":"Model output failed safety checks","action":"output_blocked"}""") { client, _ ->
            val error = assertFailsWith<IllegalStateException> {
                client.sendChatCompletionDetailed(
                    model = "gpt-4o-mini",
                    mode = "mask",
                    history = listOf(ChatTurn("user", "Hi")),
                )
            }

            assertTrue(error.message.orEmpty().contains("output_blocked"))
        }
    }

    @Test
    fun `send chat completion formats gateway json error`() = runBlocking {
        withGatewayResponse(
            responseBody = """{"status":"error","warning":"OpenAI request failed","action":"llm_error"}""",
            statusCode = 502,
        ) { client, _ ->
            val error = assertFailsWith<IllegalStateException> {
                client.sendChatCompletionDetailed(
                    model = "gpt-4o-mini",
                    mode = "mask",
                    history = listOf(ChatTurn("user", "Hi")),
                )
            }

            assertEquals("OpenAI request failed (llm_error)", error.message)
        }
    }

    private suspend fun withGatewayResponse(
        responseBody: String,
        statusCode: Int = 200,
        block: suspend (GatewayClient, () -> String) -> Unit,
    ) {
        var capturedRequestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            capturedRequestBody = exchange.readBody()
            exchange.sendJson(responseBody, statusCode)
        }
        server.start()
        try {
            val client = GatewayClient("http://127.0.0.1:${server.address.port}")
            block(client) { capturedRequestBody }
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.readBody(): String {
        return requestBody.bufferedReader().use { it.readText() }
    }

    private fun HttpExchange.sendJson(body: String, statusCode: Int) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
