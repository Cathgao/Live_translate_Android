package com.example.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class ProtocolMode(val displayName: String, val description: String) {
    GEMINI_REALTIME_JSON("Gemini Realtime API (JSON)", "Sends Base64 PCM audio inside Gemini realtime_input JSON payload"),
    BINARY_PCM("Raw Binary PCM", "Sends raw 16-bit PCM bytes directly over WebSocket binary frames"),
    SIMPLE_BASE64_JSON("Simple Base64 JSON", "Sends JSON object with Base64 audio payload: {\"type\":\"audio\",\"data\":\"...\"}")
}

data class AudioDeviceItem(
    val id: Int,
    val name: String,
    val typeName: String,
    val isUsb: Boolean,
    val isSelected: Boolean = false,
    val supportedSampleRates: String = "16000, 44100 Hz"
)

data class TranslationItem(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val originalText: String,
    val translatedText: String,
    val sourceLang: String = "Auto",
    val targetLang: String = "中文",
    val isFinal: Boolean = true
)

enum class LogType {
    SENT,
    RECEIVED,
    SYSTEM,
    ERROR
}

data class LogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType,
    val tag: String,
    val message: String
)

data class TokenUsage(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L
)
