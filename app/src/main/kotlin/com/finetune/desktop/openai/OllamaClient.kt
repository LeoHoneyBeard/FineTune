package com.finetune.desktop.openai

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OllamaClient(
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    suspend fun fetchAvailableModels(): List<ModelOption> {
        val request = HttpRequest.newBuilder(URI("${baseUrl.removeSuffix("/")}/api/tags"))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Ollama request failed: HTTP ${response.statusCode()}\n${response.body()}"
            )
        }
        return parseAvailableModels(response.body()).map { modelName ->
            ModelOption(name = modelName, provider = ModelProvider.OLLAMA)
        }
    }

    internal fun parseAvailableModels(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        return root["models"]
            ?.jsonArray
            ?.mapNotNull { entry ->
                val node = entry.jsonObject
                node["name"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
                    ?: node["model"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
            }
            .orEmpty()
            .distinct()
            .sorted()
    }
}
