package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentSampleRate = 24000

    fun playPcmChunk(pcmData: ByteArray, sampleRate: Int = 24000) {
        if (pcmData.isEmpty()) return

        scope.launch {
            try {
                if (audioTrack == null || currentSampleRate != sampleRate) {
                    initAudioTrack(sampleRate)
                }

                audioTrack?.let { track ->
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                    track.write(pcmData, 0, pcmData.size)
                }
            } catch (e: Exception) {
                Log.e("AudioPlayer", "Error playing PCM chunk", e)
            }
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        try {
            audioTrack?.release()
            
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

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
                .setBufferSizeInBytes((minBufferSize * 2).coerceAtLeast(4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentSampleRate = sampleRate
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Failed to initialize AudioTrack", e)
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error stopping AudioTrack", e)
        }
    }
}
