package com.finetune.desktop.supporttriage

import com.finetune.desktop.openai.ChatCompletionResult
import com.finetune.desktop.openai.ChatTurn
import com.finetune.desktop.openai.TokenUsage

class SupportTriageRunner(
    private val infer: suspend (history: List<ChatTurn>, temperature: Double) -> ChatCompletionResult,
) {
    suspend fun runMonolithic(input: String): SupportTriageRunResult {
        val start = System.currentTimeMillis()
        var usage = TokenUsage(0, 0)
        var parsingErrors = 0
        var final: SupportTriageFinalResult? = null
        val stage = runStage("Monolithic") {
            infer(
                listOf(
                    ChatTurn("system", SupportTriagePrompts.monolithicSystem),
                    ChatTurn("user", SupportTriagePrompts.monolithicUser(input)),
                ),
                0.0,
            )
        }.also { record ->
            usage += record.usage
            val raw = record.rawResponse
            if (raw != null) {
                val parsed = SupportTriageValidator.parseFinal(raw)
                final = parsed.value
                if (!parsed.isValid) parsingErrors += 1
                if (!parsed.isValid) {
                    return@also
                }
            }
        }
        val validatedStage = if (stage.rawResponse != null && final == null) {
            stage.copy(valid = false, error = "Invalid final JSON.")
        } else {
            stage.copy(valid = final != null)
        }
        val totalLatency = System.currentTimeMillis() - start
        return SupportTriageRunResult(
            input = input,
            pipeline = SupportTriagePipeline.MONOLITHIC,
            finalResult = final,
            rawFinalResponse = stage.rawResponse,
            stages = listOf(validatedStage),
            totalLatencyMs = totalLatency,
            modelInferences = if (stage.rawResponse == null && stage.error != null) 0 else 1,
            usage = usage,
            parsingErrors = parsingErrors,
        )
    }

    suspend fun runMultiStage(input: String): SupportTriageRunResult {
        val start = System.currentTimeMillis()
        val stages = mutableListOf<SupportTriageStageRecord>()
        var usage = TokenUsage(0, 0)
        var parsingErrors = 0
        var stage1Compact = ""
        var stage2Compact = ""
        var final: SupportTriageFinalResult? = null

        val stage1 = runStage("Stage 1") {
            infer(
                listOf(
                    ChatTurn("system", SupportTriagePrompts.compactSystem),
                    ChatTurn("user", SupportTriagePrompts.stage1User(input)),
                ),
                0.0,
            )
        }
        val stage1Parsed = stage1.rawResponse?.let(SupportTriageValidator::parseStage1)
        if (stage1Parsed?.isValid == true) {
            stage1Compact = stage1Parsed.normalizedJson ?: stage1.rawResponse
        } else if (stage1.rawResponse != null) {
            parsingErrors += 1
        }
        stages += stage1.copy(
            valid = stage1Parsed?.isValid == true,
            error = stage1.error ?: stage1Parsed?.error,
        )
        usage += stage1.usage

        val stage2 = runStage("Stage 2") {
            infer(
                listOf(
                    ChatTurn("system", SupportTriagePrompts.compactSystem),
                    ChatTurn("user", SupportTriagePrompts.stage2User(stage1Compact)),
                ),
                0.0,
            )
        }
        val stage2Parsed = stage2.rawResponse?.let(SupportTriageValidator::parseStage2)
        if (stage2Parsed?.isValid == true) {
            stage2Compact = stage2Parsed.normalizedJson ?: stage2.rawResponse
        } else if (stage2.rawResponse != null) {
            parsingErrors += 1
        }
        stages += stage2.copy(
            valid = stage2Parsed?.isValid == true,
            error = stage2.error ?: stage2Parsed?.error,
        )
        usage += stage2.usage

        val stage3 = runStage("Stage 3") {
            infer(
                listOf(
                    ChatTurn("system", SupportTriagePrompts.compactSystem),
                    ChatTurn("user", SupportTriagePrompts.stage3User(stage1Compact, stage2Compact)),
                ),
                0.0,
            )
        }
        val stage3Parsed = stage3.rawResponse?.let(SupportTriageValidator::parseFinal)
        if (stage3Parsed?.isValid == true) {
            final = normalizeFinalStatus(
                final = requireNotNull(stage3Parsed.value),
                stage1Valid = stage1Parsed?.isValid == true,
                stage2Valid = stage2Parsed?.isValid == true,
                stage2 = stage2Parsed?.value,
            )
        } else if (stage3.rawResponse != null) {
            parsingErrors += 1
        }
        stages += stage3.copy(
            valid = stage3Parsed?.isValid == true,
            error = stage3.error ?: stage3Parsed?.error,
        )
        usage += stage3.usage

        val totalLatency = System.currentTimeMillis() - start
        return SupportTriageRunResult(
            input = input,
            pipeline = SupportTriagePipeline.MULTI_STAGE,
            finalResult = final,
            rawFinalResponse = stage3.rawResponse,
            stages = stages,
            totalLatencyMs = totalLatency,
            modelInferences = stages.count { it.rawResponse != null || it.usage.inputTokens > 0 || it.usage.outputTokens > 0 },
            usage = usage,
            parsingErrors = parsingErrors,
        )
    }

    private suspend fun runStage(
        name: String,
        block: suspend () -> ChatCompletionResult,
    ): SupportTriageStageRecord {
        val start = System.currentTimeMillis()
        return runCatching { block() }
            .fold(
                onSuccess = { result ->
                    SupportTriageStageRecord(
                        name = name,
                        rawResponse = result.answer,
                        valid = true,
                        latencyMs = System.currentTimeMillis() - start,
                        usage = result.usage,
                    )
                },
                onFailure = { error ->
                    SupportTriageStageRecord(
                        name = name,
                        rawResponse = null,
                        valid = false,
                        latencyMs = System.currentTimeMillis() - start,
                        error = error.message ?: "Model inference failed.",
                    )
                },
            )
    }

    private fun normalizeFinalStatus(
        final: SupportTriageFinalResult,
        stage1Valid: Boolean,
        stage2Valid: Boolean,
        stage2: SupportTriageStage2Result?,
    ): SupportTriageFinalResult {
        val status = when {
            !stage1Valid || !stage2Valid -> SupportStatus.FAIL
            stage2?.decisionStatus == SupportStatus.FAIL -> SupportStatus.FAIL
            stage2?.decisionStatus == SupportStatus.UNSURE || final.confidence < 0.75 -> SupportStatus.UNSURE
            else -> SupportStatus.OK
        }
        return final.copy(status = status)
    }
}

fun buildSupportTriageMetrics(
    results: List<SupportTriageRunResult>,
    pipeline: SupportTriagePipeline,
): SupportTriageMetrics {
    val scoped = results.filter { it.pipeline == pipeline }
    val totalLatency = scoped.sumOf { it.totalLatencyMs }
    val statuses = scoped.map { it.finalResult?.status ?: SupportStatus.FAIL }
    fun averageStageLatency(stageName: String): Long {
        val records = scoped.mapNotNull { result -> result.stages.firstOrNull { it.name == stageName } }
        return records.takeIf { it.isNotEmpty() }?.let { it.sumOf(SupportTriageStageRecord::latencyMs) / it.size } ?: 0
    }
    return SupportTriageMetrics(
        totalRequests = scoped.size,
        successful = statuses.count { it == SupportStatus.OK },
        unsuccessful = statuses.count { it != SupportStatus.OK },
        modelInferences = scoped.sumOf { it.modelInferences },
        failedStages = scoped.sumOf { result -> result.stages.count { !it.valid } },
        parsingErrors = scoped.sumOf { it.parsingErrors },
        inputTokens = scoped.sumOf { it.usage.inputTokens },
        outputTokens = scoped.sumOf { it.usage.outputTokens },
        averageLatencyMs = scoped.takeIf { it.isNotEmpty() }?.let { totalLatency / it.size } ?: 0,
        totalLatencyMs = totalLatency,
        okCount = statuses.count { it == SupportStatus.OK },
        unsureCount = statuses.count { it == SupportStatus.UNSURE },
        failCount = statuses.count { it == SupportStatus.FAIL },
        stage1Failures = scoped.sumOf { result -> result.stages.count { it.name == "Stage 1" && !it.valid } },
        stage2Failures = scoped.sumOf { result -> result.stages.count { it.name == "Stage 2" && !it.valid } },
        stage3Failures = scoped.sumOf { result -> result.stages.count { it.name == "Stage 3" && !it.valid } },
        averageStage1LatencyMs = averageStageLatency("Stage 1"),
        averageStage2LatencyMs = averageStageLatency("Stage 2"),
        averageStage3LatencyMs = averageStageLatency("Stage 3"),
    )
}
