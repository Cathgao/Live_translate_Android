package com.example.hardware

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Serial Port Manager for DWIN / DGUS screen communication via /dev/ttyAS5 (or custom tty).
 * Protocol:
 * - Frame Header: 0x5A 0xA5
 * - Text encoding: UTF-16BE (Big Endian)
 * - Text write frame: 5A A5 [Length] 82 [Address 2B] [UTF-16BE bytes]
 *   - Original text box: 0x1401
 *   - Translated text box: 0x1001
 * - Host switch page:
 *   - Start page: 5A A5 07 82 00 84 5A 01 00 01
 *   - Stop page: 5A A5 07 82 00 84 5A 01 00 03
 * - Screen button return:
 *   - Start button: 5A A5 06 83 20 01 01 00 02
 *   - Stop button: 5A A5 06 83 20 01 01 00 01
 * - Send interval: >= 100ms
 */
class SerialPortManager(
    private val devicePath: String = "/dev/ttyAS5",
    private val onStartButtonPressed: () -> Unit,
    private val onStopButtonPressed: () -> Unit
) {

    companion object {
        private const val TAG = "SerialPortManager"
        const val SEND_INTERVAL_MS = 100L

        // Text Variable Addresses
        const val ADDR_ORIGINAL_TEXT = 0x1401
        const val ADDR_TRANSLATED_TEXT = 0x1001

        // Buffer Limits
        const val MAX_TEXT_BYTES = 900 // Max 450 UTF-16 code units (safe within screen's 1000B text box limit)
        const val MAX_CHUNK_BYTES = 240 // Max chunk payload per frame (120 Words, well within DGUS 252B payload limit)

        private fun bytesOf(vararg ints: Int): ByteArray =
            ByteArray(ints.size) { ints[it].toByte() }

        // Button return frames from screen
        val CMD_START_BUTTON = bytesOf(0x5A, 0xA5, 0x06, 0x83, 0x20, 0x01, 0x01, 0x00, 0x02)
        val CMD_STOP_BUTTON  = bytesOf(0x5A, 0xA5, 0x06, 0x83, 0x20, 0x01, 0x01, 0x00, 0x01)

        // Host page switch commands
        val CMD_SWITCH_START_PAGE = bytesOf(0x5A, 0xA5, 0x07, 0x82, 0x00, 0x84, 0x5A, 0x01, 0x00, 0x01)
        val CMD_SWITCH_STOP_PAGE  = bytesOf(0x5A, 0xA5, 0x07, 0x82, 0x00, 0x84, 0x5A, 0x01, 0x00, 0x03)

        /**
         * Safely trim text to not exceed maxBytes (UTF-16BE: 2 bytes per char).
         * Discards the oldest characters from the beginning, preserving the newest content.
         * Ensures surrogate pairs are not split.
         */
        fun trimToMaxBytes(text: String, maxBytes: Int = MAX_TEXT_BYTES): String {
            val maxChars = maxBytes / 2
            if (text.length <= maxChars) return text

            var startIndex = text.length - maxChars
            // If startIndex points to a low surrogate, skip it to avoid dangling low surrogate
            if (startIndex < text.length && Character.isLowSurrogate(text[startIndex])) {
                startIndex++
            }
            return text.substring(startIndex)
        }

        /**
         * Construct DWIN DGUS text display frames with automatic address offsetting.
         * Total payload = trimmed UTF-16BE text (<= 900 bytes) + 2-byte 0xFFFF terminator.
         * If total payload exceeds MAX_CHUNK_BYTES (240 bytes), it is chunked into multiple frames.
         * DGUS VP RAM address increments by 1 for each Word (2 bytes), so chunkAddress = address + (byteOffset / 2).
         *
         * Format per frame: 5A A5 [Length] 82 [Address High] [Address Low] [Chunk UTF-16BE bytes]
         * Length = chunk bytes + 3
         */
        fun buildTextFrames(address: Int, text: String): List<ByteArray> {
            val trimmed = trimToMaxBytes(text, MAX_TEXT_BYTES)
            val rawBytes = trimmed.toByteArray(Charsets.UTF_16BE)

            // Append 0xFFFF terminator (2 bytes)
            val payload = ByteArray(rawBytes.size + 2)
            if (rawBytes.isNotEmpty()) {
                System.arraycopy(rawBytes, 0, payload, 0, rawBytes.size)
            }
            payload[payload.size - 2] = 0xFF.toByte()
            payload[payload.size - 1] = 0xFF.toByte()

            val frames = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < payload.size) {
                val chunkSize = minOf(MAX_CHUNK_BYTES, payload.size - offset)
                val chunkBytes = payload.copyOfRange(offset, offset + chunkSize)

                // DGUS word address offset: 1 Word = 2 Bytes
                val chunkAddress = address + (offset / 2)
                val length = (chunkSize + 3).toByte()
                val addrHigh = ((chunkAddress shr 8) and 0xFF).toByte()
                val addrLow = (chunkAddress and 0xFF).toByte()

                val frame = ByteArray(3 + (length.toInt() and 0xFF))
                frame[0] = 0x5A.toByte()
                frame[1] = 0xA5.toByte()
                frame[2] = length
                frame[3] = 0x82.toByte()
                frame[4] = addrHigh
                frame[5] = addrLow
                System.arraycopy(chunkBytes, 0, frame, 6, chunkBytes.size)

                frames.add(frame)
                offset += chunkSize
            }
            return frames
        }

        /**
         * Backward-compatible helper for single frame inspection.
         */
        fun buildTextFrame(address: Int, text: String): ByteArray {
            return buildTextFrames(address, text).first()
        }

        fun ByteArray.toHexString(): String =
            joinToString(" ") { "%02X".format(it) }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var readJob: Job? = null
    private var writeJob: Job? = null

    // Channel for outbound frames
    private val sendChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private var fis: FileInputStream? = null
    private var fos: FileOutputStream? = null

    fun start() {
        if (readJob?.isActive == true) return

        initDevicePermissions()

        readJob = scope.launch {
            listenSerial()
        }

        writeJob = scope.launch {
            processSendQueue()
        }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        writeJob?.cancel()
        writeJob = null
        closeStreams()
    }

    /**
     * Send original transcript to text box (0x1401).
     * Splits into multi-frame chunks if text + 0xFFFF exceeds 240 bytes.
     */
    fun sendOriginalText(text: String) {
        val frames = buildTextFrames(ADDR_ORIGINAL_TEXT, text)
        for (frame in frames) {
            sendFrame(frame)
        }
    }

    /**
     * Send translated text to text box (0x1001).
     * Splits into multi-frame chunks if text + 0xFFFF exceeds 240 bytes.
     */
    fun sendTranslatedText(text: String) {
        val frames = buildTextFrames(ADDR_TRANSLATED_TEXT, text)
        for (frame in frames) {
            sendFrame(frame)
        }
    }

    /**
     * Send command to switch screen to start page.
     */
    fun sendStartPage() {
        sendFrame(CMD_SWITCH_START_PAGE)
    }

    /**
     * Send command to switch screen to stop page.
     */
    fun sendStopPage() {
        sendFrame(CMD_SWITCH_STOP_PAGE)
    }

    /**
     * Enqueue a raw frame to be transmitted with >= 100ms spacing.
     */
    fun sendFrame(frame: ByteArray) {
        sendChannel.trySend(frame)
    }

    private fun initDevicePermissions() {
        try {
            val devFile = File(devicePath)
            if (devFile.exists() && (!devFile.canRead() || !devFile.canWrite())) {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "chmod 666 $devicePath")).waitFor()
            }
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "stty -F $devicePath 115200 raw -echo")).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "initDevicePermissions exception on $devicePath: ${e.message}")
        }
    }

    private suspend fun ensureStreams() {
        withContext(Dispatchers.IO) {
            val file = File(devicePath)
            if (!file.exists()) {
                return@withContext
            }
            if (fis == null) {
                try {
                    fis = FileInputStream(file)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open FileInputStream for $devicePath: ${e.message}")
                }
            }
            if (fos == null) {
                try {
                    fos = FileOutputStream(file)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open FileOutputStream for $devicePath: ${e.message}")
                }
            }
        }
    }

    private fun closeStreams() {
        try {
            fis?.close()
        } catch (_: Exception) {}
        fis = null

        try {
            fos?.close()
        } catch (_: Exception) {}
        fos = null
    }

    private suspend fun processSendQueue() {
        var lastSendTime = 0L
        for (frame in sendChannel) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastSendTime
            if (elapsed < SEND_INTERVAL_MS) {
                delay(SEND_INTERVAL_MS - elapsed)
            }

            try {
                ensureStreams()
                val out = fos
                if (out != null) {
                    withContext(Dispatchers.IO) {
                        out.write(frame)
                        out.flush()
                    }
                    lastSendTime = System.currentTimeMillis()
                    Log.d(TAG, "Sent frame to $devicePath (${frame.size}B): ${frame.toHexString()}")
                } else {
                    Log.w(TAG, "Cannot write frame: output stream for $devicePath is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to $devicePath: ${e.message}")
                closeStreams()
            }
        }
    }

    private suspend fun listenSerial() {
        val devFile = File(devicePath)
        if (!devFile.exists()) {
            Log.d(TAG, "Serial device $devicePath does not exist. Direct reading skipped.")
            return
        }

        val buf = ByteArray(512)
        val ringBuffer = ByteArray(2048)
        var ringLen = 0

        while (scope.isActive) {
            try {
                ensureStreams()
                val inStream = fis
                if (inStream == null) {
                    delay(1000)
                    continue
                }

                val read = withContext(Dispatchers.IO) {
                    inStream.read(buf)
                }

                if (read == -1) {
                    Log.w(TAG, "EOF encountered on $devicePath, reconnecting in 1s...")
                    closeStreams()
                    delay(1000)
                    continue
                }

                if (read > 0) {
                    if (ringLen + read <= ringBuffer.size) {
                        System.arraycopy(buf, 0, ringBuffer, ringLen, read)
                        ringLen += read
                    } else {
                        // Overflow reset
                        ringLen = 0
                        System.arraycopy(buf, 0, ringBuffer, 0, read)
                        ringLen = read
                    }

                    // Parse complete frames
                    var offset = 0
                    while (offset + 3 <= ringLen) {
                        if (ringBuffer[offset] == 0x5A.toByte() && ringBuffer[offset + 1] == 0xA5.toByte()) {
                            val dataLen = ringBuffer[offset + 2].toInt() and 0xFF
                            val totalFrameLen = 3 + dataLen
                            if (offset + totalFrameLen <= ringLen) {
                                val frame = ringBuffer.copyOfRange(offset, offset + totalFrameLen)
                                handleIncomingFrame(frame)
                                offset += totalFrameLen
                            } else {
                                // Frame incomplete, wait for more data
                                break
                            }
                        } else {
                            offset++
                        }
                    }

                    if (offset > 0) {
                        val remaining = ringLen - offset
                        if (remaining > 0) {
                            System.arraycopy(ringBuffer, offset, ringBuffer, 0, remaining)
                        }
                        ringLen = remaining
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception reading from $devicePath: ${e.message}")
                closeStreams()
                delay(1000)
            }
        }
    }

    private suspend fun handleIncomingFrame(frame: ByteArray) {
        Log.d(TAG, "Received frame from $devicePath (${frame.size}B): ${frame.toHexString()}")

        if (frame.contentEquals(CMD_START_BUTTON)) {
            Log.i(TAG, "Start button detected from serial screen")
            withContext(Dispatchers.Main) {
                onStartButtonPressed()
            }
        } else if (frame.contentEquals(CMD_STOP_BUTTON)) {
            Log.i(TAG, "Stop button detected from serial screen")
            withContext(Dispatchers.Main) {
                onStopButtonPressed()
            }
        }
    }
}
