package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class AudioRecorder(
    private val context: Context,
    private val audioDeviceManager: AudioDeviceManager
) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioVolume = MutableStateFlow(0f)
    val audioVolume: StateFlow<Float> = _audioVolume.asStateFlow()

    private val _recordingError = MutableStateFlow<String?>(null)
    val recordingError: StateFlow<String?> = _recordingError.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording(
        sampleRate: Int = 16000,
        targetDeviceId: Int? = null,
        onAudioData: (ByteArray) -> Unit
    ) {
        if (_isRecording.value) return

        _recordingError.value = null

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        var hwSampleRate = sampleRate

        targetDeviceId?.let { id ->
            if (id != -1) {
                val deviceInfo = audioDeviceManager.getRawAudioDeviceInfo(id)
                deviceInfo?.let { info ->
                    if (info.sampleRates.isNotEmpty() && !info.sampleRates.contains(sampleRate)) {
                        hwSampleRate = info.sampleRates.maxOrNull() ?: 48000
                        Log.d("AudioRecorder", "Device doesn't support $sampleRate, using hardware rate $hwSampleRate")
                    }
                }
            }
        }

        val minBufferSize = AudioRecord.getMinBufferSize(hwSampleRate, channelConfig, audioFormat)

        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            _recordingError.value = "Invalid audio recording configuration (Sample rate: $hwSampleRate Hz)"
            return
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(2048)

        // UNPROCESSED skips the framework's AGC / noise suppression / AEC
        // and produces raw PCM that STT engines prefer. Falls back to MIC
        // if the device rejects UNPROCESSED (some OEMs ship broken support).
        val preferredSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val sourceToUse = tryOpenSource(preferredSource, hwSampleRate, channelConfig, audioFormat, bufferSize)
            ?: tryOpenSource(MediaRecorder.AudioSource.MIC, hwSampleRate, channelConfig, audioFormat, bufferSize)

        if (sourceToUse == null) {
            _recordingError.value = "AudioRecord initialization failed. Check microphone permissions."
            return
        }
        audioRecord = sourceToUse

        targetDeviceId?.let { id ->
            if (id != -1) {
                val deviceInfo = audioDeviceManager.getRawAudioDeviceInfo(id)
                deviceInfo?.let { info ->
                    val success = audioRecord?.setPreferredDevice(info)
                    Log.d("AudioRecorder", "Preferred device set to ${info.productName}: $success")
                }
            }
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _recordingError.value = "AudioRecord initialization failed. Check microphone permissions."
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioRecord?.startRecording()
        _isRecording.value = true

        recordingJob = scope.launch {
            val bufferSizeInBytes = (hwSampleRate * 0.1 * 2).toInt()
            val buffer = ByteArray(bufferSizeInBytes)
            var totalSent = 0
            while (isActive && _isRecording.value) {
                val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readBytes > 0) {
                    val chunk = buffer.copyOf(readBytes)
                    val volume = calculateRMSVolume(chunk, readBytes)
                    _audioVolume.value = volume

                    val out = if (hwSampleRate != sampleRate) {
                        resample(chunk, hwSampleRate, sampleRate)
                    } else {
                        chunk
                    }
                    onAudioData(out)
                    totalSent += out.size
                    if (totalSent % (sampleRate * 2 * 10) < out.size) {
                        Log.d("AudioRecorder", "framework chunk size=${out.size} rms=$volume total=$totalSent")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryOpenSource(
        source: Int,
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int
    ): AudioRecord? = try {
        AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
    } catch (e: Exception) {
        Log.w("AudioRecorder", "AudioRecord(source=$source) failed: ${e.message}")
        null
    }

    fun stopRecording() {
        _isRecording.value = false
        _audioVolume.value = 0f

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }

    private fun calculateRMSVolume(buffer: ByteArray, readBytes: Int): Float {
        var sum = 0.0
        var i = 0
        while (i < readBytes - 1) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += shortSample * shortSample
            i += 2
        }
        val numSamples = readBytes / 2
        if (numSamples == 0) return 0f
        val rms = sqrt(sum / numSamples)
        val normalized = (rms / 32768.0).toFloat()
        return (normalized * 4.0f).coerceIn(0f, 1f)
    }

    private fun resample(inputBytes: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate || inputBytes.isEmpty()) return inputBytes

        val inputShorts = ShortArray(inputBytes.size / 2)
        for (i in inputShorts.indices) {
            inputShorts[i] = ((inputBytes[i * 2].toInt() and 0xFF) or (inputBytes[i * 2 + 1].toInt() shl 8)).toShort()
        }

        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outLen = (inputShorts.size / ratio).toInt()
        if (outLen <= 0) return ByteArray(0)
        val outShorts = ShortArray(outLen)

        for (i in 0 until outLen) {
            val idx = i * ratio
            val i0 = idx.toInt()
            val frac = idx - i0
            val a = inputShorts[i0].toDouble()
            val b = if (i0 + 1 < inputShorts.size) inputShorts[i0 + 1].toDouble() else a
            outShorts[i] = (a + (b - a) * frac).toInt().toShort()
        }

        val outBytes = ByteArray(outShorts.size * 2)
        for (i in outShorts.indices) {
            outBytes[i * 2] = (outShorts[i].toInt() and 0xFF).toByte()
            outBytes[i * 2 + 1] = ((outShorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return outBytes
    }
}
