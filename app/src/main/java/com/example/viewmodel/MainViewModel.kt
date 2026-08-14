package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioDeviceManager
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
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

    init {
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
        _sourceLanguage.value = lang
        persist()
    }

    fun updateTargetLanguage(lang: String) {
        _targetLanguage.value = lang
        persist()
    }

    fun updateVadSilenceMs(ms: Int) {
        _vadSilenceMs.value = ms
        persist()
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
        _serverUrl.value = cleaned
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, cleaned)
            .apply()
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
        segmentCommitJob?.cancel()
        segmentCommitJob = null

        val targetDeviceId = selectedDevice.value?.id
        audioRecorder.startRecording(
            sampleRate = _sampleRate.value,
            targetDeviceId = targetDeviceId
        ) { pcmChunk ->
            webSocket.sendAudioChunk(pcmChunk, _sampleRate.value)
        }
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
        flushPending("stopRecording")
    }

    fun toggleRecording() {
        if (isRecording.value) {
            stopRecording()
        } else {
            startRecording()
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
                armSilenceCommit()
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
        segmentCommitJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEGMENT_COMMIT_MS)
            flushPending("silenceTimer")
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
        segmentCommitJob?.cancel()
        segmentCommitJob = null
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
        audioRecorder.stopRecording()
        audioPlayer.stop()
        webSocket.disconnect()
        segmentCommitJob?.cancel()
    }
}
