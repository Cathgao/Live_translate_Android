package com.example.hardware

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct Linux /dev/input listener for development boards (e.g. K2C board).
 * Listens for hardware key events such as KEY_F1 (code 59).
 */
class DevInputListener(
    private val devicePath: String = "/dev/input/event1",
    private val targetKeyCode: Int = KEY_F1,
    private val onKeyDown: (keyCode: Int) -> Unit
) {

    companion object {
        private const val TAG = "DevInputListener"

        // Linux Input Event Types & Codes
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val KEY_F1 = 59 // Linux KEY_F1 (0x3b)
        const val KEY_DOWN = 1
        const val KEY_UP = 0

        // Linux struct input_event sizes
        private const val EVENT_SIZE_64 = 24 // 64-bit kernel: 8B tv_sec + 8B tv_usec + 2B type + 2B code + 4B value
        private const val EVENT_SIZE_32 = 16 // 32-bit kernel: 4B tv_sec + 4B tv_usec + 2B type + 2B code + 4B value
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var listeningJob: Job? = null

    fun start() {
        if (listeningJob?.isActive == true) return

        listeningJob = scope.launch {
            listenDevice()
        }
    }

    fun stop() {
        listeningJob?.cancel()
        listeningJob = null
    }

    private suspend fun listenDevice() {
        val devFile = File(devicePath)
        if (!devFile.exists()) {
            Log.d(TAG, "Input device $devicePath does not exist. Direct reading skipped.")
            return
        }

        if (!devFile.canRead()) {
            Log.d(TAG, "Input device $devicePath is not readable (requires root or chmod 666). Relying on Android KeyEvent.")
            return
        }

        Log.i(TAG, "Starting direct hardware key listener on $devicePath (targetKey=$targetKeyCode)")

        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(devFile)
            val buffer = ByteArray(EVENT_SIZE_64)

            while (scope.isActive) {
                var bytesRead = 0
                while (bytesRead < EVENT_SIZE_64 && scope.isActive) {
                    val read = withContext(Dispatchers.IO) {
                        fis.read(buffer, bytesRead, EVENT_SIZE_64 - bytesRead)
                    }
                    if (read == -1) {
                        Log.w(TAG, "EOF encountered on $devicePath")
                        return
                    }
                    bytesRead += read
                }

                if (bytesRead >= EVENT_SIZE_64) {
                    val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

                    // Check 64-bit layout: offset 16 for type, 18 for code, 20 for value
                    val type64 = byteBuffer.getShort(16).toInt()
                    val code64 = byteBuffer.getShort(18).toInt()
                    val value64 = byteBuffer.getInt(20)

                    // Check 32-bit layout fallback: offset 8 for type, 10 for code, 12 for value
                    val type32 = byteBuffer.getShort(8).toInt()
                    val code32 = byteBuffer.getShort(10).toInt()
                    val value32 = byteBuffer.getInt(12)

                    var matched = false
                    var pressedCode = 0

                    if (type64 == EV_KEY && value64 == KEY_DOWN) {
                        if (targetKeyCode == -1 || code64 == targetKeyCode) {
                            matched = true
                            pressedCode = code64
                        }
                    } else if (type32 == EV_KEY && value32 == KEY_DOWN) {
                        if (targetKeyCode == -1 || code32 == targetKeyCode) {
                            matched = true
                            pressedCode = code32
                        }
                    }

                    if (matched) {
                        Log.i(TAG, "Hardware key pressed detected from $devicePath: keyCode=$pressedCode")
                        withContext(Dispatchers.Main) {
                            onKeyDown(pressedCode)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception reading from $devicePath: ${e.message}")
        } finally {
            try {
                fis?.close()
            } catch (e: Exception) {
                // Ignore
            }
            Log.d(TAG, "Direct input listener stopped for $devicePath")
        }
    }
}
