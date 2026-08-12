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
    private val onTranslationReceived: (originalText: String, translatedText: String, isFinal: Boolean) -> Unit,
    private val onAudioOutputReceived: (ByteArray) -> Unit,
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

    fun connect(url: String, protocolMode: ProtocolMode, targetLang: String = "Chinese (Simplified)", vadSilenceMs: Int = 1000) {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            disconnect()
        }

        currentUrl = url
        currentProtocolMode = protocolMode
        sentChunksCount = 0
        pendingChunks.clear()

        val targetCode = when (targetLang) {
            "Chinese (Simplified)", "中文" -> "zh"
            "English" -> "en"
            "Japanese", "日本語" -> "ja"
            "Korean", "한국어" -> "ko"
            "Spanish", "Español" -> "es"
            "French", "Français" -> "fr"
            "German", "Deutsch" -> "de"
            else -> "zh"
        }

        val finalUrl = if (!url.contains("?")) {
            "$url?source=Auto&target=$targetCode&silenceMs=$vadSilenceMs"
        } else {
            url
        }

        addLog(LogType.SYSTEM, "Network", "Initiating WebSocket connection to $finalUrl (Mode: ${protocolMode.displayName})")
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

    /**
     * Send an explicit Gemini-Realtime-style setup message after [onOpen].
     *
     * Deprecated: the live translation server we target uses the
     * `audioBlob` JSON protocol (matching `desktop-client/src/ws.ts`) and
     * configures itself from URL query parameters. Sending this setup
     * frame to that server is harmless but wasteful, so we no longer call
     * it from [createListener]. Retained here for future re-use if a user
     * switches protocol mode back to GEMINI_REALTIME_JSON and points the
     * app at a Gemini Live API endpoint.
     */
    @Deprecated("Server is configured via URL query parameters; no setup frame is sent.")
    private fun sendInitialSetup(ws: WebSocket, targetLang: String) {
        try {
            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", "models/gemini-2.0-flash-exp")
                    put("generation_config", JSONObject().apply {
                        put("response_modalities", JSONArray().apply { put("TEXT"); put("AUDIO") })
                    })
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "You are a real-time live interpreter. Translate all incoming audio or speech immediately into $targetLang accurately and concisely.")
                            })
                        })
                    })
                })
            }
            ws.send(setupJson.toString())
            addLog(LogType.SENT, "Setup", "Sent live translation setup message ($targetLang)")
        } catch (e: Exception) {
            Log.e("LiveTranslateWS", "Error sending setup message", e)
        }
    }

    private fun parseTextMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            if (root.optString("type") == "usage") {
                val input = root.optLong("inputTokens", 0L).coerceAtLeast(0L)
                val output = root.optLong("outputTokens", 0L).coerceAtLeast(0L)
                onUsageReceived(input, output)
                return
            }

            var originalText = ""
            var translatedText = ""
            var isFinal = false

            // Desktop-client compatible shape (live endpoint):
            if (root.has("originalText") || root.has("translatedText")) {
                originalText = root.optString("originalText", root.optString("original", ""))
                translatedText = root.optString("translatedText", root.optString("translation", ""))
                isFinal = root.optBoolean("finished", root.optBoolean("is_final", true))
            } else if (root.has("original") || root.has("translation")) {
                originalText = root.optString("original", root.optString("text", ""))
                translatedText = root.optString("translation", root.optString("translated", ""))
                isFinal = root.optBoolean("is_final", true)
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
                    isFinal = serverContent.optBoolean("turnComplete", true)
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

            // Embedded Base64 audio if present (desktop-client compatible key
            // is `audio`).
            if (root.has("audio")) {
                val base64Audio = root.getString("audio")
                val decodedBytes = Base64.decode(base64Audio, Base64.NO_WRAP)
                onAudioOutputReceived(decodedBytes)
            }

            if (originalText.isNotBlank() || translatedText.isNotBlank()) {
                onTranslationReceived(originalText, translatedText, isFinal)
            }

        } catch (e: Exception) {
            // Non-JSON text or direct text frame
            if (jsonText.isNotBlank()) {
                onTranslationReceived("", jsonText, true)
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
