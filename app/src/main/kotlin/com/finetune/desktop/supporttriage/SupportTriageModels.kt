package com.finetune.desktop.supporttriage

import com.finetune.desktop.openai.TokenUsage

enum class SupportIntent {
    REFUND,
    TECH,
    INFO,
    OTHER,
}

enum class SupportSentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE,
}

enum class SupportUrgency {
    LOW,
    MEDIUM,
    HIGH,
}

enum class SupportStatus {
    OK,
    UNSURE,
    FAIL,
}

enum class NoiseLevel {
    LOW,
    MEDIUM,
    HIGH,
}

enum class SupportTriagePipeline {
    MONOLITHIC,
    MULTI_STAGE,
}

data class SupportTriageFinalResult(
    val intent: SupportIntent,
    val sentiment: SupportSentiment,
    val urgency: SupportUrgency,
    val needsHuman: Boolean,
    val confidence: Double,
    val status: SupportStatus,
) {
    fun toJsonString(): String {
        return """
            {
              "intent": "${intent.name}",
              "sentiment": "${sentiment.name}",
              "urgency": "${urgency.name}",
              "needs_human": $needsHuman,
              "confidence": ${"%.3f".format(java.util.Locale.US, confidence)},
              "status": "${status.name}"
            }
        """.trimIndent()
    }
}

data class SupportTriageStage1Result(
    val hasPaymentIssue: Boolean,
    val hasTechnicalIssue: Boolean,
    val hasInfoRequest: Boolean,
    val hasComplaint: Boolean,
    val mentionsAccessProblem: Boolean,
    val mentionsRefund: Boolean,
    val emotion: SupportSentiment,
    val noiseLevel: NoiseLevel,
    val normalizedSummary: String,
)

data class SupportTriageStage2Result(
    val intent: SupportIntent,
    val urgency: SupportUrgency,
    val needsHuman: Boolean,
    val decisionConfidence: Double,
    val decisionStatus: SupportStatus,
)

data class SupportTriageStageRecord(
    val name: String,
    val rawResponse: String?,
    val valid: Boolean,
    val latencyMs: Long,
    val usage: TokenUsage = TokenUsage(0, 0),
    val error: String? = null,
)

data class SupportTriageRunResult(
    val input: String,
    val pipeline: SupportTriagePipeline,
    val finalResult: SupportTriageFinalResult?,
    val rawFinalResponse: String?,
    val stages: List<SupportTriageStageRecord>,
    val totalLatencyMs: Long,
    val modelInferences: Int,
    val usage: TokenUsage,
    val parsingErrors: Int,
)

data class SupportTriageMetrics(
    val totalRequests: Int,
    val successful: Int,
    val unsuccessful: Int,
    val modelInferences: Int,
    val failedStages: Int,
    val parsingErrors: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val averageLatencyMs: Long,
    val totalLatencyMs: Long,
    val okCount: Int,
    val unsureCount: Int,
    val failCount: Int,
    val stage1Failures: Int = 0,
    val stage2Failures: Int = 0,
    val stage3Failures: Int = 0,
    val averageStage1LatencyMs: Long = 0,
    val averageStage2LatencyMs: Long = 0,
    val averageStage3LatencyMs: Long = 0,
)

