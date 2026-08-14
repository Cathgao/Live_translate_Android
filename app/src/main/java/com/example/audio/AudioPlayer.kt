package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var playerJob: Job? = null
    private val audioQueue = Channel<Pair<ByteArray, Int>>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var currentSampleRate = 24000

    init {
        startPlaybackLoop()
    }

    private fun startPlaybackLoop() {
        playerJob?.cancel()
        playerJob = scope.launch {
            while (isActive) {
                try {
                    val (pcmData, sampleRate) = audioQueue.receive()
                    if (pcmData.isEmpty()) continue

                    mutex.withLock {
                        if (audioTrack == null || currentSampleRate != sampleRate) {
                            initAudioTrack(sampleRate)
                        }

                        audioTrack?.let { track ->
                            if (track.state == AudioTrack.STATE_INITIALIZED) {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                                track.write(pcmData, 0, pcmData.size)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error in playback loop", e)
                    }
                }
            }
        }
    }

    fun playPcmChunk(pcmData: ByteArray, sampleRate: Int = 24000) {
        if (pcmData.isEmpty()) return
        audioQueue.trySend(Pair(pcmData, sampleRate))
    }

    private fun initAudioTrack(sampleRate: Int) {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
            audioTrack = null

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = maxOf(minBufferSize * 4, 8192)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentSampleRate = sampleRate
            Log.d(TAG, "AudioTrack initialized: sampleRate=$sampleRate, bufferSize=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                try {
                    while (audioQueue.tryReceive().isSuccess) {
                        // Drain queue
                    }
                    audioTrack?.apply {
                        if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                            stop()
                        }
                        release()
                    }
                    audioTrack = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioTrack", e)
                }
            }
        }
    }
}
