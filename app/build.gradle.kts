import java.util.Properties
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val generatedConfigDir = layout.buildDirectory.dir("generated/source/localConfig/kotlin")

fun Project.loadLocalProperties(): Properties {
    val propertiesFile = rootProject.file("local.properties")
    if (!propertiesFile.exists()) {
        throw GradleException("Missing local.properties in ${rootProject.projectDir}")
    }

    return Properties().apply {
        propertiesFile.inputStream().use(::load)
    }
}

fun Properties.requiredLocalProperty(key: String): String {
    return getProperty(key)?.trim().orEmpty().ifBlank {
        throw GradleException("Missing required property '$key' in local.properties")
    }
}

fun Properties.optionalLocalProperty(key: String, defaultValue: String): String {
    return getProperty(key)?.trim().orEmpty().ifBlank { defaultValue }
}

val generateLocalConfig by tasks.registering {
    val outputDir = generatedConfigDir
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().file("com/finetune/desktop/AppConfig.kt").asFile
        file.parentFile.mkdirs()
        val localProperties = project.loadLocalProperties()
        val ollamaBaseUrl = localProperties
            .optionalLocalProperty("ollama.base_url", "http://localhost:11434")
            .removeSuffix("/")
        val ollamaOpenAiBaseUrl = localProperties
            .optionalLocalProperty("ollama.openai.base_url", "$ollamaBaseUrl/v1")
            .removeSuffix("/")

        fun escape(value: String): String = buildString(value.length + 16) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

        file.writeText(
            """
            package com.finetune.desktop

            object AppConfig {
                const val apiKey: String = "${escape(localProperties.requiredLocalProperty("openai.api.token"))}"
                const val chatModel: String = "${escape(localProperties.requiredLocalProperty("openai.chat.model"))}"
                const val fineTuneModel: String = "${escape(localProperties.requiredLocalProperty("openai.fine_tune.model"))}"
                const val strongChatModel: String = "${escape(localProperties.optionalLocalProperty("openai.confidence.strong.model", "gpt-4.1"))}"
                const val ollamaBaseUrl: String = "${escape(ollamaBaseUrl)}"
                const val ollamaOpenAiBaseUrl: String = "${escape(ollamaOpenAiBaseUrl)}"
            }
            """.trimIndent()
        )
    }
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generatedConfigDir)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateLocalConfig)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.finetune.desktop.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}
