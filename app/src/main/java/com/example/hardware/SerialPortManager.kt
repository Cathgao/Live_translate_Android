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
 *   - Original text box: 0x1000
 *   - Translated text box: 0x1600
 * - Host switch page:
 *   - Start page: 5A A5 07 82 00 84 5A 01 00 01
 *   - Stop page: 5A A5 07 82 00 84 5A 01 00 03
 * - Screen button return:
 *   - Start button: 5A A5 06 83 20 00 01 00 02
 *   - Stop button: 5A A5 06 83 20 00 01 00 01
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
        const val ADDR_ORIGINAL_TEXT = 0x1000
        const val ADDR_TRANSLATED_TEXT = 0x1600

        // Buffer Limits
        const val MAX_ORIGINAL_TEXT_BYTES = 1000 // Original text max capacity: 1000 bytes (500 UTF-16 code units, safe within DWIN 1024B limit)
        const val MAX_TRANSLATED_TEXT_BYTES = 900 // Translated text max capacity: 900 bytes (450 UTF-16 code units)
        const val MAX_CHUNK_BYTES = 240 // Max chunk payload per frame (120 Words, well within DGUS 252B payload limit)

        private fun bytesOf(vararg ints: Int): ByteArray =
            ByteArray(ints.size) { ints[it].toByte() }

        // Button return frames from screen
        val CMD_START_BUTTON = bytesOf(0x5A, 0xA5, 0x06, 0x83, 0x20, 0x00, 0x01, 0x00, 0x02)
        val CMD_STOP_BUTTON  = bytesOf(0x5A, 0xA5, 0x06, 0x83, 0x20, 0x00, 0x01, 0x00, 0x01)

        // Host page switch commands
        val CMD_SWITCH_START_PAGE = bytesOf(0x5A, 0xA5, 0x07, 0x82, 0x00, 0x84, 0x5A, 0x01, 0x00, 0x01)
        val CMD_SWITCH_STOP_PAGE  = bytesOf(0x5A, 0xA5, 0x07, 0x82, 0x00, 0x84, 0x5A, 0x01, 0x00, 0x03)

        /**
         * Construct DWIN DGUS text display frames with incremental updating.
         * Skips previously sent common prefix bytes so previous chunks are not re-sent.
         * Appends 0xFFFF terminator at the end of the newly updated slice.
         *
         * @param address Base address for the text box (e.g. 0x1001 or 0x1401)
         * @param newText Full text for current paragraph
         * @param prevBytes Bytes previously sent for this paragraph (or empty if new paragraph/after reset)
         * @return Pair of (frames to send, new full byte array)
         */
        /**
         * Construct DWIN DGUS text display frames with incremental updating.
         * Skips previously sent common prefix bytes so previous frames/chunks are not re-sent.
         * Appends 0xFFFF terminator at the end of the newly updated slice to seal the text end
         * and prevent stale leftover memory characters from reappearing.
         *
         * @param address Base address for the text box (0x1000 for original, 0x1600 for translated)
         * @param newText Full text for current paragraph
         * @param prevBytes Bytes previously sent for this paragraph (or empty if new paragraph/after reset)
         * @return Pair of (frames to send, new full byte array)
         */
        fun buildIncrementalFrames(
            address: Int,
            newText: String,
            prevBytes: ByteArray
        ): Pair<List<ByteArray>, ByteArray> {
            val newBytes = newText.toByteArray(Charsets.UTF_16BE)

            if (newBytes.isEmpty()) {
                // Explicitly clear text box: send 0xFFFF terminator (8 bytes total)
                return Pair(listOf(buildClearFrame(address)), ByteArray(0))
            }

            // Find common prefix (aligned to 2-byte Word boundary)
            var commonPrefixBytes = 0
            val maxCheck = minOf(prevBytes.size, newBytes.size)
            while (commonPrefixBytes + 1 < maxCheck) {
                if (prevBytes[commonPrefixBytes] == newBytes[commonPrefixBytes] &&
                    prevBytes[commonPrefixBytes + 1] == newBytes[commonPrefixBytes + 1]
                ) {
                    commonPrefixBytes += 2
                } else {
                    break
                }
            }

            // If completely identical to previous state, no frames needed
            if (commonPrefixBytes == prevBytes.size && commonPrefixBytes == newBytes.size) {
                return Pair(emptyList(), prevBytes)
            }

            // Data to send is diffBytes + 2-byte 0xFFFF terminator
            val diffBytes = newBytes.copyOfRange(commonPrefixBytes, newBytes.size)
            val payload = ByteArray(diffBytes.size + 2)
            if (diffBytes.isNotEmpty()) {
                System.arraycopy(diffBytes, 0, payload, 0, diffBytes.size)
            }
            payload[payload.size - 2] = 0xFF.toByte()
            payload[payload.size - 1] = 0xFF.toByte()

            val frames = mutableListOf<ByteArray>()
            var offset = 0
            val startAddress = address + (commonPrefixBytes / 2)

            while (offset < payload.size) {
                val chunkSize = minOf(MAX_CHUNK_BYTES, payload.size - offset)
                val chunkBytes = payload.copyOfRange(offset, offset + chunkSize)

                // DGUS word address offset: 1 Word = 2 Bytes
                val chunkAddress = startAddress + (offset / 2)
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
            return Pair(frames, newBytes)
        }

        /**
         * Construct a frame with 0xFFFF to explicitly clear the text box starting at the given address.
         * Format: 5A A5 05 82 [Addr_H] [Addr_L] FF FF (8 bytes)
         */
        fun buildClearFrame(address: Int): ByteArray {
            val length = 5.toByte()
            val addrHigh = ((address shr 8) and 0xFF).toByte()
            val addrLow = (address and 0xFF).toByte()
            val frame = ByteArray(8)
            frame[0] = 0x5A.toByte()
            frame[1] = 0xA5.toByte()
            frame[2] = length
            frame[3] = 0x82.toByte()
            frame[4] = addrHigh
            frame[5] = addrLow
            frame[6] = 0xFF.toByte()
            frame[7] = 0xFF.toByte()
            return frame
        }

        /**
         * Construct full DWIN DGUS text display frames from scratch (starting at base address).
         */
        fun buildTextFrames(address: Int, text: String): List<ByteArray> {
            return buildIncrementalFrames(address, text, ByteArray(0)).first
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

    // Tracks previously sent byte stream for incremental updates
    private var lastOrigBytes: ByteArray = ByteArray(0)
    private var lastTransBytes: ByteArray = ByteArray(0)

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
     * Reset tracking buffers for original and translated texts so next text write starts from offset 0.
     */
    @Synchronized
    fun resetBuffers() {
        lastOrigBytes = ByteArray(0)
        lastTransBytes = ByteArray(0)
    }

    /**
     * Clear original text box on screen by writing 0xFFFF at base address (0x1000) and reset tracking buffer.
     */
    @Synchronized
    fun clearOriginalTextBox() {
        lastOrigBytes = ByteArray(0)
        sendFrame(buildClearFrame(ADDR_ORIGINAL_TEXT))
    }

    /**
     * Clear translated text box on screen by writing 0xFFFF at base address (0x1600) and reset tracking buffer.
     */
    @Synchronized
    fun clearTranslatedTextBox() {
        lastTransBytes = ByteArray(0)
        sendFrame(buildClearFrame(ADDR_TRANSLATED_TEXT))
    }

    /**
     * Clear both text boxes on screen by writing 0xFFFF at their base addresses and reset tracking buffers.
     */
    @Synchronized
    fun clearAllTextBoxes() {
        clearOriginalTextBox()
        clearTranslatedTextBox()
    }

    /**
     * Send original transcript to text box (0x1000) incrementally.
     * Only transmits newly appended/modified chunks with offset (NO 0xFFFF during normal streaming).
     */
    @Synchronized
    fun sendOriginalText(text: String, isNewParagraph: Boolean = false) {
        if (isNewParagraph) {
            lastOrigBytes = ByteArray(0)
        }
        val (frames, newBytes) = buildIncrementalFrames(ADDR_ORIGINAL_TEXT, text, lastOrigBytes)
        lastOrigBytes = newBytes
        for (frame in frames) {
            sendFrame(frame)
        }
    }

    /**
     * Send translated text to text box (0x1600) incrementally.
     * Only transmits newly appended/modified chunks with offset (NO 0xFFFF during normal streaming).
     */
    @Synchronized
    fun sendTranslatedText(text: String, isNewParagraph: Boolean = false) {
        if (isNewParagraph) {
            lastTransBytes = ByteArray(0)
        }
        val (frames, newBytes) = buildIncrementalFrames(ADDR_TRANSLATED_TEXT, text, lastTransBytes)
        lastTransBytes = newBytes
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
