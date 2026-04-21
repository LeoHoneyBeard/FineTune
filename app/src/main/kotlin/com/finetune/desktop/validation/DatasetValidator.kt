package com.finetune.desktop.validation

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DatasetValidationResult(
    val totalLines: Int,
    val validLines: Int,
    val errors: List<String>,
) {
    val isValid: Boolean = errors.isEmpty()
}

object DatasetValidator {
    private val json = Json { ignoreUnknownKeys = true }
    private val requiredRoles = listOf("system", "user", "assistant")

    fun validate(file: File): DatasetValidationResult {
        val errors = mutableListOf<String>()
        var totalLines = 0
        var validLines = 0

        file.useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val lineNumber = index + 1
                totalLines++
                val line = rawLine.trim()

                if (line.isEmpty()) {
                    errors += "Line $lineNumber: line is empty."
                    return@forEachIndexed
                }

                val root = runCatching { json.parseToJsonElement(line) }
                    .getOrElse {
                        errors += "Line $lineNumber: invalid JSON (${it.message ?: "parse error"})."
                        return@forEachIndexed
                    }

                val lineErrors = validateEntry(root).map { message -> "Line $lineNumber: $message" }
                if (lineErrors.isEmpty()) {
                    validLines++
                } else {
                    errors += lineErrors
                }
            }
        }

        return DatasetValidationResult(
            totalLines = totalLines,
            validLines = validLines,
            errors = errors,
        )
    }

    private fun validateEntry(root: JsonElement): List<String> {
        val errors = mutableListOf<String>()
        val rootObject = root as? JsonObject
        if (rootObject == null) {
            return listOf("root value must be a JSON object.")
        }

        val messagesElement = rootObject["messages"]
        val messages = messagesElement as? JsonArray
        if (messages == null) {
            return listOf("missing \"messages\" array.")
        }

        if (messages.size != requiredRoles.size) {
            errors += "\"messages\" must contain exactly 3 items."
        }

        requiredRoles.forEachIndexed { index, expectedRole ->
            val message = messages.getOrNull(index) as? JsonObject
            if (message == null) {
                errors += "message ${index + 1} must be a JSON object."
                return@forEachIndexed
            }

            val actualRole = message.requiredString("role")
            when {
                actualRole == null -> errors += "message ${index + 1} is missing non-empty \"role\"."
                actualRole != expectedRole -> {
                    errors += "message ${index + 1} must have role \"$expectedRole\", found \"$actualRole\"."
                }
            }

            val content = message.requiredString("content")
            if (content == null) {
                errors += "message ${index + 1} must have non-empty \"content\"."
            }
        }

        return errors
    }

    private fun JsonObject.requiredString(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
