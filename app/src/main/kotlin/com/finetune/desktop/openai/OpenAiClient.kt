package com.finetune.desktop.openai

import kotlinx.coroutines.Dispatchers
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

data class ChatTurn(val role: String, val content: String)
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
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    suspend fun sendChatCompletion(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
    ): String {
        val responseBuilder = StringBuilder()
        streamChatCompletion(apiKey, model, history).collect { delta ->
            responseBuilder.append(delta)
        }
        return responseBuilder.toString()
    }

    fun streamChatCompletion(
        apiKey: String,
        model: String,
        history: List<ChatTurn>,
    ): Flow<String> = flow {
        val payload = buildString {
            append("{")
            append("\"model\":\"${escapeJson(model)}\",")
            append("\"stream\":true,")
            append("\"messages\":[")
            append(
                history.joinToString(",") {
                    """{"role":"${escapeJson(it.role)}","content":"${escapeJson(it.content)}"}"""
                }
            )
            append("]")
            append("}")
        }

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
}
