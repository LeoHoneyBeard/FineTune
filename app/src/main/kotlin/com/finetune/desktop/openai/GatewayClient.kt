package com.finetune.desktop.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GatewayClient(
    private val baseUrl: String = "http://127.0.0.1:8000",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val responseParser = OpenAiClient()

    suspend fun sendChatCompletionDetailed(
        model: String,
        history: List<ChatTurn>,
        mode: String,
    ): ChatCompletionResult {
        val payload = buildGatewayPayload(model, history, mode)
        val request = HttpRequest.newBuilder(URI("${baseUrl.removeSuffix("/")}/chat"))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = withContext(Dispatchers.IO) {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        }
        val body = response.body()
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(formatGatewayError(response.statusCode(), body))
        }

        val root = json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status != "ok") {
            val warning = root["warning"]?.jsonPrimitive?.contentOrNull ?: "Gateway blocked the response."
            val action = root["action"]?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(
                if (action.isNullOrBlank()) warning else "$warning ($action)"
            )
        }

        val answer = root["response"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Gateway response did not include model output.")
        return ChatCompletionResult(
            answer = answer.trim(),
            usage = TokenUsage(inputTokens = 0, outputTokens = 0),
            rawResponse = body,
        )
    }

    suspend fun sendScoredChatCompletion(
        model: String,
        history: List<ChatTurn>,
        mode: String,
    ): ScoredChatResponse {
        val completion = sendChatCompletionDetailed(
            model = model,
            history = buildScoredHistory(history),
            mode = mode,
        )
        return responseParser.parseScoredChatResponse(
            rawAnswer = completion.answer,
            rawResponse = completion.rawResponse,
            usage = completion.usage,
        )
    }

    suspend fun sendRedundantChatCompletion(
        model: String,
        history: List<ChatTurn>,
        mode: String,
    ): RedundantChatResponse = coroutineScope {
        val results = List(3) {
            async { sendChatCompletionDetailed(model, history, mode) }
        }.awaitAll()
        responseParser.parseRedundantChatResponse(results)
    }

    internal fun buildGatewayPayload(
        model: String,
        history: List<ChatTurn>,
        mode: String,
    ): String {
        return buildString {
            append("{")
            append("\"model\":\"${escapeJson(model)}\",")
            append("\"mode\":\"${escapeJson(mode)}\",")
            append("\"messages\":[")
            append(
                history.joinToString(",") {
                    """{"role":"${escapeJson(it.role)}","content":"${escapeJson(it.content)}"}"""
                }
            )
            append("]")
            append("}")
        }
    }

    private fun formatGatewayError(statusCode: Int, body: String): String {
        val parsed = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val warning = root["warning"]?.jsonPrimitive?.contentOrNull
            val action = root["action"]?.jsonPrimitive?.contentOrNull
            when {
                !warning.isNullOrBlank() && !action.isNullOrBlank() -> "$warning ($action)"
                !warning.isNullOrBlank() -> warning
                else -> null
            }
        }.getOrNull()
        return parsed ?: "Gateway request failed: HTTP $statusCode\n$body"
    }

    private fun buildScoredHistory(history: List<ChatTurn>): List<ChatTurn> {
        val firstNonSystemIndex = history.indexOfFirst { it.role != "system" }
            .let { index -> if (index >= 0) index else history.size }
        return buildList(history.size + 1) {
            addAll(history.take(firstNonSystemIndex))
            add(ChatTurn(role = "system", content = confidenceScoringInstruction))
            addAll(history.drop(firstNonSystemIndex))
        }
    }

    private companion object {
        private val confidenceScoringInstruction = """
            You must evaluate your confidence in the final answer.
            Return only valid JSON with exactly two string fields:
            {"status":"SURE|UNSURE|FAIL","answer":"<final answer>"}
            Use:
            - SURE when the answer is reliable and directly supported.
            - UNSURE when the answer may be incomplete, ambiguous, or needs verification.
            - FAIL when you cannot answer the request correctly.
            Do not include markdown fences or any text outside the JSON object.
        """.trimIndent()
    }
}
