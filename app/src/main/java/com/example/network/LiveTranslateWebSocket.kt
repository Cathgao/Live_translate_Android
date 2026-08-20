package com.example.network

import android.util.Base64
import android.util.Log
import com.example.model.ConnectionState
import com.example.model.LogEntry
import com.example.model.LogType
import com.example.model.ProtocolMode
import com.example.model.TranslationItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class LiveTranslateWebSocket(
    private val onTranscriptionDelta: (originalDelta: String, translatedDelta: String, isFinished: Boolean) -> Unit,
    private val onTranscriptionFinished: () -> Unit = {},
    private val onTranscriptionInterrupted: () -> Unit = {},
    private val onAudioOutputReceived: (ByteArray) -> Unit = {},
    private val onUsageReceived: (inputTokens: Long, outputTokens: Long) -> Unit = { _, _ -> }
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private var currentUrl: String = ""
    private var currentProtocolMode: ProtocolMode = ProtocolMode.GEMINI_REALTIME_JSON
    private var sentChunksCount = 0

    /** Audio captured while WS is still CONNECTING is parked here. */
    private val pendingChunks = ConcurrentLinkedQueue<PendingChunk>()
    private var bufferedSampleRate = 16000
    private val MAX_BUFFERED_CHUNKS = 200   // ~20s @ 100ms chunks

    private data class PendingChunk(val bytes: ByteArray, val sampleRate: Int)

    fun connect(
        url: String,
        protocolMode: ProtocolMode,
        sourceLang: String = "Auto",
        targetLang: String = "Chinese (Simplified)",
        vadSilenceMs: Int = 1000
    ) {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            disconnect()
        }

        currentUrl = url
        currentProtocolMode = protocolMode
        sentChunksCount = 0
        pendingChunks.clear()

        val normalizedSource = normalizeSourceLanguage(sourceLang)
        val normalizedTarget = normalizeTargetLanguage(targetLang)
        val finalUrl = buildConnectionUrl(url, normalizedSource, normalizedTarget, vadSilenceMs)

        addLog(LogType.SYSTEM, "Network", "Initiating WebSocket connection to $finalUrl (Mode: ${protocolMode.displayName}, Target: $normalizedTarget)")
        _connectionState.value = ConnectionState.CONNECTING

        try {
            val request = Request.Builder()
                .url(finalUrl)
                .build()

            webSocket = client.newWebSocket(request, createListener())
        } catch (e: Exception) {
            addLog(LogType.ERROR, "Network", "Connection initialization failed: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    companion object {
        fun normalizeTargetLanguage(lang: String): String {
            return when (lang.trim()) {
                "Chinese (Simplified)", "中文", "简体中文", "zh", "zh-CN", "zh-cn" -> "Chinese (Simplified)"
                "Chinese (Traditional)", "繁体中文", "zh-TW", "zh-tw", "zh-HK" -> "Chinese (Traditional)"
                "English", "英语", "英文", "en", "en-US", "en-GB" -> "English"
                "Japanese", "日本語", "日语", "ja", "ja-JP" -> "Japanese"
                "Korean", "한국어", "韩语", "ko", "ko-KR" -> "Korean"
                "Spanish", "Español", "西班牙语", "es", "es-ES" -> "Spanish"
                "French", "Français", "法语", "fr", "fr-FR" -> "French"
                "German", "Deutsch", "德语", "de", "de-DE" -> "German"
                "Russian", "Русский", "俄语", "ru" -> "Russian"
                "Portuguese", "Português", "葡萄牙语", "pt" -> "Portuguese"
                "Italian", "Italiano", "意大利语", "it" -> "Italian"
                "Arabic", "العربية", "阿拉伯语", "ar" -> "Arabic"
                "Hindi", "हिन्दी", "印地语", "hi" -> "Hindi"
                "Vietnamese", "Tiếng Việt", "越南语", "vi" -> "Vietnamese"
                "Thai", "ไทย", "泰语", "th" -> "Thai"
                "Polish", "Polski", "波兰语", "pl" -> "Polish"
                else -> lang.trim()
            }
        }

        fun normalizeSourceLanguage(lang: String): String {
            return when (lang.trim()) {
                "Auto", "auto", "自动", "自动检测" -> "Auto"
                "Chinese (Simplified)", "中文", "简体中文", "zh", "zh-CN" -> "Chinese (Simplified)"
                "Chinese (Traditional)", "繁体中文", "zh-TW" -> "Chinese (Traditional)"
                "English", "英语", "en" -> "English"
                "Japanese", "日本語", "日语", "ja" -> "Japanese"
                "Korean", "한국어", "韩语", "ko" -> "Korean"
                "Spanish", "Español", "西班牙语", "es" -> "Spanish"
                "French", "Français", "法语", "fr" -> "French"
                "German", "Deutsch", "德语", "de" -> "German"
                else -> lang.trim()
            }
        }

        fun buildConnectionUrl(
            baseUrl: String,
            sourceLang: String,
            targetLang: String,
            vadSilenceMs: Int
        ): String {
            val cleanUrl = baseUrl.trim()
            if (cleanUrl.isEmpty()) return ""

            val normalizedSource = normalizeSourceLanguage(sourceLang)
            val normalizedTarget = normalizeTargetLanguage(targetLang)

            return try {
                val uri = android.net.Uri.parse(cleanUrl)
                val builder = uri.buildUpon()

                // Preserve existing unrelated query parameters
                val queryNames = uri.queryParameterNames
                if (queryNames.isNotEmpty()) {
                    builder.clearQuery()
                    for (name in queryNames) {
                        if (name !in listOf("source", "target", "silenceMs", "source_lang", "target_lang", "targetLang")) {
                            for (value in uri.getQueryParameters(name)) {
                                builder.appendQueryParameter(name, value)
                            }
                        }
                    }
                }

                builder.appendQueryParameter("source", normalizedSource)
                builder.appendQueryParameter("target", normalizedTarget)
                builder.appendQueryParameter("silenceMs", vadSilenceMs.toString())
                builder.build().toString()
            } catch (e: Exception) {
                val encodedSource = java.net.URLEncoder.encode(normalizedSource, "UTF-8")
                val encodedTarget = java.net.URLEncoder.encode(normalizedTarget, "UTF-8")
                val sep = if (cleanUrl.contains("?")) "&" else "?"
                "$cleanUrl${sep}source=$encodedSource&target=$encodedTarget&silenceMs=$vadSilenceMs"
            }
        }
    }

    fun disconnect() {
        addLog(LogType.SYSTEM, "Network", "Closing WebSocket connection")
        pendingChunks.clear()
        try {
            webSocket?.close(1000, "User requested disconnect")
        } catch (_: Exception) {}
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Send a captured audio chunk. If the socket isn't connected yet, the
     * chunk is queued (oldest dropped past [MAX_BUFFERED_CHUNKS]) and
     * flushed automatically when the connection becomes CONNECTED.
     */
    fun sendAudioChunk(pcmBytes: ByteArray, sampleRate: Int = 16000) {
        val ws = webSocket
        if (_connectionState.value != ConnectionState.CONNECTED || ws == null) {
            pendingChunks.offer(PendingChunk(pcmBytes, sampleRate))
            while (pendingChunks.size > MAX_BUFFERED_CHUNKS) {
                pendingChunks.poll()
            }
            return
        }

        try {
            when (currentProtocolMode) {
                ProtocolMode.BINARY_PCM -> {
                    val byteString = pcmBytes.toByteString()
                    ws.send(byteString)
                }

                ProtocolMode.GEMINI_REALTIME_JSON -> {
                    val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                    val json = JSONObject().apply {
                        put("realtime_input", JSONObject().apply {
                            put("media_chunks", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("mime_type", "audio/pcm;rate=$sampleRate")
                                    put("data", base64Audio)
                                })
                            })
                        })
                    }
                    ws.send(json.toString())
                }

                ProtocolMode.SIMPLE_BASE64_JSON -> {
                    val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                    val json = JSONObject().apply {
                        put("audioBlob", base64Audio)
                        put("mimeType", "audio/pcm;rate=$sampleRate")
                    }
                    ws.send(json.toString())
                }
            }

            sentChunksCount++
            if (sentChunksCount % 20 == 1) {
                addLog(LogType.SENT, "AudioStream", "Sent audio chunk #$sentChunksCount (${pcmBytes.size} bytes)")
            }

        } catch (e: Exception) {
            addLog(LogType.ERROR, "AudioStream", "Error sending audio chunk: ${e.message}")
        }
    }

    fun sendTextMessage(text: String) {
        val ws = webSocket
        if (_connectionState.value != ConnectionState.CONNECTED || ws == null) return

        try {
            ws.send(text)
            addLog(LogType.SENT, "TextCommand", text)
        } catch (e: Exception) {
            addLog(LogType.ERROR, "TextCommand", "Error sending text message: ${e.message}")
        }
    }

    private fun flushPendingChunks() {
        if (pendingChunks.isEmpty()) return
        addLog(LogType.SYSTEM, "AudioStream", "Flushing ${pendingChunks.size} buffered chunks to server")
        var count = 0
        while (true) {
            val next = pendingChunks.poll() ?: break
            sendAudioChunk(next.bytes, next.sampleRate)
            count++
        }
        if (count > 0) {
            addLog(LogType.SYSTEM, "AudioStream", "Flushed $count chunks after CONNECTED")
        }
    }

    private fun createListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            addLog(LogType.SYSTEM, "Network", "WebSocket connection established successfully (HTTP ${response.code})")
            _connectionState.value = ConnectionState.CONNECTED
            // No setup frame: protocol matches desktop client.
            flushPendingChunks()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            addLog(LogType.RECEIVED, "WebSocketText", text.take(300) + if (text.length > 300) "..." else "")
            parseTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val byteArray = bytes.toByteArray()
            addLog(LogType.RECEIVED, "WebSocketBinary", "Received binary frame (${byteArray.size} bytes)")
            onAudioOutputReceived(byteArray)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            addLog(LogType.SYSTEM, "Network", "Server closing connection: $code / $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val errMessage = t.message ?: "Unknown WebSocket error"
            addLog(LogType.ERROR, "Network", "WebSocket failure: $errMessage")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun parseTextMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)
            val msgType = root.optString("type", "")

            if (msgType == "ping") {
                webSocket?.send(JSONObject().apply { put("type", "pong") }.toString())
                return
            }

            if (msgType == "usage") {
                val input = root.optLong("inputTokens", 0L).coerceAtLeast(0L)
                val output = root.optLong("outputTokens", 0L).coerceAtLeast(0L)
                onUsageReceived(input, output)
                return
            }

            if (msgType == "transcription_finished") {
                onTranscriptionFinished()
                return
            }

            if (msgType == "transcription_interrupted") {
                onTranscriptionInterrupted()
                return
            }

            if (msgType == "translation_audio" && root.has("audio")) {
                val base64Audio = root.getString("audio")
                val decodedBytes = Base64.decode(base64Audio, Base64.NO_WRAP)
                onAudioOutputReceived(decodedBytes)
                return
            }

            var originalText = ""
            var translatedText = ""
            var isFinal = false

            // Desktop-client / Web Live-Translate compatible shape (type: transcription or direct keys):
            if (root.has("originalText") || root.has("translatedText")) {
                originalText = root.optString("originalText", root.optString("original", ""))
                translatedText = root.optString("translatedText", root.optString("translation", ""))
                isFinal = root.optBoolean("finished", root.optBoolean("is_final", false))
            } else if (root.has("original") || root.has("translation")) {
                originalText = root.optString("original", root.optString("text", ""))
                translatedText = root.optString("translation", root.optString("translated", ""))
                isFinal = root.optBoolean("is_final", false)
            } else if (root.has("serverContent")) {
                // Gemini Live API serverContent payload format
                val serverContent = root.getJSONObject("serverContent")
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val firstPart = parts.getJSONObject(0)
                        if (firstPart.has("text")) {
                            translatedText = firstPart.getString("text")
                        }
                    }
                }
                if (serverContent.has("turnComplete")) {
                    isFinal = serverContent.optBoolean("turnComplete", false)
                }
            } else if (root.has("candidates")) {
                val candidates = root.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val cand = candidates.getJSONObject(0)
                    val content = cand.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        translatedText = parts.getJSONObject(0).optString("text", "")
                    }
                }
            } else if (root.has("text")) {
                translatedText = root.getString("text")
            } else if (root.has("content")) {
                translatedText = root.getString("content")
            } else if (root.has("transcript")) {
                originalText = root.getString("transcript")
            }

            // Embedded Base64 audio if present
            if (root.has("audio")) {
                val base64Audio = root.getString("audio")
                val decodedBytes = Base64.decode(base64Audio, Base64.NO_WRAP)
                onAudioOutputReceived(decodedBytes)
            }

            if (originalText.isNotEmpty() || translatedText.isNotEmpty()) {
                onTranscriptionDelta(originalText, translatedText, isFinal)
            } else if (isFinal) {
                onTranscriptionFinished()
            }

        } catch (e: Exception) {
            // Non-JSON text or direct text frame
            if (jsonText.isNotBlank()) {
                onTranscriptionDelta("", jsonText, true)
            }
        }
    }

    private fun addLog(type: LogType, tag: String, message: String) {
        val entry = LogEntry(type = type, tag = tag, message = message)
        val current = _logs.value.toMutableList()
        current.add(0, entry) // Newest logs first
        if (current.size > 200) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
