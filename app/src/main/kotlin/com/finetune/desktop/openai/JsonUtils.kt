package com.finetune.desktop.openai

private val unicodeRegex = Regex("""\\u([0-9a-fA-F]{4})""")

fun escapeJson(value: String): String = buildString(value.length + 16) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20) {
                    append("\\u%04x".format(char.code))
                } else {
                    append(char)
                }
            }
        }
    }
}

fun unescapeJson(value: String): String {
    val builder = StringBuilder(value)
    builder.replace("\\/", "/")
    builder.replace("\\\"", "\"")
    builder.replace("\\\\", "\\")
    builder.replace("\\b", "\b")
    builder.replace("\\f", "\u000C")
    builder.replace("\\n", "\n")
    builder.replace("\\r", "\r")
    builder.replace("\\t", "\t")
    return unicodeRegex.replace(builder.toString()) {
        it.groupValues[1].toInt(16).toChar().toString()
    }
}

private fun StringBuilder.replace(oldValue: String, newValue: String) {
    var startIndex = indexOf(oldValue)
    while (startIndex >= 0) {
        replace(startIndex, startIndex + oldValue.length, newValue)
        startIndex = indexOf(oldValue, startIndex + newValue.length)
    }
}

fun extractJsonString(json: String, key: String): String? {
    val regex = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""", setOf(RegexOption.DOT_MATCHES_ALL))
    return regex.find(json)?.groupValues?.get(1)?.let(::unescapeJson)
}

fun extractJsonNullableString(json: String, key: String): String? {
    val nullRegex = Regex(""""$key"\s*:\s*null""")
    if (nullRegex.containsMatchIn(json)) {
        return null
    }
    return extractJsonString(json, key)
}

fun extractJsonObject(json: String, key: String): String? {
    val keyIndex = json.indexOf(""""$key"""")
    if (keyIndex < 0) return null
    val objectStart = json.indexOf('{', keyIndex)
    if (objectStart < 0) return null

    var depth = 0
    var inString = false
    var escaped = false

    for (index in objectStart until json.length) {
        val char = json[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
            continue
        }

        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return json.substring(objectStart, index + 1)
                }
            }
        }
    }

    return null
}
