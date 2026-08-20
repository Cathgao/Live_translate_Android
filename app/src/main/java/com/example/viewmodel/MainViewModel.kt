package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioDeviceManager
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.hardware.LedController
import com.example.hardware.SerialPortManager
import com.example.model.AudioDeviceItem
import com.example.model.ConnectionState
import com.example.model.LogEntry
import com.example.model.ProtocolMode
import com.example.model.TokenUsage
import com.example.model.TranslationItem
import com.example.network.LiveTranslateWebSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "live_translate_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_VAD_SILENCE_MS = "vad_silence_ms"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_PROTOCOL_MODE = "protocol_mode"
        private const val KEY_MIC_GAIN = "mic_gain"
        private const val SEGMENT_COMMIT_MS = 5000L
    }

    val audioDeviceManager = AudioDeviceManager(application)
    private val audioRecorder = AudioRecorder(application, audioDeviceManager)
    private val audioPlayer = AudioPlayer()

    // Hardware Serial Port Manager for DWIN / DGUS Screen (/dev/ttyAS5)
    private val serialPortManager = SerialPortManager(
        devicePath = "/dev/ttyAS5",
        onStartButtonPressed = {
            if (!isRecording.value) {
                startRecording()
            }
        },
        onStopButtonPressed = {
            if (isRecording.value) {
                stopRecording()
            }
        }
    )

    private val webSocket = LiveTranslateWebSocket(
        onTranscriptionDelta = { origDelta, transDelta, isFinished ->
            handleTranscriptionDelta(origDelta, transDelta, isFinished)
        },
        onTranscriptionFinished = {
            handleTranscriptionFinished()
        },
        onTranscriptionInterrupted = {
            handleTranscriptionInterrupted()
        },
        onAudioOutputReceived = { pcmBytes ->
            audioPlayer.playPcmChunk(pcmBytes, sampleRate.value)
        },
        onUsageReceived = { input, output ->
            _tokenUsage.value = TokenUsage(inputTokens = input, outputTokens = output)
        }
    )

    // UI States
    val connectionState: StateFlow<ConnectionState> = webSocket.connectionState
    val logs: StateFlow<List<LogEntry>> = webSocket.logs
    val availableDevices: StateFlow<List<AudioDeviceItem>> = audioDeviceManager.availableDevices
    val selectedDevice: StateFlow<AudioDeviceItem?> = audioDeviceManager.selectedDevice
    val isRecording: StateFlow<Boolean> = audioRecorder.isRecording
    val audioVolume: StateFlow<Float> = audioRecorder.audioVolume
    val recordingError: StateFlow<String?> = audioRecorder.recordingError

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _protocolMode = MutableStateFlow(ProtocolMode.SIMPLE_BASE64_JSON)
    val protocolMode: StateFlow<ProtocolMode> = _protocolMode.asStateFlow()

    fun updateProtocolMode(mode: ProtocolMode) {
        _protocolMode.value = mode
    }

    private val _sourceLanguage = MutableStateFlow("Auto")
    val sourceLanguage: StateFlow<String> = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow("Chinese (Simplified)")
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    private val _vadSilenceMs = MutableStateFlow(1000)
    val vadSilenceMs: StateFlow<Int> = _vadSilenceMs.asStateFlow()

    private val _fontSize = MutableStateFlow(25)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _micGain = MutableStateFlow(1.0f)
    val micGain: StateFlow<Float> = _micGain.asStateFlow()

    private val _sampleRate = MutableStateFlow(16000)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    // Dual-layer transcript model matching Cathgao/Live-Translate Web:
    // Base holds committed past sentences, Live holds currently streaming sentence
    private val _originalBase = MutableStateFlow("")
    val originalBase: StateFlow<String> = _originalBase.asStateFlow()

    private val _originalLive = MutableStateFlow("")
    val originalLive: StateFlow<String> = _originalLive.asStateFlow()

    private val _translatedBase = MutableStateFlow("")
    val translatedBase: StateFlow<String> = _translatedBase.asStateFlow()

    private val _translatedLive = MutableStateFlow("")
    val translatedLive: StateFlow<String> = _translatedLive.asStateFlow()

    // Aliases for compatibility
    val liveOriginalText: StateFlow<String> = _originalLive.asStateFlow()
    val liveTranslatedText: StateFlow<String> = _translatedLive.asStateFlow()

    private val _tokenUsage = MutableStateFlow(TokenUsage(0L, 0L))
    val tokenUsage: StateFlow<TokenUsage> = _tokenUsage.asStateFlow()

    private val _showUsbDiagnosticSheet = MutableStateFlow(false)
    val showUsbDiagnosticSheet: StateFlow<Boolean> = _showUsbDiagnosticSheet.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showLogConsole = MutableStateFlow(false)
    val showLogConsole: StateFlow<Boolean> = _showLogConsole.asStateFlow()

    // Internal pending buffers & timers
    private var pendingOrig: String = ""
    private var pendingTrans: String = ""
    private var segmentCommitJob: kotlinx.coroutines.Job? = null

    // Serial port paragraph buffers (accumulated until silence timer timeout)
    private var serialCommittedOrig: String = ""
    private var serialCommittedTrans: String = ""
    private var lastSentOrig: String = ""
    private var lastSentTrans: String = ""

    init {
        // Initialize LED state (Mic OFF: PI15=0, PI12=1)
        LedController.setMicState(false)

        // Start serial communication with screen
        serialPortManager.start()

        // Restore persisted settings so they survive process restarts.
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _serverUrl.value = prefs.getString(KEY_SERVER_URL, "") ?: ""
        _sourceLanguage.value = prefs.getString(KEY_SOURCE_LANG, _sourceLanguage.value) ?: _sourceLanguage.value
        _targetLanguage.value = prefs.getString(KEY_TARGET_LANG, _targetLanguage.value) ?: _targetLanguage.value
        prefs.getInt(KEY_VAD_SILENCE_MS, _vadSilenceMs.value).also {
            if (it in 500..3000) _vadSilenceMs.value = it
        }
        prefs.getInt(KEY_FONT_SIZE, _fontSize.value).also {
            if (it in 8..72) _fontSize.value = it
        }
        _keepScreenOn.value = prefs.getBoolean(KEY_KEEP_SCREEN_ON, _keepScreenOn.value)
        val savedGain = prefs.getFloat(KEY_MIC_GAIN, 1.0f)
        _micGain.value = savedGain
        audioRecorder.setMicGain(savedGain)

        runCatching {
            val raw = prefs.getString(KEY_PROTOCOL_MODE, null)
            if (raw != null) _protocolMode.value = ProtocolMode.valueOf(raw)
        }

        // Monitor audio device changes (e.g. USB mic plugged in or selected) to auto-migrate active recording
        viewModelScope.launch {
            var lastDeviceId = selectedDevice.value?.id
            selectedDevice.collect { current ->
                val currentId = current?.id
                if (lastDeviceId != null && currentId != null && lastDeviceId != currentId) {
                    if (isRecording.value) {
                        audioRecorder.stopRecording()
                        startRecording()
                    }
                }
                lastDeviceId = currentId
            }
        }
    }

    private fun persist() {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
        prefs.putString(KEY_SERVER_URL, _serverUrl.value)
        prefs.putString(KEY_SOURCE_LANG, _sourceLanguage.value)
        prefs.putString(KEY_TARGET_LANG, _targetLanguage.value)
        prefs.putInt(KEY_VAD_SILENCE_MS, _vadSilenceMs.value)
        prefs.putInt(KEY_FONT_SIZE, _fontSize.value)
        prefs.putBoolean(KEY_KEEP_SCREEN_ON, _keepScreenOn.value)
        prefs.putFloat(KEY_MIC_GAIN, _micGain.value)
        prefs.putString(KEY_PROTOCOL_MODE, _protocolMode.value.name)
        prefs.apply()
    }

    fun updateMicGain(gain: Float) {
        val clamped = gain.coerceIn(0.0f, 1.0f)
        _micGain.value = clamped
        audioRecorder.setMicGain(clamped)
        persist()
    }

    fun updateSourceLanguage(lang: String) {
        if (_sourceLanguage.value == lang) return
        _sourceLanguage.value = lang
        persist()
        if (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONNECTING) {
            webSocket.connect(
                url = _serverUrl.value,
                protocolMode = _protocolMode.value,
                sourceLang = _sourceLanguage.value,
                targetLang = _targetLanguage.value,
                vadSilenceMs = _vadSilenceMs.value
            )
        }
    }

    fun updateTargetLanguage(lang: String) {
        if (_targetLanguage.value == lang) return
        _targetLanguage.value = lang
        persist()
        if (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONNECTING) {
            webSocket.connect(
                url = _serverUrl.value,
                protocolMode = _protocolMode.value,
                sourceLang = _sourceLanguage.value,
                targetLang = _targetLanguage.value,
                vadSilenceMs = _vadSilenceMs.value
            )
        }
    }

    fun updateVadSilenceMs(ms: Int) {
        if (_vadSilenceMs.value == ms) return
        _vadSilenceMs.value = ms
        persist()
        if (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONNECTING) {
            webSocket.connect(
                url = _serverUrl.value,
                protocolMode = _protocolMode.value,
                sourceLang = _sourceLanguage.value,
                targetLang = _targetLanguage.value,
                vadSilenceMs = _vadSilenceMs.value
            )
        }
    }

    fun updateFontSize(size: Int) {
        _fontSize.value = size
        persist()
    }

    fun updateKeepScreenOn(keep: Boolean) {
        _keepScreenOn.value = keep
        persist()
    }

    fun updateServerUrl(url: String) {
        val cleaned = url.trim()
        if (_serverUrl.value == cleaned) return
        _serverUrl.value = cleaned
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, cleaned)
            .apply()
        if (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONNECTING) {
            webSocket.connect(
                url = _serverUrl.value,
                protocolMode = _protocolMode.value,
                sourceLang = _sourceLanguage.value,
                targetLang = _targetLanguage.value,
                vadSilenceMs = _vadSilenceMs.value
            )
        }
    }

    fun updateProtocolModePersist(mode: ProtocolMode) {
        _protocolMode.value = mode
        persist()
    }

    fun resetSettings() {
        _vadSilenceMs.value = 1000
        _fontSize.value = 25
        _keepScreenOn.value = true
        _sourceLanguage.value = "Auto"
        persist()
    }

    fun resetTokens() {
        _tokenUsage.value = TokenUsage(0L, 0L)
    }

    fun toggleConnect() {
        if (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONNECTING) {
            if (isRecording.value) {
                stopRecording()
            }
            webSocket.disconnect()
        } else {
            webSocket.connect(
                url = _serverUrl.value,
                protocolMode = _protocolMode.value,
                sourceLang = _sourceLanguage.value,
                targetLang = _targetLanguage.value,
                vadSilenceMs = _vadSilenceMs.value
            )
        }
    }

    fun selectAudioDevice(deviceId: Int) {
        audioDeviceManager.selectDevice(deviceId)
    }

    fun refreshAudioDevices() {
        audioDeviceManager.refreshDevices()
    }

    fun startRecording() {
        if (connectionState.value != ConnectionState.CONNECTED) {
            // Auto connect if not connected
            webSocket.connect(
                url = _serverUrl.value,
                protocolMode = _protocolMode.value,
                sourceLang = _sourceLanguage.value,
                targetLang = _targetLanguage.value,
                vadSilenceMs = _vadSilenceMs.value
            )
        }

        _originalLive.value = ""
        _translatedLive.value = ""
        pendingOrig = ""
        pendingTrans = ""
        serialCommittedOrig = ""
        serialCommittedTrans = ""
        lastSentOrig = ""
        lastSentTrans = ""
        segmentCommitJob?.cancel()
        segmentCommitJob = null

        // Notify screen to switch to start page
        serialPortManager.sendStartPage()

        // Clear both text boxes on screen when starting recording
        serialPortManager.clearAllTextBoxes()

        // Turn on Mic LED (PI15=1, PI12=0)
        LedController.setMicState(true)

        val targetDeviceId = selectedDevice.value?.id
        audioRecorder.startRecording(
            sampleRate = _sampleRate.value,
            targetDeviceId = targetDeviceId
        ) { pcmChunk ->
            webSocket.sendAudioChunk(pcmChunk, _sampleRate.value)
        }
    }

    fun stopRecording() {
        segmentCommitJob?.cancel()
        segmentCommitJob = null
        audioRecorder.stopRecording()
        flushPending("stopRecording")
        // Notify screen to switch to stop page
        serialPortManager.sendStopPage()
        // Turn off Mic LED (PI15=0, PI12=1)
        LedController.setMicState(false)
    }

    fun toggleRecording() {
        if (isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun joinParagraphText(prev: String, next: String): String {
        val p = prev.trim()
        val n = next.trim()
        if (p.isEmpty()) return n
        if (n.isEmpty()) return p
        val lastChar = p.last()
        val isCjk = lastChar.code in 0x4E00..0x9FFF || lastChar in "，。！？；：“”‘’"
        return if (isCjk) "$p$n" else "$p $n"
    }

    private fun sendSerialParagraph(orig: String, trans: String) {
        if (orig.isNotEmpty() && orig != lastSentOrig) {
            lastSentOrig = orig
            serialPortManager.sendOriginalText(orig)
        }
        if (trans.isNotEmpty() && trans != lastSentTrans) {
            lastSentTrans = trans
            serialPortManager.sendTranslatedText(trans)
        }
    }

    private fun handleTranscriptionDelta(origDelta: String, transDelta: String, isFinished: Boolean) {
        viewModelScope.launch {
            if (origDelta.isNotEmpty()) {
                pendingOrig = if (pendingOrig.isEmpty()) origDelta else pendingOrig + origDelta
            }
            if (transDelta.isNotEmpty()) {
                pendingTrans = if (pendingTrans.isEmpty()) transDelta else pendingTrans + transDelta
            }

            val hasMeaningfulDelta = origDelta.trim().isNotEmpty() || transDelta.trim().isNotEmpty()
            if (hasMeaningfulDelta) {
                _originalLive.value = pendingOrig
                _translatedLive.value = pendingTrans
                if (isRecording.value) {
                    armSilenceCommit()
                }

                // Check combined paragraph capacity:
                // Original limit: 1000 bytes (within DWIN 1024B limit), Translated limit: 900 bytes.
                // If either exceeds its limit, commit current text, clear screen text boxes, and start new paragraph.
                val currentFullOrig = joinParagraphText(serialCommittedOrig, pendingOrig)
                val currentFullTrans = joinParagraphText(serialCommittedTrans, pendingTrans)

                val origBytes = currentFullOrig.toByteArray(Charsets.UTF_16BE).size
                val transBytes = currentFullTrans.toByteArray(Charsets.UTF_16BE).size

                if (origBytes > SerialPortManager.MAX_ORIGINAL_TEXT_BYTES ||
                    transBytes > SerialPortManager.MAX_TRANSLATED_TEXT_BYTES
                ) {
                    // Reached capacity: commit to history and clear both text boxes
                    val fullOrigToCommit = currentFullOrig.trim()
                    val fullTransToCommit = currentFullTrans.trim()
                    if (fullOrigToCommit.isNotEmpty()) {
                        _originalBase.value = if (_originalBase.value.isEmpty()) fullOrigToCommit else "${_originalBase.value}\n\n$fullOrigToCommit"
                    }
                    if (fullTransToCommit.isNotEmpty()) {
                        _translatedBase.value = if (_translatedBase.value.isEmpty()) fullTransToCommit else "${_translatedBase.value}\n\n$fullTransToCommit"
                    }

                    serialCommittedOrig = ""
                    serialCommittedTrans = ""
                    pendingOrig = ""
                    pendingTrans = ""
                    lastSentOrig = ""
                    lastSentTrans = ""
                    _originalLive.value = ""
                    _translatedLive.value = ""

                    serialPortManager.clearAllTextBoxes()
                    return@launch
                }

                sendSerialParagraph(currentFullOrig, currentFullTrans)
            }

            if (isFinished) {
                flushPending("isFinished")
            }
        }
    }

    private fun handleTranscriptionFinished() {
        viewModelScope.launch {
            flushPending("transcription_finished")
        }
    }

    private fun handleTranscriptionInterrupted() {
        viewModelScope.launch {
            segmentCommitJob?.cancel()
            segmentCommitJob = null
            pendingOrig = ""
            pendingTrans = ""
            _originalLive.value = ""
            _translatedLive.value = ""
        }
    }

    private fun armSilenceCommit() {
        segmentCommitJob?.cancel()
        if (!isRecording.value) return
        segmentCommitJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEGMENT_COMMIT_MS)
            if (isRecording.value) {
                flushPending("silenceTimer")
            }
        }
    }

    private fun flushPending(reason: String) {
        segmentCommitJob?.cancel()
        segmentCommitJob = null

        val finalOrig = pendingOrig.trim()
        val finalTrans = pendingTrans.trim()

        if (finalOrig.isNotEmpty()) {
            _originalBase.value = if (_originalBase.value.isEmpty()) finalOrig else "${_originalBase.value}\n\n$finalOrig"
        }
        if (finalTrans.isNotEmpty()) {
            _translatedBase.value = if (_translatedBase.value.isEmpty()) finalTrans else "${_translatedBase.value}\n\n$finalTrans"
        }

        if (reason == "clearAll" || reason == "bufferOverflow") {
            // Clear all or buffer overflow: clear serial paragraph buffers and send clear frames
            serialCommittedOrig = ""
            serialCommittedTrans = ""
            lastSentOrig = ""
            lastSentTrans = ""
            serialPortManager.clearAllTextBoxes()
        } else if (reason == "silenceTimer") {
            if (isRecording.value) {
                // Paragraph finished via silence timeout while recording is active:
                // clear serial paragraph buffer so next speech starts fresh
                serialCommittedOrig = ""
                serialCommittedTrans = ""
                lastSentOrig = ""
                lastSentTrans = ""
                serialPortManager.clearAllTextBoxes()
            }
        } else {
            // Sentence finished via VAD / isFinished / transcription_finished / stopRecording:
            val candidateOrig = if (finalOrig.isNotEmpty()) joinParagraphText(serialCommittedOrig, finalOrig) else serialCommittedOrig
            val candidateTrans = if (finalTrans.isNotEmpty()) joinParagraphText(serialCommittedTrans, finalTrans) else serialCommittedTrans

            val origBytes = candidateOrig.toByteArray(Charsets.UTF_16BE).size
            val transBytes = candidateTrans.toByteArray(Charsets.UTF_16BE).size

            if (origBytes > SerialPortManager.MAX_ORIGINAL_TEXT_BYTES ||
                transBytes > SerialPortManager.MAX_TRANSLATED_TEXT_BYTES
            ) {
                // Either reached capacity limit: send 0xFFFF clear frames and start new paragraph with just this finished sentence
                serialPortManager.clearAllTextBoxes()
                serialCommittedOrig = finalOrig
                serialCommittedTrans = finalTrans
                lastSentOrig = ""
                lastSentTrans = ""
            } else {
                serialCommittedOrig = candidateOrig
                serialCommittedTrans = candidateTrans
            }

            sendSerialParagraph(serialCommittedOrig, serialCommittedTrans)

            // Continue running silence commit timer only if recording is still active
            if (isRecording.value && reason != "stopRecording") {
                armSilenceCommit()
            }
        }

        pendingOrig = ""
        pendingTrans = ""
        _originalLive.value = ""
        _translatedLive.value = ""
    }

    fun clearAllText() {
        _originalBase.value = ""
        _originalLive.value = ""
        _translatedBase.value = ""
        _translatedLive.value = ""
        pendingOrig = ""
        pendingTrans = ""
        serialCommittedOrig = ""
        serialCommittedTrans = ""
        lastSentOrig = ""
        lastSentTrans = ""
        segmentCommitJob?.cancel()
        segmentCommitJob = null
        serialPortManager.clearAllTextBoxes()
    }

    fun clearLogs() {
        webSocket.clearLogs()
    }

    fun setShowUsbDiagnosticSheet(show: Boolean) {
        _showUsbDiagnosticSheet.value = show
    }

    fun setShowSettingsDialog(show: Boolean) {
        _showSettingsDialog.value = show
    }

    fun setShowLogConsole(show: Boolean) {
        _showLogConsole.value = show
    }

    override fun onCleared() {
        super.onCleared()
        LedController.setMicState(false)
        serialPortManager.stop()
        audioRecorder.stopRecording()
        audioPlayer.stop()
        webSocket.disconnect()
        segmentCommitJob?.cancel()
    }
}

