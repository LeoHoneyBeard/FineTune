package com.finetune.desktop.openai

enum class ModelProvider(val title: String) {
    OPENAI("OpenAI"),
    OLLAMA("Ollama"),
}

data class ModelOption(
    val name: String,
    val provider: ModelProvider,
) {
    val key: String = "${provider.name}:$name"
    val label: String = "$name (${provider.title})"
}
