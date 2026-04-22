package com.finetune.desktop.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatTurn(val role: String, val content: String)
enum class ConfidenceStatus {
    SURE,
    UNSURE,
    FAIL,
}

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    operator fun plus(other: TokenUsage): TokenUsage {
        return TokenUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
        )
    }
}

data class ChatCompletionResult(
    val answer: String,
    val usage: TokenUsage,
    val rawResponse: String,
)

data class ScoredChatResponse(
    val answer: String,
    val status: ConfidenceStatus,
    val rawAnswer: String,
    val rawResponse: String,
    val usage: TokenUsage,
)

data class RedundantChatResponse(
    val answer: String,
    val status: ConfidenceStatus,
    val responses: List<String>,
    val usage: TokenUsage,
    val inferenceCount: Int,
)

data class FineTuneJobInfo(
    val jobId: String,
    val status: String,
    val fileId: String? = null,
    val fineTunedModel: String? = null,
    val errorMessage: String? = null,
)

class OpenAiClient(
    private val baseUrl: String = "https://api.openai.com/v1",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    suspend fun sendChatCompletion(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        temperature: Double,
    ): String {
        val responseBuilder = StringBuilder()
        streamChatCompletion(apiKey, model, history, temperature).collect { delta ->
            responseBuilder.append(delta)
        }
        return responseBuilder.toString()
    }

    suspend fun sendChatCompletionDetailed(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        temperature: Double,
    ): ChatCompletionResult {
        val payload = buildChatCompletionPayload(
            model = model,
            history = history,
            temperature = temperature,
            stream = false,
        )
        val body = sendJsonRequest(
            apiKey = apiKey,
            endpoint = "/chat/completions",
            payload = payload,
        )
        return parseChatCompletionResponse(body)
    }

    suspend fun sendScoredChatCompletion(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        temperature: Double,
    ): ScoredChatResponse {
        val scoredHistory = buildScoredHistory(history)
        val completion = sendChatCompletionDetailed(apiKey, model, scoredHistory, temperature)
        return parseScoredChatResponse(
            rawAnswer = completion.answer,
            rawResponse = completion.rawResponse,
            usage = completion.usage,
        )
    }

    suspend fun sendRedundantChatCompletion(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        temperature: Double,
    ): RedundantChatResponse = coroutineScope {
        val results = List(3) {
            async { sendChatCompletionDetailed(apiKey, model, history, temperature) }
        }.awaitAll()
        parseRedundantChatResponse(results)
    }

    fun streamChatCompletion(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
        temperature: Double,
    ): Flow<String> = flow {
        val payload = buildChatCompletionPayload(model, history, temperature, stream = true)

        val request = baseRequest(apiKey, "/chat/completions")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        if (response.statusCode() !in 200..299) {
            val body = response.body().bufferedReader().use { it.readText() }
            throw IllegalStateException(
                "OpenAI request failed: HTTP ${response.statusCode()}\n$body"
            )
        }

        response.body().use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) {
                        continue
                    }

                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") {
                        continue
                    }

                    val deltaObject = extractJsonObject(data, "delta") ?: continue
                    val content = extractJsonString(deltaObject, "content") ?: continue
                    emit(content)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun uploadTrainingFile(
        apiKey: String,
        datasetFile: File,
    ): String {
        require(datasetFile.exists()) { "Dataset file does not exist." }
        require(datasetFile.extension.equals("jsonl", ignoreCase = true)) {
            "OpenAI fine-tuning requires a .jsonl dataset file."
        }

        val boundary = "Boundary-${UUID.randomUUID()}"
        val bodyParts = buildList {
            add("--$boundary\r\n".toByteArray())
            add("""Content-Disposition: form-data; name="purpose"""".toByteArray())
            add("\r\n\r\nfine-tune\r\n".toByteArray())

            add("--$boundary\r\n".toByteArray())
            add(
                """Content-Disposition: form-data; name="file"; filename="${datasetFile.name}"""".toByteArray()
            )
            add("\r\n".toByteArray())
            add("Content-Type: application/jsonl\r\n\r\n".toByteArray())
            add(datasetFile.readBytes())
            add("\r\n--$boundary--\r\n".toByteArray())
        }

        val request = baseRequest(apiKey, "/files")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArrays(bodyParts))
            .build()

        val body = execute(request)
        return extractJsonString(body, "id")
            ?: throw IllegalStateException("Could not extract file id from upload response.")
    }

    suspend fun createFineTuneJob(
        apiKey: String,
        model: String,
        trainingFileId: String,
    ): FineTuneJobInfo {
        val payload = """
            {
              "model": "${escapeJson(model)}",
              "training_file": "${escapeJson(trainingFileId)}"
            }
        """.trimIndent()

        val body = sendJsonRequest(
            apiKey = apiKey,
            endpoint = "/fine_tuning/jobs",
            payload = payload,
        )

        return parseJob(body).copy(fileId = trainingFileId)
    }

    suspend fun retrieveFineTuneJob(
        apiKey: String,
        jobId: String,
    ): FineTuneJobInfo {
        val request = baseRequest(apiKey, "/fine_tuning/jobs/$jobId")
            .GET()
            .build()
        return parseJob(execute(request))
    }

    private fun parseJob(body: String): FineTuneJobInfo {
        val errorObject = extractJsonObject(body, "error")
        return FineTuneJobInfo(
            jobId = extractJsonString(body, "id")
                ?: throw IllegalStateException("Could not extract fine-tune job id."),
            status = extractJsonString(body, "status")
                ?: throw IllegalStateException("Could not extract fine-tune status."),
            fineTunedModel = extractJsonNullableString(body, "fine_tuned_model"),
            errorMessage = errorObject?.let { extractJsonString(it, "message") },
        )
    }

    private suspend fun sendJsonRequest(
        apiKey: String,
        endpoint: String,
        payload: String,
    ): String {
        val request = baseRequest(apiKey, endpoint)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        return execute(request)
    }

    private fun baseRequest(apiKey: String, endpoint: String): HttpRequest.Builder {
        require(apiKey.isNotBlank()) { "OpenAI API token is required." }
        return HttpRequest.newBuilder(URI("$baseUrl$endpoint"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $apiKey")
    }

    private suspend fun execute(request: HttpRequest): String {
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "OpenAI request failed: HTTP ${response.statusCode()}\n${response.body()}"
            )
        }
        return response.body()
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

    internal fun parseScoredChatResponse(
        rawAnswer: String,
        rawResponse: String = rawAnswer,
        usage: TokenUsage = TokenUsage(0, 0),
    ): ScoredChatResponse {
        val normalized = rawAnswer
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val payload = json.parseToJsonElement(normalized).jsonObject
        val status = payload["status"]?.jsonPrimitive?.content?.trim()?.uppercase()
            ?: throw IllegalStateException("Could not extract confidence status from model response.")
        val answer = payload["answer"]?.jsonPrimitive?.content?.trim()
            ?: throw IllegalStateException("Could not extract scored answer from model response.")
        val confidenceStatus = runCatching { ConfidenceStatus.valueOf(status) }
            .getOrElse {
                throw IllegalStateException("Unsupported confidence status: $status")
            }
        return ScoredChatResponse(
            answer = answer.ifBlank { "-" },
            status = confidenceStatus,
            rawAnswer = rawAnswer,
            rawResponse = rawResponse,
            usage = usage,
        )
    }

    internal fun parseRedundantChatResponse(
        results: List<ChatCompletionResult>,
    ): RedundantChatResponse {
        require(results.size == 3) { "Redundancy requires exactly 3 responses." }
        val normalizedResponses = results.map { it.answer.trim().ifBlank { "-" } }
        val distinctResponses = normalizedResponses.distinct()
        val totalUsage = results.fold(TokenUsage(0, 0)) { acc, item -> acc + item.usage }
        return if (distinctResponses.size == 1) {
            RedundantChatResponse(
                answer = distinctResponses.single(),
                status = ConfidenceStatus.SURE,
                responses = normalizedResponses,
                usage = totalUsage,
                inferenceCount = results.size,
            )
        } else {
            RedundantChatResponse(
                answer = normalizedResponses.first(),
                status = ConfidenceStatus.FAIL,
                responses = normalizedResponses,
                usage = totalUsage,
                inferenceCount = results.size,
            )
        }
    }

    internal fun parseChatCompletionResponse(body: String): ChatCompletionResult {
        val root = json.parseToJsonElement(body).jsonObject
        val answer = root["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?: throw IllegalStateException("Could not extract assistant message content.")
        val usage = root["usage"]?.jsonObject
        return ChatCompletionResult(
            answer = answer,
            usage = TokenUsage(
                inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            ),
            rawResponse = body,
        )
    }

    private fun buildChatCompletionPayload(
        model: String,
        history: List<ChatTurn>,
        temperature: Double,
        stream: Boolean,
    ): String {
        return buildString {
            append("{")
            append("\"model\":\"${escapeJson(model)}\",")
            append("\"stream\":$stream,")
            append("\"temperature\":$temperature,")
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
