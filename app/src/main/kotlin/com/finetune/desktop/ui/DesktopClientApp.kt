package com.finetune.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finetune.desktop.AppConfig
import com.finetune.desktop.batch.BatchDatasetEntry
import com.finetune.desktop.batch.BatchDatasetIO
import com.finetune.desktop.batch.BatchResultExport
import com.finetune.desktop.openai.ChatTurn
import com.finetune.desktop.openai.ConfidenceStatus
import com.finetune.desktop.openai.FineTuneJobInfo
import com.finetune.desktop.openai.ModelOption
import com.finetune.desktop.openai.ModelProvider
import com.finetune.desktop.openai.OpenAiClient
import com.finetune.desktop.openai.OllamaClient
import com.finetune.desktop.supporttriage.SupportStatus
import com.finetune.desktop.supporttriage.SupportTriageMetrics
import com.finetune.desktop.supporttriage.SupportTriagePipeline
import com.finetune.desktop.supporttriage.SupportTriageRunResult
import com.finetune.desktop.supporttriage.SupportTriageRunner
import com.finetune.desktop.supporttriage.buildSupportTriageMetrics
import com.finetune.desktop.validation.DatasetValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private enum class DesktopTab(val title: String) {
    CHAT("Chat"),
    BATCH("Batch run"),
    SUPPORT_TRIAGE("Support triage"),
    FINE_TUNE("Fine-tune"),
    VALIDATE("Validate dataset"),
}

private enum class ValidationIndicator {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR,
}

private enum class TranscriptRole {
    USER,
    ASSISTANT,
}

private enum class ConfidenceMode(val title: String) {
    SCORING("Scoring"),
    REDUNDANCY("Redundancy"),
}

private enum class ChatRunMode(val title: String) {
    MANUAL("Manual"),
    IMPORT("Import"),
}

private data class TranscriptMessage(
    val role: TranscriptRole,
    val speaker: String,
    val content: String,
    val confidenceStatus: ConfidenceStatus? = null,
    val auxiliaryLines: List<String> = emptyList(),
    val usedStrongModel: Boolean = false,
    val strongModelLabel: String? = null,
)

private data class ChatImportItem(
    val prompt: String,
    val response: String? = null,
    val confidenceStatus: ConfidenceStatus? = null,
    val auxiliaryLines: List<String> = emptyList(),
    val inferenceCount: Int = 0,
    val strongModelInferenceCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val error: String? = null,
    val isRunning: Boolean = false,
    val usedStrongModel: Boolean = false,
    val strongModelLabel: String? = null,
)

private data class ChatImportMetrics(
    val totalRequests: Int,
    val successfulCount: Int,
    val unsuccessfulCount: Int,
    val inferenceCount: Int,
    val strongModelInferenceCount: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val statusCounts: Map<ConfidenceStatus, Int>,
    val errorCount: Int,
)

private data class ConfidenceExecutionResult(
    val answer: String,
    val status: ConfidenceStatus,
    val auxiliaryLines: List<String>,
    val inferenceCount: Int,
    val strongModelInferenceCount: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val usedStrongModel: Boolean,
    val strongModelLabel: String?,
)

private data class ModelExecutionTarget(
    val option: ModelOption,
    val client: OpenAiClient,
    val apiKey: String?,
)

private data class BatchPromptItem(
    val lineNumber: Int,
    val systemPrompt: String,
    val userPrompt: String,
    val expectedAssistant: String?,
    val selected: Boolean = false,
    val response: String? = null,
    val error: String? = null,
    val isRunning: Boolean = false,
    val wasRequested: Boolean = false,
)

private data class SupportTriageComparisonItem(
    val input: String,
    val monolithic: SupportTriageRunResult? = null,
    val multiStage: SupportTriageRunResult? = null,
    val isRunning: Boolean = false,
)

private class DesktopClientState(
    private val client: OpenAiClient = OpenAiClient(),
) {
    private val ollamaClient = OllamaClient(AppConfig.ollamaBaseUrl)
    private val ollamaChatClient = OpenAiClient(AppConfig.ollamaOpenAiBaseUrl)
    private val json = Json { ignoreUnknownKeys = true }
    private val projectModelOptions = listOf(
        ModelOption(name = AppConfig.chatModel, provider = ModelProvider.OPENAI),
        ModelOption(name = AppConfig.strongChatModel, provider = ModelProvider.OPENAI),
        ModelOption(name = AppConfig.fineTuneModel, provider = ModelProvider.OPENAI),
    ).distinctBy(ModelOption::key)
    val availableModelOptions = mutableStateListOf<ModelOption>().apply {
        addAll(projectModelOptions)
    }
    var selectedTab by mutableStateOf(DesktopTab.CHAT)
    var systemPrompt by mutableStateOf("You are a helpful assistant.")
    var chatTemperature by mutableStateOf("1.0")
    var prompt by mutableStateOf("")
    var chatRunMode by mutableStateOf(ChatRunMode.MANUAL)
    var selectedChatModelKey by mutableStateOf(projectModelOptions.first().key)
    var selectedWeakModelKey by mutableStateOf(projectModelOptions.first().key)
    var selectedStrongModelKey by mutableStateOf(
        projectModelOptions.firstOrNull { it.name == AppConfig.strongChatModel }?.key ?: projectModelOptions.first().key
    )
    var isRefreshingModels by mutableStateOf(false)
    var modelCatalogStatus by mutableStateOf("Project models loaded. You can also fetch models from Ollama.")
    val chatMessages = mutableStateListOf<TranscriptMessage>()
    val chatLogs = mutableStateListOf<String>()
    private val chatHistory = mutableListOf<ChatTurn>()
    private var chatRequestCounter = 0
    var isChatLoading by mutableStateOf(false)
    var isConfidenceEnabled by mutableStateOf(false)
    var selectedConfidenceMode by mutableStateOf(ConfidenceMode.SCORING)
    val scoringEnumOptions = mutableStateListOf<String>()
    var chatImportPath by mutableStateOf("")
    val chatImportItems = mutableStateListOf<ChatImportItem>()
    var chatImportSummary by mutableStateOf("Select a JSON file with an array of prompt strings.")
    var chatImportMetrics by mutableStateOf<ChatImportMetrics?>(null)

    var fineTuneDatasetPath by mutableStateOf("")
    val fineTuneLogs = mutableStateListOf<String>()
    var isFineTuneRunning by mutableStateOf(false)
    private var pollingJob: Job? = null

    var batchDatasetPath by mutableStateOf("")
    val batchItems = mutableStateListOf<BatchPromptItem>()
    var batchSummary by mutableStateOf("Select a dataset to load user prompts.")
    var isBatchRunning by mutableStateOf(false)

    var supportTriageInput by mutableStateOf(
        """
        После оплаты подписка не появилась, деньги списались. Верните деньги или подключите доступ.
        Не могу войти в аккаунт, код подтверждения не приходит уже час.
        Подскажите, как отменить автопродление подписки?
        Спасибо, все работает отлично.
        """.trimIndent()
    )
    var supportTriageJsonPath by mutableStateOf("")
    val supportTriageItems = mutableStateListOf<SupportTriageComparisonItem>()
    var supportTriageSummary by mutableStateOf("Enter one support request per line and run comparison.")
    var isSupportTriageRunning by mutableStateOf(false)
    var supportTriageMonolithicMetrics by mutableStateOf<SupportTriageMetrics?>(null)
    var supportTriageMultiStageMetrics by mutableStateOf<SupportTriageMetrics?>(null)
    val supportTriageLogs = mutableStateListOf<String>()
    private var supportTriageJob: Job? = null

    var validationDatasetPath by mutableStateOf("")
    var validationSummary by mutableStateOf("Select a dataset and start validation.")
    var validationDetails by mutableStateOf("")
    var validationIndicator by mutableStateOf(ValidationIndicator.IDLE)
    var isValidationRunning by mutableStateOf(false)

    var dialogMessage by mutableStateOf<String?>(null)

    fun dispose() {
        pollingJob?.cancel()
        supportTriageJob?.cancel()
    }

    fun selectedChatModel(): ModelOption = resolveModelOption(selectedChatModelKey)

    fun selectedWeakModel(): ModelOption = resolveModelOption(selectedWeakModelKey)

    fun selectedStrongModel(): ModelOption = resolveModelOption(selectedStrongModelKey)

    fun addScoringEnumOption() {
        scoringEnumOptions += ""
    }

    fun updateScoringEnumOption(index: Int, value: String) {
        if (index in scoringEnumOptions.indices) {
            scoringEnumOptions[index] = value
        }
    }

    fun refreshOllamaModels(scope: CoroutineScope) {
        if (isRefreshingModels) return
        isRefreshingModels = true
        modelCatalogStatus = "Loading models from Ollama..."
        scope.launch {
            runCatching { ollamaClient.fetchAvailableModels() }
                .onSuccess { ollamaModels ->
                    val merged = (projectModelOptions + ollamaModels)
                        .distinctBy(ModelOption::key)
                        .sortedWith(compareBy<ModelOption>({ it.provider.ordinal }, { it.name.lowercase() }))
                    availableModelOptions.clear()
                    availableModelOptions.addAll(merged)
                    modelCatalogStatus = if (ollamaModels.isEmpty()) {
                        "No Ollama models found. Project models remain available."
                    } else {
                        "Loaded ${ollamaModels.size} model(s) from Ollama."
                    }
                }
                .onFailure { error ->
                    modelCatalogStatus = "Could not load Ollama models."
                    dialogMessage = error.message ?: "Could not load Ollama models."
                }
            isRefreshingModels = false
        }
    }

    fun clearChat() {
        if (isChatLoading) return
        chatHistory.clear()
        chatMessages.clear()
        chatLogs.clear()
        chatImportItems.clear()
        chatImportMetrics = null
        chatImportSummary = "Select a JSON file with an array of prompt strings."
    }

    private fun resolveModelOption(key: String): ModelOption {
        return availableModelOptions.firstOrNull { it.key == key }
            ?: projectModelOptions.first()
    }

    private fun createExecutionTarget(option: ModelOption): ModelExecutionTarget {
        return when (option.provider) {
            ModelProvider.OPENAI -> ModelExecutionTarget(
                option = option,
                client = client,
                apiKey = AppConfig.apiKey,
            )

            ModelProvider.OLLAMA -> ModelExecutionTarget(
                option = option,
                client = ollamaChatClient,
                apiKey = null,
            )
        }
    }

    private fun activeScoringEnumOptions(): Set<String> {
        return scoringEnumOptions
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
    }

    fun browseChatImportFile() {
        chooseJsonFile(chatImportPath)?.let { file ->
            chatImportPath = file.absolutePath
            loadChatImportPrompts(file)
        }
    }

    fun browseFineTuneDataset() {
        chooseJsonlFile(fineTuneDatasetPath)?.let { fineTuneDatasetPath = it.absolutePath }
    }

    fun browseValidationDataset() {
        chooseJsonlFile(validationDatasetPath)?.let { validationDatasetPath = it.absolutePath }
    }

    fun browseBatchDataset() {
        chooseJsonlFile(batchDatasetPath)?.let { file ->
            batchDatasetPath = file.absolutePath
            loadBatchDataset(file)
        }
    }

    fun toggleBatchSelection(index: Int, selected: Boolean) {
        batchItems[index] = batchItems[index].copy(selected = selected)
    }

    fun selectedBatchCount(): Int = batchItems.count { it.selected }

    fun runSupportTriage(scope: CoroutineScope) {
        if (isSupportTriageRunning) return
        val inputs = supportTriageInput
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)
        if (inputs.isEmpty()) {
            dialogMessage = "Enter at least one support request."
            return
        }

        val target = createExecutionTarget(selectedChatModel())
        val runner = SupportTriageRunner { history, temperature ->
            target.client.sendChatCompletionDetailed(target.apiKey, target.option.name, history, temperature)
        }
        supportTriageItems.clear()
        supportTriageItems.addAll(inputs.map { SupportTriageComparisonItem(input = it) })
        supportTriageMonolithicMetrics = null
        supportTriageMultiStageMetrics = null
        supportTriageLogs.clear()
        supportTriageSummary = "Running ${inputs.size} support triage request(s) with ${target.option.label}..."
        isSupportTriageRunning = true

        supportTriageJob = scope.launch {
            val results = mutableListOf<SupportTriageRunResult>()
            runCatching {
                inputs.forEachIndexed { index, input ->
                    if (!isActive) throw CancellationException("Support triage run was stopped.")
                    supportTriageItems[index] = supportTriageItems[index].copy(isRunning = true)
                    supportTriageLogs += "Request ${index + 1}: monolithic started."
                    val monolithic = runner.runMonolithic(input)
                    results += monolithic
                    supportTriageItems[index] = supportTriageItems[index].copy(monolithic = monolithic)
                    supportTriageLogs += formatSupportTriageLog(index + 1, monolithic)

                    if (!isActive) throw CancellationException("Support triage run was stopped.")
                    supportTriageLogs += "Request ${index + 1}: multi-stage started."
                    val multiStage = runner.runMultiStage(input)
                    results += multiStage
                    supportTriageItems[index] = supportTriageItems[index].copy(
                        multiStage = multiStage,
                        isRunning = false,
                    )
                    supportTriageLogs += formatSupportTriageLog(index + 1, multiStage)
                }
                supportTriageSummary = "Completed ${inputs.size} support triage comparison(s)."
            }.onFailure { error ->
                if (error is CancellationException) {
                    supportTriageLogs += "Run stopped by user."
                    supportTriageSummary = "Stopped. Completed ${results.count { it.pipeline == SupportTriagePipeline.MONOLITHIC }} monolithic and ${results.count { it.pipeline == SupportTriagePipeline.MULTI_STAGE }} multi-stage run(s)."
                } else {
                    supportTriageLogs += "Run failed: ${error.message ?: "Unknown error"}"
                    supportTriageSummary = "Support triage run failed."
                }
            }

            supportTriageItems.indices.forEach { index ->
                if (supportTriageItems[index].isRunning) {
                    supportTriageItems[index] = supportTriageItems[index].copy(isRunning = false)
                }
            }
            supportTriageMonolithicMetrics = buildSupportTriageMetrics(results, SupportTriagePipeline.MONOLITHIC)
            supportTriageMultiStageMetrics = buildSupportTriageMetrics(results, SupportTriagePipeline.MULTI_STAGE)
            isSupportTriageRunning = false
            supportTriageJob = null
        }
    }

    fun stopSupportTriage() {
        if (!isSupportTriageRunning) return
        supportTriageSummary = "Stopping support triage run..."
        supportTriageLogs += "Stop requested."
        supportTriageJob?.cancel(CancellationException("Support triage run stopped by user."))
    }

    fun browseSupportTriageJsonFile() {
        chooseJsonFile(supportTriageJsonPath)?.let { file ->
            supportTriageJsonPath = file.absolutePath
            loadSupportTriageRequests(file)
        }
    }

    fun sendChat(scope: CoroutineScope) {
        val promptText = prompt.trim()
        val systemPromptText = systemPrompt.trim()
        val temperature = parseChatTemperatureOrShowError() ?: return
        if (promptText.isBlank()) {
            dialogMessage = "Enter a message before sending."
            return
        }

        if (chatHistory.isEmpty() && systemPromptText.isNotBlank()) {
            chatHistory += ChatTurn("system", systemPromptText)
        }

        chatHistory += ChatTurn("user", promptText)
        chatMessages += TranscriptMessage(
            role = TranscriptRole.USER,
            speaker = "You",
            content = promptText,
        )
        prompt = ""
        isChatLoading = true

        val assistantIndex = chatMessages.size
        chatMessages += TranscriptMessage(
            role = TranscriptRole.ASSISTANT,
            speaker = "Model",
            content = "",
        )
        val requestId = ++chatRequestCounter
        val modeLabel = confidenceModeLabel(selectedConfidenceMode.takeIf { isConfidenceEnabled })
        val requestModel = if (isConfidenceEnabled) selectedWeakModel() else selectedChatModel()
        val requestHistory = if (selectedConfidenceMode.takeIf { isConfidenceEnabled } == ConfidenceMode.SCORING) {
            buildScoredLogHistory(chatHistory)
        } else {
            chatHistory
        }
        appendChatLog(
            buildString {
                appendLine("Request #$requestId")
                appendLine("Mode: $modeLabel")
                appendLine("Model: ${requestModel.label}")
                appendLine("Temperature: $temperature")
                appendLine("Messages:")
                requestHistory.forEachIndexed { index, turn ->
                    appendLine("${index + 1}. ${turn.role.uppercase()}")
                    appendLine(formatLogBlock(turn.content))
                }
            }.trimEnd()
        )

        scope.launch {
            val confidenceMode = selectedConfidenceMode.takeIf { isConfidenceEnabled }
            runCatching {
                when (confidenceMode) {
                    ConfidenceMode.SCORING -> {
                        executeConfidenceRoute(
                            requestId = requestId,
                            history = chatHistory,
                            temperature = temperature,
                            mode = ConfidenceMode.SCORING,
                        ).also { response ->
                            chatMessages[assistantIndex] = chatMessages[assistantIndex].copy(
                                content = response.answer,
                                confidenceStatus = response.status,
                                usedStrongModel = response.usedStrongModel,
                                strongModelLabel = response.strongModelLabel,
                            )
                        }.answer
                    }
                    ConfidenceMode.REDUNDANCY -> {
                        executeConfidenceRoute(
                            requestId = requestId,
                            history = chatHistory,
                            temperature = temperature,
                            mode = ConfidenceMode.REDUNDANCY,
                        ).also { response ->
                            chatMessages[assistantIndex] = chatMessages[assistantIndex].copy(
                                content = response.answer,
                                confidenceStatus = response.status,
                                auxiliaryLines = response.auxiliaryLines,
                                usedStrongModel = response.usedStrongModel,
                                strongModelLabel = response.strongModelLabel,
                            )
                        }.answer
                    }
                    null -> {
                        val target = createExecutionTarget(selectedChatModel())
                        val answerBuilder = StringBuilder()
                        target.client.streamChatCompletion(target.apiKey, target.option.name, chatHistory, temperature).collect { delta ->
                            answerBuilder.append(delta)
                            chatMessages[assistantIndex] = chatMessages[assistantIndex].copy(
                                content = answerBuilder.toString()
                            )
                        }
                        answerBuilder.toString().also { answer ->
                            appendChatLog(
                                buildString {
                                    appendLine("Response #$requestId")
                                    appendLine("Mode: Standard")
                                    appendLine("Answer:")
                                    append(formatLogBlock(answer))
                                }.trimEnd()
                            )
                        }
                    }
                }
            }.onSuccess { answer ->
                chatHistory += ChatTurn("assistant", answer)
                isChatLoading = false
            }.onFailure { error ->
                chatHistory.removeLastOrNull()
                chatMessages[assistantIndex] = TranscriptMessage(
                    role = TranscriptRole.ASSISTANT,
                    speaker = "Model",
                    content = "Error:\n${error.message ?: "Unknown error"}",
                )
                appendChatLog(
                    buildString {
                        appendLine("Response #$requestId")
                        appendLine("Mode: $modeLabel")
                        appendLine("Error:")
                        append(formatLogBlock(error.message ?: "Unknown error"))
                    }.trimEnd()
                )
                isChatLoading = false
            }
        }
    }

    fun runImportedChat(scope: CoroutineScope) {
        val temperature = parseChatTemperatureOrShowError() ?: return
        if (chatImportItems.isEmpty()) {
            dialogMessage = "Select a JSON file with prompts before running import."
            return
        }

        isChatLoading = true
        chatHistory.clear()
        chatMessages.clear()
        chatLogs.clear()
        chatImportMetrics = null
        chatImportSummary = "Processing ${chatImportItems.size} imported prompt(s)..."
        for (index in chatImportItems.indices) {
            chatImportItems[index] = chatImportItems[index].copy(
                response = null,
                confidenceStatus = null,
                auxiliaryLines = emptyList(),
                inferenceCount = 0,
                strongModelInferenceCount = 0,
                inputTokens = 0,
                outputTokens = 0,
                error = null,
                isRunning = false,
                usedStrongModel = false,
                strongModelLabel = null,
            )
        }

        scope.launch {
            chatImportItems.forEachIndexed { index, item ->
                val history = buildChatPromptHistory(item.prompt)
                val userMessageIndex = chatMessages.size
                chatMessages += TranscriptMessage(
                    role = TranscriptRole.USER,
                    speaker = "You",
                    content = item.prompt,
                )
                val assistantIndex = chatMessages.size
                chatMessages += TranscriptMessage(
                    role = TranscriptRole.ASSISTANT,
                    speaker = "Model",
                    content = "",
                )
                chatImportItems[index] = chatImportItems[index].copy(isRunning = true)
                val requestId = ++chatRequestCounter
                val modeLabel = confidenceModeLabel(selectedConfidenceMode.takeIf { isConfidenceEnabled })
                val requestModel = if (isConfidenceEnabled) selectedWeakModel() else selectedChatModel()
                val requestHistory = if (selectedConfidenceMode.takeIf { isConfidenceEnabled } == ConfidenceMode.SCORING) {
                    buildScoredLogHistory(history)
                } else {
                    history
                }
                appendChatLog(
                    buildString {
                        appendLine("Request #$requestId")
                        appendLine("Mode: $modeLabel")
                        appendLine("Model: ${requestModel.label}")
                        appendLine("Temperature: $temperature")
                        appendLine("Messages:")
                        requestHistory.forEachIndexed { historyIndex, turn ->
                            appendLine("${historyIndex + 1}. ${turn.role.uppercase()}")
                            appendLine(formatLogBlock(turn.content))
                        }
                    }.trimEnd()
                )

                runCatching {
                    when (val confidenceMode = selectedConfidenceMode.takeIf { isConfidenceEnabled }) {
                        ConfidenceMode.SCORING -> {
                            executeConfidenceRoute(
                                requestId = requestId,
                                history = history,
                                temperature = temperature,
                                mode = confidenceMode,
                            ).also { response ->
                                chatMessages[assistantIndex] = chatMessages[assistantIndex].copy(
                                    content = response.answer,
                                    confidenceStatus = response.status,
                                    usedStrongModel = response.usedStrongModel,
                                    strongModelLabel = response.strongModelLabel,
                                )
                                chatImportItems[index] = chatImportItems[index].copy(
                                    response = response.answer,
                                    confidenceStatus = response.status,
                                    inferenceCount = response.inferenceCount,
                                    strongModelInferenceCount = response.strongModelInferenceCount,
                                    inputTokens = response.inputTokens,
                                    outputTokens = response.outputTokens,
                                    isRunning = false,
                                    usedStrongModel = response.usedStrongModel,
                                    strongModelLabel = response.strongModelLabel,
                                )
                            }
                        }
                        ConfidenceMode.REDUNDANCY -> {
                            executeConfidenceRoute(
                                requestId = requestId,
                                history = history,
                                temperature = temperature,
                                mode = confidenceMode,
                            ).also { response ->
                                chatMessages[assistantIndex] = chatMessages[assistantIndex].copy(
                                    content = response.answer,
                                    confidenceStatus = response.status,
                                    auxiliaryLines = response.auxiliaryLines,
                                    usedStrongModel = response.usedStrongModel,
                                    strongModelLabel = response.strongModelLabel,
                                )
                                chatImportItems[index] = chatImportItems[index].copy(
                                    response = response.answer,
                                    confidenceStatus = response.status,
                                    auxiliaryLines = response.auxiliaryLines,
                                    inferenceCount = response.inferenceCount,
                                    strongModelInferenceCount = response.strongModelInferenceCount,
                                    inputTokens = response.inputTokens,
                                    outputTokens = response.outputTokens,
                                    isRunning = false,
                                    usedStrongModel = response.usedStrongModel,
                                    strongModelLabel = response.strongModelLabel,
                                )
                            }
                        }
                        null -> {
                            val target = createExecutionTarget(selectedChatModel())
                            target.client.sendChatCompletionDetailed(target.apiKey, target.option.name, history, temperature)
                                .also { response ->
                                    chatMessages[assistantIndex] = chatMessages[assistantIndex].copy(
                                        content = response.answer.ifBlank { "-" },
                                    )
                                    chatImportItems[index] = chatImportItems[index].copy(
                                        response = response.answer.ifBlank { "-" },
                                        inferenceCount = 1,
                                        inputTokens = response.usage.inputTokens,
                                        outputTokens = response.usage.outputTokens,
                                        isRunning = false,
                                    )
                                    appendChatLog(
                                        buildString {
                                            appendLine("Response #$requestId")
                                            appendLine("Mode: Standard")
                                            appendLine("Answer:")
                                            append(formatLogBlock(response.answer))
                                        }.trimEnd()
                                    )
                                }
                        }
                    }
                }.onFailure { error ->
                    chatMessages[assistantIndex] = TranscriptMessage(
                        role = TranscriptRole.ASSISTANT,
                        speaker = "Model",
                        content = "Error:\n${error.message ?: "Unknown error"}",
                    )
                    chatImportItems[index] = chatImportItems[index].copy(
                        response = null,
                        error = error.message ?: "Unknown error",
                        isRunning = false,
                    )
                    appendChatLog(
                        buildString {
                            appendLine("Response #$requestId")
                            appendLine("Mode: $modeLabel")
                            appendLine("Error:")
                            append(formatLogBlock(error.message ?: "Unknown error"))
                        }.trimEnd()
                    )
                }
            }

            chatImportMetrics = buildChatImportMetrics()
            chatImportSummary = "Completed ${chatImportItems.size} imported prompt(s)."
            isChatLoading = false
        }
    }

    fun sendBatch(scope: CoroutineScope) {
        val selectedIndices = batchItems.withIndex()
            .filter { it.value.selected }
            .map { it.index }
        if (selectedIndices.isEmpty()) {
            dialogMessage = "Select at least one prompt before sending."
            return
        }

        isBatchRunning = true
        batchSummary = "Sending ${selectedIndices.size} selected prompt(s)..."
        selectedIndices.forEach { index ->
            batchItems[index] = batchItems[index].copy(
                response = null,
                error = null,
                isRunning = false,
            )
        }

        scope.launch {
            selectedIndices.forEachIndexed { completedCount, index ->
                val item = batchItems[index]
                batchItems[index] = item.copy(isRunning = true)
                batchSummary = "Processing ${completedCount + 1} of ${selectedIndices.size}..."

                val history = buildList {
                    add(ChatTurn("system", item.systemPrompt))
                    add(ChatTurn("user", item.userPrompt))
                }

                runCatching {
                    val target = createExecutionTarget(selectedChatModel())
                    target.client.sendChatCompletion(target.apiKey, target.option.name, history, 1.0)
                }.onSuccess { response ->
                    batchItems[index] = batchItems[index].copy(
                        response = response,
                        error = null,
                        isRunning = false,
                        wasRequested = true,
                    )
                }.onFailure { error ->
                    batchItems[index] = batchItems[index].copy(
                        response = null,
                        error = error.message ?: "Unknown error",
                        isRunning = false,
                        wasRequested = true,
                    )
                }
            }

            batchSummary = "Completed ${selectedIndices.size} prompt(s)."
            isBatchRunning = false
        }
    }

    fun exportBatchResults() {
        if (batchItems.isEmpty()) {
            dialogMessage = "Load a dataset before exporting."
            return
        }

        val requestedItems = batchItems.filter { it.wasRequested }
        if (requestedItems.isEmpty()) {
            dialogMessage = "There are no completed batch requests to export yet."
            return
        }

        val exportFile = chooseJsonExportFile(batchDatasetPath) ?: return
        runCatching {
            BatchDatasetIO.export(
                file = exportFile,
                results = requestedItems.map { item ->
                    BatchResultExport(
                        lineNumber = item.lineNumber,
                        selected = item.selected,
                        systemPrompt = item.systemPrompt,
                        userPrompt = item.userPrompt,
                        expectedAssistant = item.expectedAssistant,
                        response = item.response,
                        error = item.error,
                    )
                },
            )
        }.onSuccess {
            batchSummary = "Exported results to ${exportFile.name}."
        }.onFailure { error ->
            dialogMessage = "Export failed: ${error.message ?: "Unknown error"}"
        }
    }

    fun startFineTune(scope: CoroutineScope) {
        val datasetPath = fineTuneDatasetPath.trim()
        if (datasetPath.isBlank()) {
            dialogMessage = "Select a JSONL file first."
            return
        }

        val datasetFile = File(datasetPath)
        if (!datasetFile.exists() || !datasetFile.isFile) {
            dialogMessage = "Selected dataset file does not exist."
            return
        }

        isFineTuneRunning = true
        fineTuneLogs.clear()
        logFineTune("Selected dataset: ${datasetFile.name}")
        logFineTune("Uploading file to OpenAI Files API...")

        scope.launch {
            runCatching {
                val fileId = client.uploadTrainingFile(AppConfig.apiKey, datasetFile)
                client.createFineTuneJob(AppConfig.apiKey, AppConfig.fineTuneModel, fileId)
            }.onSuccess { job ->
                logFineTune("Upload complete. File id: ${job.fileId}")
                logFineTune("Fine-tune job created: ${job.jobId}")
                logFineTune("Initial status: ${job.status}")
                startPolling(scope, job)
            }.onFailure { error ->
                logFineTune("Failed: ${error.message ?: "Unknown error"}")
                isFineTuneRunning = false
            }
        }
    }

    fun validateDataset(scope: CoroutineScope) {
        val datasetPath = validationDatasetPath.trim()
        if (datasetPath.isBlank()) {
            dialogMessage = "Select a JSONL file first."
            return
        }

        val datasetFile = File(datasetPath)
        if (!datasetFile.exists() || !datasetFile.isFile) {
            dialogMessage = "Selected dataset file does not exist."
            return
        }

        isValidationRunning = true
        validationIndicator = ValidationIndicator.LOADING
        validationSummary = "Validation in progress..."
        validationDetails = "Validating ${datasetFile.name}..."

        scope.launch {
            runCatching {
                DatasetValidator.validate(datasetFile)
            }.onSuccess { result ->
                validationIndicator = if (result.isValid) {
                    ValidationIndicator.SUCCESS
                } else {
                    ValidationIndicator.ERROR
                }
                validationSummary = if (result.isValid) {
                    "Dataset is valid"
                } else {
                    "Dataset has validation errors"
                }
                validationDetails = buildString {
                    appendLine("File: ${datasetFile.name}")
                    appendLine("Total lines: ${result.totalLines}")
                    appendLine("Valid lines: ${result.validLines}")
                    appendLine()
                    if (result.isValid) {
                        append("Dataset is valid.")
                    } else {
                        appendLine("Found ${result.errors.size} issue(s):")
                        result.errors.forEach(::appendLine)
                    }
                }.trimEnd()
                isValidationRunning = false
            }.onFailure { error ->
                validationIndicator = ValidationIndicator.ERROR
                validationSummary = "Validation failed"
                validationDetails = "Validation failed: ${error.message ?: "Unknown error"}"
                isValidationRunning = false
            }
        }
    }

    private fun startPolling(scope: CoroutineScope, initialJob: FineTuneJobInfo) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var lastStatus = initialJob.status
            var done = isTerminal(lastStatus)
            while (isActive && !done) {
                delay(10_000)
                runCatching {
                    client.retrieveFineTuneJob(AppConfig.apiKey, initialJob.jobId)
                }.onSuccess { updated ->
                    if (updated.status != lastStatus || updated.fineTunedModel != null || updated.errorMessage != null) {
                        logFineTune("Polled status: ${updated.status}")
                        updated.fineTunedModel?.let { logFineTune("Fine-tuned model: $it") }
                        updated.errorMessage?.let { logFineTune("Error: $it") }
                    }
                    lastStatus = updated.status
                    done = isTerminal(updated.status)
                }.onFailure { error ->
                    logFineTune("Polling failed: ${error.message ?: "Unknown error"}")
                    done = true
                }
            }

            isFineTuneRunning = false
        }
    }

    private fun logFineTune(message: String) {
        fineTuneLogs += message
    }

    private fun appendChatLog(entry: String) {
        chatLogs += entry
        chatLogs += ""
    }

    private fun confidenceModeLabel(mode: ConfidenceMode?): String {
        return mode?.title ?: "Standard"
    }

    private fun formatLogBlock(text: String): String {
        return text.ifBlank { "-" }
            .lines()
            .joinToString(separator = "\n") { "  $it" }
    }

    private fun formatSupportTriageLog(requestNumber: Int, result: SupportTriageRunResult): String {
        return buildString {
            appendLine("Request $requestNumber: ${result.pipeline.name}")
            appendLine("Latency: ${result.totalLatencyMs} ms")
            appendLine("Inferences: ${result.modelInferences}")
            appendLine("Tokens: input=${result.usage.inputTokens}, output=${result.usage.outputTokens}")
            appendLine("Parsing errors: ${result.parsingErrors}")
            result.finalResult?.let { final ->
                appendLine("Final: ${final.status.name} ${final.intent.name}/${final.sentiment.name}/${final.urgency.name}, confidence=${"%.3f".format(java.util.Locale.US, final.confidence)}, needs_human=${final.needsHuman}")
            } ?: appendLine("Final: FAIL")
            result.stages.forEach { stage ->
                appendLine("${stage.name}: ${if (stage.valid) "OK" else "FAIL"} (${stage.latencyMs} ms)")
                stage.error?.let { appendLine("  Error: $it") }
            }
        }.trimEnd()
    }

    private fun parseChatTemperatureOrShowError(): Double? {
        val temperature = chatTemperature.trim().toDoubleOrNull()
        if (temperature == null || temperature !in 0.0..2.0) {
            dialogMessage = "Temperature must be a number between 0.0 and 2.0."
            return null
        }
        return temperature
    }

    private fun buildChatPromptHistory(promptText: String): List<ChatTurn> {
        val systemPromptText = systemPrompt.trim()
        return buildList {
            if (systemPromptText.isNotBlank()) {
                add(ChatTurn("system", systemPromptText))
            }
            add(ChatTurn("user", promptText))
        }
    }

    private suspend fun executeConfidenceRoute(
        requestId: Int,
        history: List<ChatTurn>,
        temperature: Double,
        mode: ConfidenceMode,
    ): ConfidenceExecutionResult {
        return when (mode) {
            ConfidenceMode.SCORING -> executeScoringRoute(requestId, history, temperature)
            ConfidenceMode.REDUNDANCY -> executeRedundancyRoute(requestId, history, temperature)
        }
    }

    private suspend fun executeScoringRoute(
        requestId: Int,
        history: List<ChatTurn>,
        temperature: Double,
    ): ConfidenceExecutionResult {
        val weakTarget = createExecutionTarget(selectedWeakModel())
        val strongTarget = createExecutionTarget(selectedStrongModel())
        val primary = runCatching {
            weakTarget.client.sendScoredChatCompletion(
                apiKey = weakTarget.apiKey,
                model = weakTarget.option.name,
                history = history,
                temperature = temperature,
            )
        }.getOrElse { error ->
            appendChatLog(
                buildString {
                    appendLine("Response #$requestId")
                    appendLine("Stage: Primary")
                    appendLine("Model: ${weakTarget.option.label}")
                    appendLine("Mode: Scoring")
                    appendLine("Error:")
                    appendLine(formatLogBlock(error.message ?: "Unknown error"))
                    append("Escalation #$requestId\n  Reason: Primary model failed.\n  Routing to stronger model: ${strongTarget.option.label}")
                }
            )
            val strong = strongTarget.client.sendScoredChatCompletion(
                apiKey = strongTarget.apiKey,
                model = strongTarget.option.name,
                history = history,
                temperature = temperature,
            )
            logScoringResponse(
                requestId = requestId,
                stage = "Strong",
                model = strongTarget.option.label,
                response = strong,
            )
            return ConfidenceExecutionResult(
                answer = strong.answer,
                status = strong.status,
                auxiliaryLines = emptyList(),
                inferenceCount = 1,
                strongModelInferenceCount = 1,
                inputTokens = strong.usage.inputTokens,
                outputTokens = strong.usage.outputTokens,
                usedStrongModel = true,
                strongModelLabel = strongTarget.option.label,
            )
        }
        logScoringResponse(
            requestId = requestId,
            stage = "Primary",
            model = weakTarget.option.label,
            response = primary,
        )
        val enumOptions = activeScoringEnumOptions()
        val isEnumValid = enumOptions.isEmpty() || primary.answer.trim() in enumOptions
        if (enumOptions.isNotEmpty()) {
            appendChatLog(
                buildString {
                    appendLine("Enum validation #$requestId")
                    appendLine("Allowed values: ${enumOptions.joinToString(", ")}")
                    appendLine("Answer: ${primary.answer.trim()}")
                    append("Result: ${if (isEnumValid) "PASS" else "FAIL"}")
                }
            )
        }
        if (primary.status == ConfidenceStatus.SURE && isEnumValid) {
            return ConfidenceExecutionResult(
                answer = primary.answer,
                status = primary.status,
                auxiliaryLines = emptyList(),
                inferenceCount = 1,
                strongModelInferenceCount = 0,
                inputTokens = primary.usage.inputTokens,
                outputTokens = primary.usage.outputTokens,
                usedStrongModel = false,
                strongModelLabel = null,
            )
        }

        val escalationReason = when {
            primary.status != ConfidenceStatus.SURE && !isEnumValid ->
                "Primary status is ${primary.status.name}, and answer does not match enum variants."
            primary.status != ConfidenceStatus.SURE ->
                "Primary status is ${primary.status.name}."
            else ->
                "Primary answer does not match enum variants."
        }
        appendChatLog(
            "Escalation #$requestId\n  Reason: $escalationReason\n  Routing to stronger model: ${strongTarget.option.label}"
        )
        val strong = strongTarget.client.sendScoredChatCompletion(
            apiKey = strongTarget.apiKey,
            model = strongTarget.option.name,
            history = history,
            temperature = temperature,
        )
        logScoringResponse(
            requestId = requestId,
            stage = "Strong",
            model = strongTarget.option.label,
            response = strong,
        )
        return ConfidenceExecutionResult(
            answer = strong.answer,
            status = strong.status,
            auxiliaryLines = emptyList(),
            inferenceCount = 2,
            strongModelInferenceCount = 1,
            inputTokens = primary.usage.inputTokens + strong.usage.inputTokens,
            outputTokens = primary.usage.outputTokens + strong.usage.outputTokens,
            usedStrongModel = true,
            strongModelLabel = strongTarget.option.label,
        )
    }

    private suspend fun executeRedundancyRoute(
        requestId: Int,
        history: List<ChatTurn>,
        temperature: Double,
    ): ConfidenceExecutionResult {
        val weakTarget = createExecutionTarget(selectedWeakModel())
        val strongTarget = createExecutionTarget(selectedStrongModel())
        val primary = weakTarget.client.sendRedundantChatCompletion(
            apiKey = weakTarget.apiKey,
            model = weakTarget.option.name,
            history = history,
            temperature = temperature,
        )
        logRedundancyResponse(
            requestId = requestId,
            stage = "Primary",
            model = weakTarget.option.label,
            response = primary,
        )
        if (primary.status == ConfidenceStatus.SURE) {
            return ConfidenceExecutionResult(
                answer = primary.answer,
                status = primary.status,
                auxiliaryLines = primary.responses,
                inferenceCount = primary.inferenceCount,
                strongModelInferenceCount = 0,
                inputTokens = primary.usage.inputTokens,
                outputTokens = primary.usage.outputTokens,
                usedStrongModel = false,
                strongModelLabel = null,
            )
        }

        appendChatLog("Escalation #$requestId\n  Routing to stronger model: ${strongTarget.option.label}")
        val strong = strongTarget.client.sendRedundantChatCompletion(
            apiKey = strongTarget.apiKey,
            model = strongTarget.option.name,
            history = history,
            temperature = temperature,
        )
        logRedundancyResponse(
            requestId = requestId,
            stage = "Strong",
            model = strongTarget.option.label,
            response = strong,
        )
        return ConfidenceExecutionResult(
            answer = strong.answer,
            status = strong.status,
            auxiliaryLines = strong.responses,
            inferenceCount = primary.inferenceCount + strong.inferenceCount,
            strongModelInferenceCount = strong.inferenceCount,
            inputTokens = primary.usage.inputTokens + strong.usage.inputTokens,
            outputTokens = primary.usage.outputTokens + strong.usage.outputTokens,
            usedStrongModel = true,
            strongModelLabel = strongTarget.option.label,
        )
    }

    private fun logScoringResponse(
        requestId: Int,
        stage: String,
        model: String,
        response: com.finetune.desktop.openai.ScoredChatResponse,
    ) {
        appendChatLog(
            buildString {
                appendLine("Response #$requestId")
                appendLine("Stage: $stage")
                appendLine("Model: $model")
                appendLine("Mode: Scoring")
                appendLine("Status: ${response.status.name}")
                appendLine("Raw answer:")
                appendLine(formatLogBlock(response.rawAnswer))
                appendLine("Raw API response:")
                appendLine(formatLogBlock(response.rawResponse))
                appendLine("Parsed answer:")
                append(formatLogBlock(response.answer))
            }.trimEnd()
        )
    }

    private fun logRedundancyResponse(
        requestId: Int,
        stage: String,
        model: String,
        response: com.finetune.desktop.openai.RedundantChatResponse,
    ) {
        appendChatLog(
            buildString {
                appendLine("Response #$requestId")
                appendLine("Stage: $stage")
                appendLine("Model: $model")
                appendLine("Mode: Redundancy")
                appendLine("Status: ${response.status.name}")
                appendLine("Responses:")
                response.responses.forEachIndexed { index, text ->
                    appendLine("${index + 1}.")
                    appendLine(formatLogBlock(text))
                }
                appendLine("Final answer:")
                append(formatLogBlock(response.answer))
            }.trimEnd()
        )
    }

    private fun buildScoredLogHistory(history: List<ChatTurn>): List<ChatTurn> {
        val firstNonSystemIndex = history.indexOfFirst { it.role != "system" }
            .let { index -> if (index >= 0) index else history.size }
        return buildList(history.size + 1) {
            addAll(history.take(firstNonSystemIndex))
            add(
                ChatTurn(
                    role = "system",
                    content = """
                        You must evaluate your confidence in the final answer.
                        Return only valid JSON with exactly two string fields:
                        {"status":"SURE|UNSURE|FAIL","answer":"<final answer>"}
                        Use:
                        - SURE when the answer is reliable and directly supported.
                        - UNSURE when the answer may be incomplete, ambiguous, or needs verification.
                        - FAIL when you cannot answer the request correctly.
                        Do not include markdown fences or any text outside the JSON object.
                    """.trimIndent()
                )
            )
            addAll(history.drop(firstNonSystemIndex))
        }
    }

    private fun loadChatImportPrompts(file: File) {
        runCatching {
            val root = json.parseToJsonElement(file.readText())
            root.jsonArray.mapIndexed { index, element ->
                val prompt = element.jsonPrimitive.contentOrNull?.trim()
                    ?: throw IllegalArgumentException("Item ${index + 1} must be a string.")
                if (prompt.isBlank()) {
                    throw IllegalArgumentException("Item ${index + 1} must not be blank.")
                }
                ChatImportItem(prompt = prompt)
            }
        }.onSuccess { prompts ->
            chatImportItems.clear()
            chatImportItems.addAll(prompts)
            chatImportMetrics = null
            chatImportSummary = if (prompts.isEmpty()) {
                "JSON file does not contain any prompts."
            } else {
                "Loaded ${prompts.size} prompt(s) from ${file.name}."
            }
        }.onFailure { error ->
            chatImportItems.clear()
            chatImportMetrics = null
            chatImportSummary = "Failed to load import prompts."
            dialogMessage = error.message ?: "Failed to parse import prompts."
        }
    }

    private fun loadSupportTriageRequests(file: File) {
        runCatching {
            val root = json.parseToJsonElement(file.readText())
            root.jsonArray.mapIndexed { index, element ->
                val request = element.jsonPrimitive.contentOrNull?.trim()
                    ?: throw IllegalArgumentException("Item ${index + 1} must be a string.")
                if (request.isBlank()) {
                    throw IllegalArgumentException("Item ${index + 1} must not be blank.")
                }
                request
            }
        }.onSuccess { requests ->
            supportTriageInput = requests.joinToString(separator = "\n")
            supportTriageItems.clear()
            supportTriageMonolithicMetrics = null
            supportTriageMultiStageMetrics = null
            supportTriageLogs.clear()
            supportTriageSummary = if (requests.isEmpty()) {
                "JSON file does not contain any support requests."
            } else {
                "Loaded ${requests.size} support request(s) from ${file.name}."
            }
        }.onFailure { error ->
            supportTriageSummary = "Failed to load support requests."
            dialogMessage = error.message ?: "Failed to parse support requests."
        }
    }

    private fun buildChatImportMetrics(): ChatImportMetrics {
        val statusCounts = chatImportItems.mapNotNull { it.confidenceStatus }
            .groupingBy { it }
            .eachCount()
        val errorCount = chatImportItems.count { it.error != null }
        val successfulCount = if (isConfidenceEnabled) {
            statusCounts[ConfidenceStatus.SURE] ?: 0
        } else {
            chatImportItems.count { it.error == null && it.response != null }
        }
        val unsuccessfulCount = if (isConfidenceEnabled) {
            chatImportItems.size - successfulCount
        } else {
            errorCount
        }
        return ChatImportMetrics(
            totalRequests = chatImportItems.size,
            successfulCount = successfulCount,
            unsuccessfulCount = unsuccessfulCount,
            inferenceCount = chatImportItems.sumOf { it.inferenceCount },
            strongModelInferenceCount = chatImportItems.sumOf { it.strongModelInferenceCount },
            inputTokens = chatImportItems.sumOf { it.inputTokens },
            outputTokens = chatImportItems.sumOf { it.outputTokens },
            statusCounts = statusCounts,
            errorCount = errorCount,
        )
    }

    private fun loadBatchDataset(file: File) {
        runCatching {
            BatchDatasetIO.parse(file)
        }.onSuccess { entries ->
            batchItems.clear()
            batchItems.addAll(entries.map { it.toUiItem() })
            batchSummary = if (entries.isEmpty()) {
                "Dataset does not contain any prompts."
            } else {
                "Loaded ${entries.size} prompt(s) from ${file.name}."
            }
        }.onFailure { error ->
            batchItems.clear()
            batchSummary = "Failed to load dataset."
            dialogMessage = error.message ?: "Failed to parse dataset."
        }
    }

    private fun isTerminal(status: String): Boolean {
        return status.equals("succeeded", ignoreCase = true) ||
            status.equals("failed", ignoreCase = true) ||
            status.equals("cancelled", ignoreCase = true)
    }

    private fun chooseJsonlFile(currentPath: String): File? {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("JSONL dataset", "jsonl")
            val currentFile = currentPath.takeIf { it.isNotBlank() }?.let(::File)
            currentDirectory = when {
                currentFile == null -> currentDirectory
                currentFile.isDirectory -> currentFile
                currentFile.parentFile?.exists() == true -> currentFile.parentFile
                else -> currentDirectory
            }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }

    private fun chooseJsonFile(currentPath: String): File? {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("JSON file", "json")
            val currentFile = currentPath.takeIf { it.isNotBlank() }?.let(::File)
            currentDirectory = when {
                currentFile == null -> currentDirectory
                currentFile.isDirectory -> currentFile
                currentFile.parentFile?.exists() == true -> currentFile.parentFile
                else -> currentDirectory
            }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }

    private fun chooseJsonExportFile(currentPath: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export batch result"
            fileFilter = FileNameExtensionFilter("JSON file", "json")
            val currentFile = currentPath.takeIf { it.isNotBlank() }?.let(::File)
            currentDirectory = when {
                currentFile == null -> currentDirectory
                currentFile.isDirectory -> currentFile
                currentFile.parentFile?.exists() == true -> currentFile.parentFile
                else -> currentDirectory
            }
            selectedFile = File(currentDirectory, "batch-results.json")
        }

        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null
        }

        val selected = chooser.selectedFile
        return if (selected.extension.equals("json", ignoreCase = true)) {
            selected
        } else {
            File(selected.absolutePath + ".json")
        }
    }

    private fun BatchDatasetEntry.toUiItem(): BatchPromptItem {
        return BatchPromptItem(
            lineNumber = lineNumber,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            expectedAssistant = expectedAssistant,
        )
    }

}

@Composable
fun FineTuneDesktopApp() {
    val state = remember { DesktopClientState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(state) {
        onDispose(state::dispose)
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0B6E4F),
            secondary = Color(0xFF355070),
            tertiary = Color(0xFFB56576),
            surfaceVariant = Color(0xFFF1F5F2),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "FineTune Desktop Client",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TabRow(selectedTabIndex = state.selectedTab.ordinal) {
                        DesktopTab.entries.forEach { tab ->
                            Tab(
                                selected = state.selectedTab == tab,
                                onClick = { state.selectedTab = tab },
                                text = { Text(tab.title) },
                            )
                        }
                    }
                    when (state.selectedTab) {
                        DesktopTab.CHAT -> ChatScreen(state = state, scope = scope)
                        DesktopTab.BATCH -> BatchRunScreen(state = state, scope = scope)
                        DesktopTab.SUPPORT_TRIAGE -> SupportTriageScreen(state = state, scope = scope)
                        DesktopTab.FINE_TUNE -> FineTuneScreen(state = state, scope = scope)
                        DesktopTab.VALIDATE -> ValidationScreen(state = state, scope = scope)
                    }
                }
            }
        }

        val dialogMessage = state.dialogMessage
        if (dialogMessage != null) {
            AlertDialog(
                onDismissRequest = { state.dialogMessage = null },
                confirmButton = {
                    TextButton(onClick = { state.dialogMessage = null }) {
                        Text("OK")
                    }
                },
                title = { Text("Input error") },
                text = { Text(dialogMessage) },
            )
        }
    }
}

@Composable
private fun ChatScreen(
    state: DesktopClientState,
    scope: CoroutineScope,
) {
    val transcriptState = rememberLazyListState()
    val pageScrollState = rememberScrollState()
    var conversationFraction by remember { mutableStateOf(0.62f) }

    androidx.compose.runtime.LaunchedEffect(
        state.chatMessages.size,
        state.chatMessages.lastOrNull()?.content,
    ) {
        if (state.chatMessages.isNotEmpty()) {
            transcriptState.animateScrollToItem(state.chatMessages.lastIndex)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val chatWorkspaceHeight = maxHeight

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(pageScrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        SectionCard(
            title = "Settings",
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = { state.systemPrompt = it },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                enabled = !state.isChatLoading,
                label = { Text("System Prompt") },
            )
            OutlinedTextField(
                value = state.chatTemperature,
                onValueChange = { state.chatTemperature = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isChatLoading,
                label = { Text("Temperature") },
                singleLine = true,
                supportingText = {
                    Text("Range: 0.0 to 2.0")
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.isConfidenceEnabled,
                    onCheckedChange = { state.isConfidenceEnabled = it },
                    enabled = !state.isChatLoading,
                )
                Text("Оценка уверенности")
            }
            if (state.isConfidenceEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConfidenceMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier.selectable(
                                selected = state.selectedConfidenceMode == mode,
                                enabled = !state.isChatLoading,
                                onClick = { state.selectedConfidenceMode = mode },
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.selectedConfidenceMode == mode,
                                onClick = { state.selectedConfidenceMode = mode },
                                enabled = !state.isChatLoading,
                            )
                            Text(mode.title)
                        }
                    }
                }
            }
            if (state.isConfidenceEnabled) {
                ModelSelectorField(
                    label = "Weak model",
                    selectedModel = state.selectedWeakModel(),
                    options = state.availableModelOptions,
                    enabled = !state.isChatLoading && !state.isRefreshingModels,
                    onSelect = { state.selectedWeakModelKey = it.key },
                )
                ModelSelectorField(
                    label = "Strong model",
                    selectedModel = state.selectedStrongModel(),
                    options = state.availableModelOptions,
                    enabled = !state.isChatLoading && !state.isRefreshingModels,
                    onSelect = { state.selectedStrongModelKey = it.key },
                )
                if (state.selectedConfidenceMode == ConfidenceMode.SCORING) {
                    ScoringEnumValidationBlock(
                        values = state.scoringEnumOptions,
                        enabled = !state.isChatLoading,
                        onAdd = state::addScoringEnumOption,
                        onValueChange = state::updateScoringEnumOption,
                    )
                }
            } else {
                ModelSelectorField(
                    label = "Request model",
                    selectedModel = state.selectedChatModel(),
                    options = state.availableModelOptions,
                    enabled = !state.isChatLoading && !state.isRefreshingModels,
                    onSelect = { state.selectedChatModelKey = it.key },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { state.refreshOllamaModels(scope) },
                    enabled = !state.isChatLoading && !state.isRefreshingModels,
                ) {
                    if (state.isRefreshingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Refresh Ollama models")
                }
                Text(
                    text = state.modelCatalogStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatRunMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.selectable(
                        selected = state.chatRunMode == mode,
                        enabled = !state.isChatLoading,
                        onClick = { state.chatRunMode = mode },
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.chatRunMode == mode,
                        onClick = { state.chatRunMode = mode },
                        enabled = !state.isChatLoading,
                    )
                    Text(mode.title)
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(chatWorkspaceHeight),
        ) {
            val density = LocalDensity.current
            val dividerHeight = 14.dp
            val minPaneHeight = 180.dp
            val totalHeightPx = with(density) { maxHeight.toPx() }
            val dividerHeightPx = with(density) { dividerHeight.toPx() }
            val minPaneHeightPx = with(density) { minPaneHeight.toPx() }
            val availablePaneHeightPx = (totalHeightPx - dividerHeightPx).coerceAtLeast(minPaneHeightPx * 2)
            val clampedConversationFraction = conversationFraction.coerceIn(
                minimumValue = minPaneHeightPx / availablePaneHeightPx,
                maximumValue = 1f - (minPaneHeightPx / availablePaneHeightPx),
            )
            if (clampedConversationFraction != conversationFraction) {
                conversationFraction = clampedConversationFraction
            }
            val conversationHeight = with(density) { (availablePaneHeightPx * conversationFraction).toDp() }
            val bottomHeight = with(density) { (availablePaneHeightPx * (1f - conversationFraction)).toDp() }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                SectionCard(
                    title = "Conversation",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(conversationHeight),
                ) {
                    if (state.chatMessages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Conversation is empty.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = transcriptState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(state.chatMessages) { _, message ->
                                MessageBubble(
                                    message = message,
                                    modifier = Modifier,
                                )
                            }
                        }
                    }
                }

                ChatPaneResizer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dividerHeight)
                        .pointerInput(totalHeightPx) {
                            detectVerticalDragGestures { _, dragAmount ->
                                val deltaFraction = dragAmount / availablePaneHeightPx
                                conversationFraction = (conversationFraction + deltaFraction).coerceIn(
                                    minimumValue = minPaneHeightPx / availablePaneHeightPx,
                                    maximumValue = 1f - (minPaneHeightPx / availablePaneHeightPx),
                                )
                            }
                        },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomHeight),
                ) {
                    if (state.chatRunMode == ChatRunMode.MANUAL) {
                        ManualChatPane(state = state, scope = scope)
                    } else {
                        ImportChatPane(state = state, scope = scope)
                    }
                }
            }
        }
    }
}
}

@Composable
private fun ManualChatPane(
    state: DesktopClientState,
    scope: CoroutineScope,
) {
    SectionCard(
        title = "Prompt",
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isChatLoading) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Waiting for model response...")
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            OutlinedTextField(
                value = state.prompt,
                onValueChange = { state.prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .chatInputKeyHandler(
                        canSend = !state.isChatLoading,
                        onSend = { state.sendChat(scope) },
                        onInsertNewLine = {
                            state.prompt = buildString(state.prompt.length + 1) {
                                append(state.prompt)
                                append('\n')
                            }
                        },
                    ),
                enabled = !state.isChatLoading,
                label = { Text("Message") },
            )
            Text(
                text = "Enter to send, Ctrl+Enter for a new line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = state::clearChat,
                    enabled = !state.isChatLoading,
                ) {
                    Text("Clear chat")
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(
                    onClick = { state.sendChat(scope) },
                    enabled = !state.isChatLoading,
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ImportChatPane(
    state: DesktopClientState,
    scope: CoroutineScope,
) {
    SectionCard(
        title = "Import",
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isChatLoading) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Running imported prompts...")
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            FilePickerField(
                label = "Prompts JSON",
                path = state.chatImportPath,
                onBrowse = state::browseChatImportFile,
                enabled = !state.isChatLoading,
                buttonLabel = "Select JSON",
            )
            Text(
                text = state.chatImportSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.chatImportMetrics?.let { metrics ->
                ImportMetricsBlock(
                    metrics = metrics,
                    confidenceEnabled = state.isConfidenceEnabled,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = state::clearChat,
                    enabled = !state.isChatLoading,
                ) {
                    Text("Clear chat")
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(
                    onClick = { state.runImportedChat(scope) },
                    enabled = state.chatImportItems.isNotEmpty() && !state.isChatLoading,
                ) {
                    Text("Run import")
                }
            }
        }
    }
}

@Composable
private fun ChatPaneResizer(
    modifier: Modifier = Modifier,
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        ) {
            val y = size.height / 2f
            drawLine(
                color = outlineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceVariantColor),
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(
                text = "Drag to resize",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariantColor,
            )
        }
    }
}

@Composable
private fun BatchRunScreen(
    state: DesktopClientState,
    scope: CoroutineScope,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(
            title = "Dataset",
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilePickerField(
                label = "Dataset (.jsonl)",
                path = state.batchDatasetPath,
                onBrowse = state::browseBatchDataset,
                enabled = !state.isBatchRunning,
            )
            Text(
                text = state.batchSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Selected: ${state.selectedBatchCount()} of ${state.batchItems.size}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        SectionCard(
            title = "Prompts and Responses",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (state.batchItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No prompts loaded.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    itemsIndexed(state.batchItems) { index, item ->
                        BatchPromptCard(
                            item = item,
                            enabled = !state.isBatchRunning,
                            onCheckedChange = { state.toggleBatchSelection(index, it) },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isBatchRunning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(10.dp))
            }
            TextButton(
                onClick = state::exportBatchResults,
                enabled = state.batchItems.isNotEmpty() && !state.isBatchRunning,
            ) {
                Text("Export JSON")
            }
            Spacer(modifier = Modifier.size(8.dp))
            Button(
                onClick = { state.sendBatch(scope) },
                enabled = state.batchItems.isNotEmpty() && !state.isBatchRunning,
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun BatchPromptCard(
    item: BatchPromptItem,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Line ${item.lineNumber}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = item.userPrompt,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "System: ${item.systemPrompt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.isRunning) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Sending request...")
                }
            }

            item.response?.let { response ->
                BatchResultBlock(
                    title = "Response",
                    content = response,
                    color = Color(0xFFE8F4EE),
                )
            }

            item.error?.let { error ->
                BatchResultBlock(
                    title = "Error",
                    content = error,
                    color = Color(0xFFFDECEC),
                )
            }
        }
    }
}

@Composable
private fun BatchResultBlock(
    title: String,
    content: String,
    color: Color,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(text = content)
            }
        }
    }
}

@Composable
private fun SupportTriageScreen(
    state: DesktopClientState,
    scope: CoroutineScope,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(
            title = "Multi-stage support triage",
            modifier = Modifier.fillMaxWidth(),
        ) {
            ModelSelectorField(
                label = "Request model",
                selectedModel = state.selectedChatModel(),
                options = state.availableModelOptions,
                enabled = !state.isSupportTriageRunning && !state.isRefreshingModels,
                onSelect = { state.selectedChatModelKey = it.key },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { state.refreshOllamaModels(scope) },
                    enabled = !state.isSupportTriageRunning && !state.isRefreshingModels,
                ) {
                    if (state.isRefreshingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Refresh Ollama models")
                }
                Text(
                    text = state.modelCatalogStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilePickerField(
                label = "Requests JSON",
                path = state.supportTriageJsonPath,
                onBrowse = state::browseSupportTriageJsonFile,
                enabled = !state.isSupportTriageRunning,
                buttonLabel = "Import JSON",
            )
            OutlinedTextField(
                value = state.supportTriageInput,
                onValueChange = { state.supportTriageInput = it },
                modifier = Modifier.fillMaxWidth().height(190.dp),
                enabled = !state.isSupportTriageRunning,
                label = { Text("Support requests, one per line") },
            )
            Text(
                text = state.supportTriageSummary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isSupportTriageRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(10.dp))
                    OutlinedButton(
                        onClick = state::stopSupportTriage,
                        enabled = true,
                    ) {
                        Text("Stop")
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Button(
                    onClick = { state.runSupportTriage(scope) },
                    enabled = !state.isSupportTriageRunning,
                ) {
                    Text("Run comparison")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.supportTriageMonolithicMetrics?.let { metrics ->
                SupportTriageMetricsCard(
                    title = "Monolithic metrics",
                    metrics = metrics,
                    modifier = Modifier.weight(1f),
                )
            }
            state.supportTriageMultiStageMetrics?.let { metrics ->
                SupportTriageMetricsCard(
                    title = "Multi-stage metrics",
                    metrics = metrics,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard(
            title = "Comparison results",
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp),
        ) {
            if (state.supportTriageItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No support triage runs yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    itemsIndexed(state.supportTriageItems) { index, item ->
                        SupportTriageComparisonCard(index = index + 1, item = item)
                    }
                }
            }
        }

        SectionCard(
            title = "Stage log",
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        ) {
            LogPanel(
                lines = state.supportTriageLogs,
                placeholder = "No support triage logs yet.",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SupportTriageMetricsCard(
    title: String,
    metrics: SupportTriageMetrics,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("Total requests: ${metrics.totalRequests}")
            Text("Successful: ${metrics.successful}")
            Text("Unsuccessful: ${metrics.unsuccessful}")
            Text("Model inferences: ${metrics.modelInferences}")
            Text("Failed stages: ${metrics.failedStages}")
            Text("Parsing errors: ${metrics.parsingErrors}")
            Text("Tokens: input=${metrics.inputTokens}, output=${metrics.outputTokens}")
            Text("Latency: avg=${metrics.averageLatencyMs} ms, total=${metrics.totalLatencyMs} ms")
            Text("Status counts: OK=${metrics.okCount}, UNSURE=${metrics.unsureCount}, FAIL=${metrics.failCount}")
            if (metrics.stage1Failures + metrics.stage2Failures + metrics.stage3Failures > 0 ||
                metrics.averageStage1LatencyMs + metrics.averageStage2LatencyMs + metrics.averageStage3LatencyMs > 0
            ) {
                Text("Stage failures: s1=${metrics.stage1Failures}, s2=${metrics.stage2Failures}, s3=${metrics.stage3Failures}")
                Text("Stage avg latency: s1=${metrics.averageStage1LatencyMs} ms, s2=${metrics.averageStage2LatencyMs} ms, s3=${metrics.averageStage3LatencyMs} ms")
            }
        }
    }
}

@Composable
private fun SupportTriageComparisonCard(
    index: Int,
    item: SupportTriageComparisonItem,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Request $index",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer {
                Text(text = item.input, style = MaterialTheme.typography.bodyLarge)
            }
            if (item.isRunning) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Running comparison...")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SupportTriageResultBlock(
                    title = "Monolithic",
                    result = item.monolithic,
                    modifier = Modifier.weight(1f),
                )
                SupportTriageResultBlock(
                    title = "Multi-stage",
                    result = item.multiStage,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SupportTriageResultBlock(
    title: String,
    result: SupportTriageRunResult?,
    modifier: Modifier = Modifier,
) {
    val status = result?.finalResult?.status
    val color = when (status) {
        SupportStatus.OK -> Color(0xFFE8F4EE)
        SupportStatus.UNSURE -> Color(0xFFFFF3CD)
        SupportStatus.FAIL, null -> Color(0xFFFDECEC)
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (result == null) {
                Text("Not run yet.")
            } else {
                Text("Latency: ${result.totalLatencyMs} ms; inferences: ${result.modelInferences}")
                Text("Tokens: input=${result.usage.inputTokens}, output=${result.usage.outputTokens}")
                Text("Failed stages: ${result.stages.count { !it.valid }}; parsing errors: ${result.parsingErrors}")
                SelectionContainer {
                    Text(result.finalResult?.toJsonString() ?: "FAIL")
                }
            }
        }
    }
}

@Composable
private fun FineTuneScreen(
    state: DesktopClientState,
    scope: CoroutineScope,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(
            title = "Settings",
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilePickerField(
                label = "Dataset (.jsonl)",
                path = state.fineTuneDatasetPath,
                onBrowse = state::browseFineTuneDataset,
                enabled = true,
            )
        }

        SectionCard(
            title = "Fine-tune Status",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LogPanel(
                lines = state.fineTuneLogs,
                placeholder = "No fine-tune activity yet.",
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { state.startFineTune(scope) },
                enabled = !state.isFineTuneRunning,
            ) {
                if (state.isFineTuneRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text("Upload and start fine-tune")
            }
        }
    }
}

@Composable
private fun ValidationScreen(
    state: DesktopClientState,
    scope: CoroutineScope,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(
            title = "Settings",
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilePickerField(
                label = "Dataset (.jsonl)",
                path = state.validationDatasetPath,
                onBrowse = state::browseValidationDataset,
                enabled = !state.isValidationRunning,
            )
        }

        SectionCard(
            title = "Status",
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(indicator = state.validationIndicator)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.validationSummary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = validationSummaryColor(state.validationIndicator),
                    )
                    if (state.isValidationRunning) {
                        Text(
                            text = "Validation is running.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard(
            title = "Validation Result",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LogPanel(
                lines = state.validationDetails.takeIf(String::isNotBlank)?.lines().orEmpty(),
                placeholder = "No validation output yet.",
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { state.validateDataset(scope) },
                enabled = !state.isValidationRunning,
            ) {
                if (state.isValidationRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text("Validate dataset")
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun MessageBubble(
    message: TranscriptMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == TranscriptRole.USER
    val rowAlignment = if (isUser) Arrangement.End else Arrangement.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else if (message.usedStrongModel) {
        Color(0xFFFFF3CD)
    } else {
        Color.White
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val speakerColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val borderColor = if (!isUser && message.usedStrongModel) {
        Color(0xFFE0A800)
    } else {
        Color.Transparent
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 20.dp,
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = rowAlignment,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .border(width = 2.dp, color = borderColor, shape = bubbleShape),
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = bubbleShape,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                Text(
                    text = message.speaker,
                    style = MaterialTheme.typography.labelLarge,
                    color = speakerColor,
                    fontWeight = FontWeight.Bold,
                    textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                )
                if (message.usedStrongModel) {
                    Text(
                        text = "Strong model: ${message.strongModelLabel ?: AppConfig.strongChatModel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isUser) contentColor.copy(alpha = 0.8f) else Color(0xFF9A6700),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (message.auxiliaryLines.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                    ) {
                        message.auxiliaryLines.forEachIndexed { index, line ->
                            Text(
                                text = "${index + 1}. $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.72f),
                                textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                            )
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        text = message.content.ifBlank { "..." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                    )
                }
                message.confidenceStatus?.let { status ->
                    Text(
                        text = "Status: ${status.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                        } else {
                            confidenceStatusColor(status)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSelectorField(
    label: String,
    selectedModel: ModelOption,
    options: List<ModelOption>,
    enabled: Boolean,
    onSelect: (ModelOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedModel.label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.name)
                                Text(
                                    text = option.provider.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoringEnumValidationBlock(
    values: List<String>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onValueChange: (Int, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Enum validation",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = onAdd,
                enabled = enabled,
            ) {
                Text("+")
            }
        }
        values.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(index, it) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Enum value ${index + 1}") },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun FilePickerField(
    label: String,
    path: String,
    onBrowse: () -> Unit,
    enabled: Boolean,
    buttonLabel: String = "Select JSONL",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = path,
            onValueChange = {},
            modifier = Modifier.weight(1f),
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            maxLines = 1,
            supportingText = {
                if (path.isNotBlank()) {
                    Text(
                        text = path,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
        Button(
            onClick = onBrowse,
            enabled = enabled,
        ) {
            Text(buttonLabel)
        }
    }
}

@Composable
private fun ImportMetricsBlock(
    metrics: ChatImportMetrics,
    confidenceEnabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Metrics",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Total requests: ${metrics.totalRequests}")
            Text("Successful: ${metrics.successfulCount}")
            Text("Unsuccessful: ${metrics.unsuccessfulCount}")
            Text("Model inferences: ${metrics.inferenceCount}")
            Text("Strong model requests: ${metrics.strongModelInferenceCount}")
            Text("Input tokens: ${metrics.inputTokens}")
            Text("Output tokens: ${metrics.outputTokens}")
            if (confidenceEnabled && metrics.statusCounts.isNotEmpty()) {
                ConfidenceStatus.entries.forEach { status ->
                    val count = metrics.statusCounts[status] ?: 0
                    Text("${status.name}: $count")
                }
            }
            if (metrics.errorCount > 0) {
                Text("Errors: ${metrics.errorCount}")
            }
        }
    }
}

@Composable
private fun LogPanel(
    lines: List<String>,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp),
            )
            .background(Color.White, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(text = line, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun validationSummaryColor(indicator: ValidationIndicator): Color {
    return when (indicator) {
        ValidationIndicator.SUCCESS -> Color(0xFF1F8A3B)
        ValidationIndicator.ERROR -> Color(0xFFC62828)
        ValidationIndicator.LOADING -> Color(0xFF8A6D1D)
        ValidationIndicator.IDLE -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun confidenceStatusColor(status: ConfidenceStatus): Color {
    return when (status) {
        ConfidenceStatus.SURE -> Color(0xFF1F8A3B)
        ConfidenceStatus.UNSURE -> Color(0xFF8A6D1D)
        ConfidenceStatus.FAIL -> Color(0xFFC62828)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.chatInputKeyHandler(
    canSend: Boolean,
    onSend: () -> Unit,
    onInsertNewLine: () -> Unit,
): Modifier {
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) {
            return@onPreviewKeyEvent false
        }

        if (event.isCtrlPressed) {
            onInsertNewLine()
            true
        } else if (canSend) {
            onSend()
            true
        } else {
            true
        }
    }
}

@Composable
private fun StatusDot(indicator: ValidationIndicator) {
    val color = when (indicator) {
        ValidationIndicator.IDLE -> MaterialTheme.colorScheme.outline
        ValidationIndicator.LOADING -> Color(0xFF9A6A00)
        ValidationIndicator.SUCCESS -> Color(0xFF1F8A3B)
        ValidationIndicator.ERROR -> Color(0xFFC62828)
    }

    Canvas(
        modifier = Modifier
            .size(32.dp),
    ) {
        drawCircle(color = color)
        when (indicator) {
            ValidationIndicator.SUCCESS -> {
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.26f, size.height * 0.53f),
                    end = Offset(size.width * 0.43f, size.height * 0.72f),
                    strokeWidth = 3.2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.43f, size.height * 0.72f),
                    end = Offset(size.width * 0.75f, size.height * 0.31f),
                    strokeWidth = 3.2f,
                    cap = StrokeCap.Round,
                )
            }

            ValidationIndicator.ERROR -> {
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.31f, size.height * 0.31f),
                    end = Offset(size.width * 0.69f, size.height * 0.69f),
                    strokeWidth = 3.2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.69f, size.height * 0.31f),
                    end = Offset(size.width * 0.31f, size.height * 0.69f),
                    strokeWidth = 3.2f,
                    cap = StrokeCap.Round,
                )
            }

            ValidationIndicator.LOADING -> {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 280f,
                    useCenter = false,
                    style = Stroke(width = 3.2f, cap = StrokeCap.Round),
                )
            }

            ValidationIndicator.IDLE -> Unit
        }
    }
}
