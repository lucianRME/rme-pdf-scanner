package org.synapseworks.pageharbor.backup.format

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface JsonValue

internal data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue

internal data class JsonArray(val values: List<JsonValue>) : JsonValue

internal data class JsonString(val value: String) : JsonValue

internal data class JsonNumber(val token: String) : JsonValue

internal data class JsonBoolean(val value: Boolean) : JsonValue

internal data object JsonNull : JsonValue

internal fun parseJsonObject(text: String, limits: BackupFormatLimits): JsonObject {
    val value = StrictJsonParser(text, limits).parse()
    return value as? JsonObject ?: invalidJson("The JSON root must be an object.")
}

internal fun decodeStrictUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (exception: Exception) {
    throw backupFailure(
        BackupFormatFailure.INVALID_JSON,
        "Backup metadata is not valid UTF-8.",
        exception,
    )
}

private class StrictJsonParser(
    private val source: String,
    private val limits: BackupFormatLimits,
) {
    private var index = 0
    private var tokenCount = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val result = parseValue(1)
        skipWhitespace()
        if (index != source.length) invalidJson("Unexpected trailing JSON data.")
        return result
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > limits.maximumJsonDepth) {
            throw backupFailure(
                BackupFormatFailure.LIMIT_EXCEEDED,
                "JSON nesting exceeds the configured limit.",
            )
        }
        tokenCount += 1
        if (tokenCount > limits.maximumJsonTokens) {
            throw backupFailure(
                BackupFormatFailure.LIMIT_EXCEEDED,
                "JSON token count exceeds the configured limit.",
            )
        }
        if (index >= source.length) invalidJson("Unexpected end of JSON.")
        return when (source[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonString(parseString())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNull)
            '-', in '0'..'9' -> JsonNumber(parseNumber())
            else -> invalidJson("Unexpected JSON token.")
        }
    }

    private fun parseObject(depth: Int): JsonObject {
        index += 1
        skipWhitespace()
        if (consume('}')) return JsonObject(emptyMap())
        val fields = LinkedHashMap<String, JsonValue>()
        while (true) {
            if (index >= source.length || source[index] != '"') {
                invalidJson("A JSON object key must be a string.")
            }
            val key = parseString()
            if (fields.containsKey(key)) invalidJson("Duplicate JSON object key.")
            skipWhitespace()
            requireCharacter(':')
            skipWhitespace()
            fields[key] = parseValue(depth + 1)
            skipWhitespace()
            if (consume('}')) break
            requireCharacter(',')
            skipWhitespace()
        }
        return JsonObject(fields)
    }

    private fun parseArray(depth: Int): JsonArray {
        index += 1
        skipWhitespace()
        if (consume(']')) return JsonArray(emptyList())
        val values = ArrayList<JsonValue>()
        while (true) {
            values += parseValue(depth + 1)
            skipWhitespace()
            if (consume(']')) break
            requireCharacter(',')
            skipWhitespace()
        }
        return JsonArray(values)
    }

    private fun parseString(): String {
        requireCharacter('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> {
                    if (result.length > limits.maximumStringCharacters) {
                        throw backupFailure(
                            BackupFormatFailure.LIMIT_EXCEEDED,
                            "A JSON string exceeds the configured limit.",
                        )
                    }
                    return result.toString()
                }

                character == '\\' -> appendEscape(result)
                character.code < 0x20 -> invalidJson("A JSON string contains a control character.")
                character.isHighSurrogate() -> {
                    if (index >= source.length || !source[index].isLowSurrogate()) {
                        invalidJson("A JSON string contains an unpaired surrogate.")
                    }
                    result.append(character)
                    result.append(source[index++])
                }

                character.isLowSurrogate() -> invalidJson("A JSON string contains an unpaired surrogate.")
                else -> result.append(character)
            }
            if (result.length > limits.maximumStringCharacters) {
                throw backupFailure(
                    BackupFormatFailure.LIMIT_EXCEEDED,
                    "A JSON string exceeds the configured limit.",
                )
            }
        }
        invalidJson("Unterminated JSON string.")
    }

    private fun appendEscape(result: StringBuilder) {
        if (index >= source.length) invalidJson("Unterminated JSON escape.")
        when (val escaped = source[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(result)
            else -> invalidJson("Invalid JSON escape.")
        }
    }

    private fun appendUnicodeEscape(result: StringBuilder) {
        val first = parseHexCodeUnit()
        when {
            first.isHighSurrogate() -> {
                if (index + 2 > source.length || source[index] != '\\' || source[index + 1] != 'u') {
                    invalidJson("A JSON string contains an unpaired surrogate escape.")
                }
                index += 2
                val second = parseHexCodeUnit()
                if (!second.isLowSurrogate()) {
                    invalidJson("A JSON string contains an unpaired surrogate escape.")
                }
                result.append(first).append(second)
            }

            first.isLowSurrogate() -> invalidJson("A JSON string contains an unpaired surrogate escape.")
            else -> result.append(first)
        }
    }

    private fun parseHexCodeUnit(): Char {
        if (index + 4 > source.length) invalidJson("Incomplete JSON Unicode escape.")
        var value = 0
        repeat(4) {
            val digit = source[index++].digitToIntOrNull(16)
                ?: invalidJson("Invalid JSON Unicode escape.")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun parseNumber(): String {
        val start = index
        if (consume('-') && index >= source.length) invalidJson("Incomplete JSON number.")
        when {
            consume('0') -> {
                if (index < source.length && source[index].isDigit()) {
                    invalidJson("A JSON number has a leading zero.")
                }
            }

            index < source.length && source[index] in '1'..'9' -> {
                index += 1
                while (index < source.length && source[index].isDigit()) index += 1
            }

            else -> invalidJson("Invalid JSON number.")
        }
        if (consume('.')) {
            if (index >= source.length || !source[index].isDigit()) invalidJson("Invalid JSON fraction.")
            while (index < source.length && source[index].isDigit()) index += 1
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index += 1
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index += 1
            if (index >= source.length || !source[index].isDigit()) invalidJson("Invalid JSON exponent.")
            while (index < source.length && source[index].isDigit()) index += 1
        }
        return source.substring(start, index)
    }

    private fun <T : JsonValue> parseLiteral(expected: String, value: T): T {
        if (!source.regionMatches(index, expected, 0, expected.length)) {
            invalidJson("Invalid JSON literal.")
        }
        index += expected.length
        return value
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
    }

    private fun consume(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) return false
        index += 1
        return true
    }

    private fun requireCharacter(expected: Char) {
        if (!consume(expected)) invalidJson("Missing JSON delimiter.")
    }
}

internal fun JsonObject.requiredObject(name: String): JsonObject =
    fields[name] as? JsonObject ?: invalidJson("A required JSON object is missing or invalid.")

internal fun JsonObject.requiredArray(name: String): JsonArray =
    fields[name] as? JsonArray ?: invalidJson("A required JSON array is missing or invalid.")

internal fun JsonObject.requiredString(name: String): String =
    (fields[name] as? JsonString)?.value ?: invalidJson("A required JSON string is missing or invalid.")

internal fun JsonObject.requiredLong(name: String): Long {
    val token = (fields[name] as? JsonNumber)?.token
        ?: invalidJson("A required JSON integer is missing or invalid.")
    if (!JSON_INTEGER.matches(token)) invalidJson("A required JSON value is not an integer.")
    return token.toLongOrNull() ?: invalidJson("A JSON integer is out of range.")
}

internal fun JsonObject.requiredInt(name: String): Int {
    val value = requiredLong(name)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        invalidJson("A JSON integer is out of range.")
    }
    return value.toInt()
}

internal fun JsonObject.requiredBoolean(name: String): Boolean =
    (fields[name] as? JsonBoolean)?.value ?: invalidJson("A required JSON boolean is missing or invalid.")

internal fun JsonObject.requiredNullableString(name: String): String? = when (val value = fields[name]) {
    is JsonString -> value.value
    JsonNull -> null
    else -> invalidJson("A required nullable JSON string is missing or invalid.")
}

internal fun JsonObject.requiredNullableLong(name: String): Long? = when (val value = fields[name]) {
    is JsonNumber -> {
        if (!JSON_INTEGER.matches(value.token)) invalidJson("A nullable JSON value is not an integer.")
        value.token.toLongOrNull() ?: invalidJson("A JSON integer is out of range.")
    }

    JsonNull -> null
    else -> invalidJson("A required nullable JSON integer is missing or invalid.")
}

internal fun JsonObject.optionalNullableInt(name: String): Int? {
    if (!fields.containsKey(name) || fields[name] === JsonNull) return null
    val token = (fields[name] as? JsonNumber)?.token
        ?: invalidJson("An optional JSON integer is invalid.")
    if (!JSON_INTEGER.matches(token)) invalidJson("An optional JSON value is not an integer.")
    val value = token.toLongOrNull() ?: invalidJson("A JSON integer is out of range.")
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        invalidJson("A JSON integer is out of range.")
    }
    return value.toInt()
}

internal fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

internal fun nullableJsonString(value: String?): String = value?.let(::jsonString) ?: "null"

private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\r', '\n')
private val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")

private fun invalidJson(message: String): Nothing = throw backupFailure(
    BackupFormatFailure.INVALID_JSON,
    message,
)
