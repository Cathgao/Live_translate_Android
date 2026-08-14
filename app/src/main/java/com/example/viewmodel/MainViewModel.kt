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
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_VAD_SILENCE_MS = "vad_silence_ms"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_PROTOCOL_MODE = "protocol_mode"
    }

    val audioDeviceManager = AudioDeviceManager(application)
    private val audioRecorder = AudioRecorder(application, audioDeviceManager)
    private val audioPlayer = AudioPlayer()

    private val webSocket = LiveTranslateWebSocket(
        onTranslationReceived = { original, translated, isFinal ->
            handleTranslationReceived(original, translated, isFinal)
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

    private val _targetLanguage = MutableStateFlow("Chinese (Simplified)")
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    private val _vadSilenceMs = MutableStateFlow(1000)
    val vadSilenceMs: StateFlow<Int> = _vadSilenceMs.asStateFlow()

    private val _fontSize = MutableStateFlow(25)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _sampleRate = MutableStateFlow(16000)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private val _translationHistory = MutableStateFlow<List<TranslationItem>>(emptyList())
    val translationHistory: StateFlow<List<TranslationItem>> = _translationHistory.asStateFlow()

    private val _liveOriginalText = MutableStateFlow("")
    val liveOriginalText: StateFlow<String> = _liveOriginalText.asStateFlow()

    private val _liveTranslatedText = MutableStateFlow("")
    val liveTranslatedText: StateFlow<String> = _liveTranslatedText.asStateFlow()

    private val _tokenUsage = MutableStateFlow(TokenUsage(0L, 0L))
    val tokenUsage: StateFlow<TokenUsage> = _tokenUsage.asStateFlow()

    private val _showUsbDiagnosticSheet = MutableStateFlow(false)
    val showUsbDiagnosticSheet: StateFlow<Boolean> = _showUsbDiagnosticSheet.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showLogConsole = MutableStateFlow(false)
    val showLogConsole: StateFlow<Boolean> = _showLogConsole.asStateFlow()

    init {
        // Restore persisted settings so they survive process restarts.
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _serverUrl.value = prefs.getString(KEY_SERVER_URL, "") ?: ""
        _targetLanguage.value = prefs.getString(KEY_TARGET_LANG, _targetLanguage.value) ?: _targetLanguage.value
        prefs.getInt(KEY_VAD_SILENCE_MS, _vadSilenceMs.value).also {
            if (it in 500..3000) _vadSilenceMs.value = it
        }
        prefs.getInt(KEY_FONT_SIZE, _fontSize.value).also {
            if (it in 8..72) _fontSize.value = it
        }
        _keepScreenOn.value = prefs.getBoolean(KEY_KEEP_SCREEN_ON, _keepScreenOn.value)
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
        prefs.putString(KEY_TARGET_LANG, _targetLanguage.value)
        prefs.putInt(KEY_VAD_SILENCE_MS, _vadSilenceMs.value)
        prefs.putInt(KEY_FONT_SIZE, _fontSize.value)
        prefs.putBoolean(KEY_KEEP_SCREEN_ON, _keepScreenOn.value)
        prefs.putString(KEY_PROTOCOL_MODE, _protocolMode.value.name)
        prefs.apply()
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
        // Persist so the value survives app restarts.
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
                targetLang = _targetLanguage.value,
                vadSilenceMs = _vadSilenceMs.value
            )
        }

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
    }

    fun toggleRecording() {
        if (isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun handleTranslationReceived(original: String, translated: String, isFinal: Boolean) {
        viewModelScope.launch {
            if (original.isNotBlank()) {
                _liveOriginalText.value = original
            }
            if (translated.isNotBlank()) {
                _liveTranslatedText.value = translated
            }

            if (isFinal && (translated.isNotBlank() || original.isNotBlank())) {
                val item = TranslationItem(
                    id = UUID.randomUUID().toString(),
                    originalText = _liveOriginalText.value.ifBlank { "Recorded Speech" },
                    translatedText = _liveTranslatedText.value.ifBlank { translated },
                    targetLang = _targetLanguage.value
                )
                
                val updated = _translationHistory.value.toMutableList()
                updated.add(0, item)
                _translationHistory.value = updated

                _liveOriginalText.value = ""
                _liveTranslatedText.value = ""
            }
        }
    }

    fun clearTranslationHistory() {
        _translationHistory.value = emptyList()
        _liveOriginalText.value = ""
        _liveTranslatedText.value = ""
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
    }
}
