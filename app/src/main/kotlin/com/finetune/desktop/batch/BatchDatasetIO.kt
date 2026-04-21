package com.finetune.desktop.batch

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class BatchDatasetEntry(
    val lineNumber: Int,
    val systemPrompt: String,
    val userPrompt: String,
    val expectedAssistant: String?,
)

data class BatchResultExport(
    val lineNumber: Int,
    val selected: Boolean,
    val systemPrompt: String,
    val userPrompt: String,
    val expectedAssistant: String?,
    val response: String?,
    val error: String?,
)

object BatchDatasetIO {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun parse(file: File): List<BatchDatasetEntry> {
        val entries = mutableListOf<BatchDatasetEntry>()

        file.useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val lineNumber = index + 1
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    return@forEachIndexed
                }

                val root = runCatching { json.parseToJsonElement(line).jsonObject }
                    .getOrElse {
                        throw IllegalArgumentException(
                            "Line $lineNumber: invalid JSON (${it.message ?: "parse error"})."
                        )
                    }

                val messages = root["messages"] as? JsonArray
                    ?: throw IllegalArgumentException("Line $lineNumber: missing \"messages\" array.")

                val systemPrompt = messages.requiredRoleContent(lineNumber, "system")
                val userPrompt = messages.requiredRoleContent(lineNumber, "user")
                val assistantPrompt = messages.optionalRoleContent("assistant")

                entries += BatchDatasetEntry(
                    lineNumber = lineNumber,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    expectedAssistant = assistantPrompt,
                )
            }
        }

        return entries
    }

    fun export(file: File, results: List<BatchResultExport>) {
        val payload = buildJsonArray {
            results.forEach { result ->
                add(
                    buildJsonObject {
                        put("lineNumber", result.lineNumber)
                        put("selected", result.selected)
                        put("systemPrompt", result.systemPrompt)
                        put("userPrompt", result.userPrompt)
                        put("expectedAssistant", result.expectedAssistant)
                        put("response", result.response)
                        put("error", result.error)
                    }
                )
            }
        }

        file.writeText(json.encodeToString(JsonElement.serializer(), payload))
    }

    private fun JsonArray.requiredRoleContent(lineNumber: Int, role: String): String {
        return optionalRoleContent(role)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "Line $lineNumber: missing non-empty \"$role\" message content."
            )
    }

    private fun JsonArray.optionalRoleContent(role: String): String? {
        return firstNotNullOfOrNull { element ->
            val message = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val messageRole = (message["role"] as? JsonPrimitive)?.contentOrNull
            if (messageRole != role) {
                return@firstNotNullOfOrNull null
            }

            (message["content"] as? JsonPrimitive)?.contentOrNull?.trim()
        }
    }
}
