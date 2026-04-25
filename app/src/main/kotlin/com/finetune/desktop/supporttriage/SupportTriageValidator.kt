package com.finetune.desktop.supporttriage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ValidationOutcome<out T>(
    val value: T?,
    val normalizedJson: String?,
    val error: String?,
) {
    val isValid: Boolean = value != null && error == null
}

object SupportTriageValidator {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun parseFinal(raw: String): ValidationOutcome<SupportTriageFinalResult> {
        return parseObject(raw).mapJson { payload ->
            payload.requireExactKeys(
                "intent",
                "sentiment",
                "urgency",
                "needs_human",
                "confidence",
                "status",
            )
            SupportTriageFinalResult(
                intent = payload.requiredIntent("intent"),
                sentiment = payload.requiredEnum("sentiment", SupportSentiment::valueOf),
                urgency = payload.requiredEnum("urgency", SupportUrgency::valueOf),
                needsHuman = payload.requiredBoolean("needs_human"),
                confidence = payload.requiredConfidence("confidence"),
                status = payload.requiredEnum("status", SupportStatus::valueOf),
            )
        }
    }

    fun parseStage1(raw: String): ValidationOutcome<SupportTriageStage1Result> {
        if (!raw.trimStart().startsWith("{")) {
            return parseCompactStage1(raw)
        }
        return parseObject(raw).mapJson { payload ->
            payload.requireExactKeys(
                "has_payment_issue",
                "has_technical_issue",
                "has_info_request",
                "has_complaint",
                "mentions_access_problem",
                "mentions_refund",
                "emotion",
                "noise_level",
                "normalized_summary",
            )
            SupportTriageStage1Result(
                hasPaymentIssue = payload.requiredBoolean("has_payment_issue"),
                hasTechnicalIssue = payload.requiredBoolean("has_technical_issue"),
                hasInfoRequest = payload.requiredBoolean("has_info_request"),
                hasComplaint = payload.requiredBoolean("has_complaint"),
                mentionsAccessProblem = payload.requiredBoolean("mentions_access_problem"),
                mentionsRefund = payload.requiredBoolean("mentions_refund"),
                emotion = payload.requiredEnum("emotion", SupportSentiment::valueOf),
                noiseLevel = payload.requiredEnum("noise_level", NoiseLevel::valueOf),
                normalizedSummary = payload.requiredString("normalized_summary"),
            )
        }
    }

    fun parseStage2(raw: String): ValidationOutcome<SupportTriageStage2Result> {
        if (!raw.trimStart().startsWith("{")) {
            return parseCompactStage2(raw)
        }
        return parseObject(raw).mapJson { payload ->
            payload.requireExactKeys(
                "intent",
                "urgency",
                "needs_human",
                "decision_confidence",
                "decision_status",
            )
            SupportTriageStage2Result(
                intent = payload.requiredEnum("intent", SupportIntent::valueOf),
                urgency = payload.requiredEnum("urgency", SupportUrgency::valueOf),
                needsHuman = payload.requiredBoolean("needs_human"),
                decisionConfidence = payload.requiredConfidence("decision_confidence"),
                decisionStatus = payload.requiredEnum("decision_status", SupportStatus::valueOf),
            )
        }
    }

    fun parseCompactStage1(raw: String): ValidationOutcome<SupportTriageStage1Result> {
        return parseCompactObject(raw).mapCompact { fields ->
            fields.requireExactKeys("pay", "tech", "info", "comp", "access", "refund", "emo", "noise")
            SupportTriageStage1Result(
                hasPaymentIssue = fields.requiredBit("pay"),
                hasTechnicalIssue = fields.requiredBit("tech"),
                hasInfoRequest = fields.requiredBit("info"),
                hasComplaint = fields.requiredBit("comp"),
                mentionsAccessProblem = fields.requiredBit("access"),
                mentionsRefund = fields.requiredBit("refund"),
                emotion = fields.requiredCompactSentiment("emo"),
                noiseLevel = fields.requiredCompactNoise("noise"),
                normalizedSummary = "",
            )
        }
    }

    fun parseCompactStage2(raw: String): ValidationOutcome<SupportTriageStage2Result> {
        return parseCompactObject(raw).mapCompact { fields ->
            fields.requireExactKeys("intent", "urg", "human", "conf", "status")
            SupportTriageStage2Result(
                intent = fields.requiredCompactIntent("intent"),
                urgency = fields.requiredCompactUrgency("urg"),
                needsHuman = fields.requiredBit("human"),
                decisionConfidence = fields.requiredConfidence("conf"),
                decisionStatus = fields.requiredEnum("status", SupportStatus::valueOf),
            )
        }
    }

    private fun parseObject(raw: String): ValidationOutcome<JsonObject> {
        return runCatching {
            val normalized = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val payload = json.parseToJsonElement(normalized).jsonObject
            ValidationOutcome(payload, json.encodeToString(JsonObject.serializer(), payload), null)
        }.getOrElse { error ->
            ValidationOutcome(null, null, error.message ?: "Could not parse JSON.")
        }
    }

    private fun parseCompactObject(raw: String): ValidationOutcome<Map<String, String>> {
        return runCatching {
            val normalized = raw.trim()
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: throw IllegalArgumentException("Compact response is empty.")
            val fields = normalized
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .associate { token ->
                    val separator = token.indexOf(':')
                    if (separator <= 0 || separator == token.lastIndex) {
                        throw IllegalArgumentException("Invalid compact token '$token'.")
                    }
                    token.substring(0, separator).lowercase() to token.substring(separator + 1).trim()
                }
            ValidationOutcome(fields, fields.toCompactString(), null)
        }.getOrElse { error ->
            ValidationOutcome(null, null, error.message ?: "Could not parse compact response.")
        }
    }

    private fun <T> ValidationOutcome<JsonObject>.mapJson(transform: (JsonObject) -> T): ValidationOutcome<T> {
        val payload = value ?: return ValidationOutcome(null, normalizedJson, error)
        return runCatching {
            ValidationOutcome(transform(payload), normalizedJson, null)
        }.getOrElse { validationError ->
            ValidationOutcome(null, normalizedJson, validationError.message ?: "Invalid JSON schema.")
        }
    }

    private fun <T> ValidationOutcome<Map<String, String>>.mapCompact(transform: (Map<String, String>) -> T): ValidationOutcome<T> {
        val payload = value ?: return ValidationOutcome(null, normalizedJson, error)
        return runCatching {
            ValidationOutcome(transform(payload), normalizedJson, null)
        }.getOrElse { validationError ->
            ValidationOutcome(null, normalizedJson, validationError.message ?: "Invalid compact schema.")
        }
    }

    private fun JsonObject.requiredString(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field '$key' must be a non-empty string.")
    }

    private fun JsonObject.requireExactKeys(vararg expectedKeys: String) {
        val expected = expectedKeys.toSet()
        val actual = keys
        val missing = expected - actual
        val extra = actual - expected
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            throw IllegalArgumentException(
                "JSON schema mismatch. Missing: ${missing.joinToString().ifBlank { "-" }}. Extra: ${extra.joinToString().ifBlank { "-" }}."
            )
        }
    }

    private fun Map<String, String>.requireExactKeys(vararg expectedKeys: String) {
        val expected = expectedKeys.toSet()
        val actual = keys
        val missing = expected - actual
        val extra = actual - expected
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            throw IllegalArgumentException(
                "Compact schema mismatch. Missing: ${missing.joinToString().ifBlank { "-" }}. Extra: ${extra.joinToString().ifBlank { "-" }}."
            )
        }
    }

    private fun Map<String, String>.requiredBit(key: String): Boolean {
        return when (requiredValue(key)) {
            "0" -> false
            "1" -> true
            else -> throw IllegalArgumentException("Field '$key' must be 0 or 1.")
        }
    }

    private fun Map<String, String>.requiredConfidence(key: String): Double {
        val value = requiredValue(key).toDoubleOrNull()
            ?: throw IllegalArgumentException("Field '$key' must be a number.")
        if (value !in 0.0..1.0) {
            throw IllegalArgumentException("Field '$key' must be in range 0.0..1.0.")
        }
        return value
    }

    private fun Map<String, String>.requiredCompactSentiment(key: String): SupportSentiment {
        return when (requiredValue(key).uppercase()) {
            "POS" -> SupportSentiment.POSITIVE
            "NEU" -> SupportSentiment.NEUTRAL
            "NEG" -> SupportSentiment.NEGATIVE
            else -> throw IllegalArgumentException("Unsupported compact sentiment for '$key'.")
        }
    }

    private fun Map<String, String>.requiredCompactNoise(key: String): NoiseLevel {
        return when (requiredValue(key).uppercase()) {
            "LOW" -> NoiseLevel.LOW
            "MED" -> NoiseLevel.MEDIUM
            "HIGH" -> NoiseLevel.HIGH
            else -> throw IllegalArgumentException("Unsupported compact noise for '$key'.")
        }
    }

    private fun Map<String, String>.requiredCompactUrgency(key: String): SupportUrgency {
        return when (requiredValue(key).uppercase()) {
            "LOW" -> SupportUrgency.LOW
            "MED" -> SupportUrgency.MEDIUM
            "HIGH" -> SupportUrgency.HIGH
            else -> throw IllegalArgumentException("Unsupported compact urgency for '$key'.")
        }
    }

    private fun Map<String, String>.requiredCompactIntent(key: String): SupportIntent {
        val variants = requiredValue(key)
            .uppercase()
            .split('|', '/', ',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { value ->
                runCatching { SupportIntent.valueOf(value) }
                    .getOrElse { throw IllegalArgumentException("Unsupported value '$value' for '$key'.") }
            }
            .toSet()
        if (variants.isEmpty()) {
            throw IllegalArgumentException("Field '$key' must be present.")
        }
        return when {
            SupportIntent.REFUND in variants -> SupportIntent.REFUND
            SupportIntent.TECH in variants -> SupportIntent.TECH
            SupportIntent.INFO in variants -> SupportIntent.INFO
            else -> SupportIntent.OTHER
        }
    }

    private fun <T : Enum<T>> Map<String, String>.requiredEnum(
        key: String,
        parser: (String) -> T,
    ): T {
        val value = requiredValue(key).uppercase()
        return runCatching { parser(value) }
            .getOrElse { throw IllegalArgumentException("Unsupported value '$value' for '$key'.") }
    }

    private fun Map<String, String>.requiredValue(key: String): String {
        return this[key]?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field '$key' must be present.")
    }

    private fun Map<String, String>.toCompactString(): String {
        return entries.joinToString(separator = " ") { (key, value) -> "$key:$value" }
    }

    private fun JsonObject.requiredBoolean(key: String): Boolean {
        val primitive = this[key]?.jsonPrimitive
            ?: throw IllegalArgumentException("Field '$key' must be boolean.")
        return primitive.contentOrNull?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        } ?: throw IllegalArgumentException("Field '$key' must be boolean.")
    }

    private fun JsonObject.requiredConfidence(key: String): Double {
        val value = this[key]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("Field '$key' must be a number.")
        if (value !in 0.0..1.0) {
            throw IllegalArgumentException("Field '$key' must be in range 0.0..1.0.")
        }
        return value
    }

    private fun <T : Enum<T>> JsonObject.requiredEnum(
        key: String,
        parser: (String) -> T,
    ): T {
        val value = requiredString(key).uppercase()
        return runCatching { parser(value) }
            .getOrElse { throw IllegalArgumentException("Unsupported value '$value' for '$key'.") }
    }

    private fun JsonObject.requiredIntent(key: String): SupportIntent {
        val variants = requiredString(key)
            .uppercase()
            .split('|', '/', ',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { value ->
                runCatching { SupportIntent.valueOf(value) }
                    .getOrElse { throw IllegalArgumentException("Unsupported value '$value' for '$key'.") }
            }
            .toSet()
        if (variants.isEmpty()) {
            throw IllegalArgumentException("Field '$key' must be present.")
        }
        return when {
            SupportIntent.REFUND in variants -> SupportIntent.REFUND
            SupportIntent.TECH in variants -> SupportIntent.TECH
            SupportIntent.INFO in variants -> SupportIntent.INFO
            else -> SupportIntent.OTHER
        }
    }
}
