package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    companion object {
        private const val TAG = "AudioRecorder"
    }

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

        val targetDevice: AudioDeviceInfo? = targetDeviceId?.let { id ->
            if (id != -1) audioDeviceManager.getRawAudioDeviceInfo(id) else null
        }

        // 1. Determine hardware sample rate
        var hwSampleRate = sampleRate
        if (targetDevice != null) {
            val supportedRates = targetDevice.sampleRates
            if (supportedRates.isNotEmpty()) {
                hwSampleRate = if (supportedRates.contains(sampleRate)) {
                    sampleRate
                } else if (supportedRates.contains(48000)) {
                    48000
                } else if (supportedRates.contains(44100)) {
                    44100
                } else {
                    supportedRates.maxOrNull() ?: 48000
                }
            } else if (isUsbDevice(targetDevice.type)) {
                // USB audio devices standard hardware sample rate is 48000 Hz
                hwSampleRate = 48000
            }
        }

        // 2. Determine hardware channel configuration
        // Most USB microphones report Stereo (2 channels) only.
        var hwChannelMask = AudioFormat.CHANNEL_IN_MONO
        var hwChannelCount = 1

        if (targetDevice != null) {
            val masks = targetDevice.channelMasks
            val counts = targetDevice.channelCounts
            val hasMono = (masks.isNotEmpty() && masks.contains(AudioFormat.CHANNEL_IN_MONO)) ||
                    (counts.isNotEmpty() && counts.contains(1))
            val hasStereo = (masks.isNotEmpty() && (masks.contains(AudioFormat.CHANNEL_IN_STEREO) || masks.contains(0x000c))) ||
                    (counts.isNotEmpty() && counts.contains(2))

            if (!hasMono && (hasStereo || isUsbDevice(targetDevice.type))) {
                hwChannelMask = AudioFormat.CHANNEL_IN_STEREO
                hwChannelCount = 2
            }
        }

        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(hwSampleRate, hwChannelMask, audioEncoding)

        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            _recordingError.value = "Invalid audio recording configuration ($hwSampleRate Hz, channels=$hwChannelCount)"
            return
        }

        // 3. Generous buffer size to avoid HAL/AudioFlinger overflow on USB Audio
        val bytesPerFrame = hwChannelCount * 2
        val bufferSize = maxOf(minBufferSize * 4, (hwSampleRate * bytesPerFrame * 0.3).toInt(), 16384)

        Log.d(TAG, "Configuring AudioRecord: hwSampleRate=$hwSampleRate, channels=$hwChannelCount, bufferSize=$bufferSize, targetDevice=${targetDevice?.productName}")

        // 4. Try building AudioRecord using AudioRecord.Builder (with pre-set preferred device)
        val audioSourcesToTry = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        var record: AudioRecord? = null
        for (source in audioSourcesToTry) {
            record = tryBuildAudioRecord(source, hwSampleRate, hwChannelMask, audioEncoding, bufferSize, targetDevice)
            if (record != null) {
                Log.d(TAG, "AudioRecord created successfully with source=$source")
                break
            }
        }

        // Fallback: If Stereo failed or device rejected config, try Mono fallback
        if (record == null && hwChannelCount == 2) {
            Log.w(TAG, "Stereo AudioRecord build failed, falling back to Mono")
            val monoMinBuf = AudioRecord.getMinBufferSize(hwSampleRate, AudioFormat.CHANNEL_IN_MONO, audioEncoding)
            val monoBufSize = maxOf(monoMinBuf * 4, 8192)
            hwChannelMask = AudioFormat.CHANNEL_IN_MONO
            hwChannelCount = 1

            for (source in audioSourcesToTry) {
                record = tryBuildAudioRecord(source, hwSampleRate, hwChannelMask, audioEncoding, monoBufSize, targetDevice)
                if (record != null) break
            }
        }

        if (record == null) {
            _recordingError.value = "AudioRecord initialization failed. Check microphone permissions."
            return
        }

        audioRecord = record

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error calling startRecording()", e)
            _recordingError.value = "Failed to start recording: ${e.message}"
            record.release()
            audioRecord = null
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            _recordingError.value = "AudioRecord state not recording (state=${record.recordingState})"
            record.release()
            audioRecord = null
            return
        }

        _isRecording.value = true

        // 5. Read loop
        val captureChannelCount = hwChannelCount
        val captureSampleRate = hwSampleRate

        recordingJob = scope.launch {
            // Read in ~100ms chunks at hardware rate
            val chunkFrames = captureSampleRate / 10
            val readBufferBytes = chunkFrames * captureChannelCount * 2
            val readBuffer = ByteArray(readBufferBytes)
            var totalSent = 0

            while (isActive && _isRecording.value) {
                val currentRecord = audioRecord ?: break
                val readBytes = currentRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)

                if (readBytes > 0) {
                    val rawChunk = readBuffer.copyOf(readBytes)

                    // Step A: If hardware is stereo, convert to mono PCM16
                    val monoBytes = if (captureChannelCount == 2) {
                        stereoToMonoPcm16(rawChunk, readBytes)
                    } else {
                        rawChunk
                    }

                    // Step B: Calculate RMS volume
                    val volume = calculateRMSVolume(monoBytes, monoBytes.size)
                    _audioVolume.value = volume

                    // Step C: Resample to target rate (e.g. 16000 Hz) if needed
                    val outBytes = if (captureSampleRate != sampleRate) {
                        resample(monoBytes, captureSampleRate, sampleRate)
                    } else {
                        monoBytes
                    }

                    onAudioData(outBytes)
                    totalSent += outBytes.size

                    if (totalSent <= outBytes.size || totalSent % (sampleRate * 2 * 3) < outBytes.size) {
                        Log.d(TAG, "Stream active: chunk=${outBytes.size}B, rms=$volume, total=$totalSent")
                    }
                } else if (readBytes < 0) {
                    Log.w(TAG, "AudioRecord.read returned error code: $readBytes")
                    if (readBytes == AudioRecord.ERROR_DEAD_OBJECT) {
                        _recordingError.value = "Audio device disconnected"
                        break
                    }
                    delay(10)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryBuildAudioRecord(
        source: Int,
        sampleRate: Int,
        channelMask: Int,
        audioEncoding: Int,
        bufferSize: Int,
        targetDevice: AudioDeviceInfo?
    ): AudioRecord? {
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(audioEncoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val builder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)

            val record = builder.build()
            if (targetDevice != null) {
                val setDevSuccess = record.setPreferredDevice(targetDevice)
                Log.d(TAG, "setPreferredDevice ${targetDevice.productName} (id=${targetDevice.id}): $setDevSuccess")
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.Builder failed with source=$source: ${e.message}")
            null
        }
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
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }

    private fun stereoToMonoPcm16(stereoBytes: ByteArray, readBytes: Int): ByteArray {
        val numFrames = readBytes / 4 // 2 bytes per sample * 2 channels = 4 bytes per frame
        if (numFrames <= 0) return ByteArray(0)
        val monoBytes = ByteArray(numFrames * 2)

        // Check channel amplitudes to handle mono USB mics that only output to Left or Right channel
        var leftMax = 0
        var rightMax = 0
        for (i in 0 until numFrames) {
            val left = ((stereoBytes[i * 4].toInt() and 0xFF) or (stereoBytes[i * 4 + 1].toInt() shl 8)).toShort().toInt()
            val right = ((stereoBytes[i * 4 + 2].toInt() and 0xFF) or (stereoBytes[i * 4 + 3].toInt() shl 8)).toShort().toInt()
            val absL = abs(left)
            val absR = abs(right)
            if (absL > leftMax) leftMax = absL
            if (absR > rightMax) rightMax = absR
        }

        val useLeftOnly = leftMax > 150 && rightMax < 15
        val useRightOnly = rightMax > 150 && leftMax < 15

        for (i in 0 until numFrames) {
            val left = ((stereoBytes[i * 4].toInt() and 0xFF) or (stereoBytes[i * 4 + 1].toInt() shl 8)).toShort().toInt()
            val right = ((stereoBytes[i * 4 + 2].toInt() and 0xFF) or (stereoBytes[i * 4 + 3].toInt() shl 8)).toShort().toInt()

            val monoSample: Short = when {
                useLeftOnly -> left.toShort()
                useRightOnly -> right.toShort()
                else -> ((left + right) / 2).toShort()
            }

            monoBytes[i * 2] = (monoSample.toInt() and 0xFF).toByte()
            monoBytes[i * 2 + 1] = ((monoSample.toInt() shr 8) and 0xFF).toByte()
        }
        return monoBytes
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

    private fun isUsbDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }
}
