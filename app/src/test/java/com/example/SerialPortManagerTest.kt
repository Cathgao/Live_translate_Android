package com.example

import com.example.hardware.SerialPortManager
import com.example.hardware.SerialPortManager.Companion.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialPortManagerTest {

    @Test
    fun testOriginalTextFrameGeneration() {
        val text = "你好吗？"
        val frames = SerialPortManager.buildTextFrames(SerialPortManager.ADDR_ORIGINAL_TEXT, text)
        assertEquals(1, frames.size)
        val frame = frames[0]

        // "你好吗？" in UTF-16BE: 4F 60 59 7D 54 17 FF 1F (8 bytes) + FF FF (2 bytes) = 10 bytes payload
        // Length = 10 + 3 = 13 (0x0D)
        // Address: 0x1000
        // Expected: 5A A5 0D 82 10 00 4F 60 59 7D 54 17 FF 1F FF FF
        val expectedHex = "5A A5 0D 82 10 00 4F 60 59 7D 54 17 FF 1F FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(16, frame.size)
        assertEquals(0x0D.toByte(), frame[2])
    }

    @Test
    fun testTranslatedTextFrameGeneration() {
        val text = "Hello"
        val frames = SerialPortManager.buildTextFrames(SerialPortManager.ADDR_TRANSLATED_TEXT, text)
        assertEquals(1, frames.size)
        val frame = frames[0]

        // "Hello" in UTF-16BE: 00 48 00 65 00 6C 00 6C 00 6F (10 bytes) + FF FF (2 bytes) = 12 bytes payload
        // Length = 12 + 3 = 15 (0x0F)
        // Address: 0x1600
        val expectedHex = "5A A5 0F 82 16 00 00 48 00 65 00 6C 00 6C 00 6F FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(18, frame.size)
    }

    @Test
    fun testClearFrameGeneration() {
        val frameOrig = SerialPortManager.buildClearFrame(SerialPortManager.ADDR_ORIGINAL_TEXT)
        assertEquals("5A A5 05 82 10 00 FF FF", frameOrig.toHexString())
        assertEquals(8, frameOrig.size)

        val frameTrans = SerialPortManager.buildClearFrame(SerialPortManager.ADDR_TRANSLATED_TEXT)
        assertEquals("5A A5 05 82 16 00 FF FF", frameTrans.toHexString())
        assertEquals(8, frameTrans.size)
    }

    @Test
    fun testEmptyTextFrameGeneration() {
        val frame = SerialPortManager.buildTextFrame(SerialPortManager.ADDR_ORIGINAL_TEXT, "")
        // Explicit clear frame: 5A A5 05 82 10 00 FF FF
        val expectedHex = "5A A5 05 82 10 00 FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(8, frame.size)
    }

    @Test
    fun testMultiFrameChunkingAndAutomaticOffset() {
        // 200 characters = 400 bytes text + 2 bytes 0xFFFF = 402 bytes payload
        // Should split into 2 chunks:
        // Chunk 0: 240 bytes @ 0x1000 (120 words)
        // Chunk 1: 162 bytes @ 0x1000 + 120 = 0x1078 (81 words, ending with FF FF)
        val longText = "A".repeat(200)
        val frames = SerialPortManager.buildTextFrames(SerialPortManager.ADDR_ORIGINAL_TEXT, longText)
        assertEquals(2, frames.size)

        // Frame 0:
        // Length: 240 + 3 = 243 (0xF3)
        // Addr: 0x10 0x00
        assertEquals(0x5A.toByte(), frames[0][0])
        assertEquals(0xA5.toByte(), frames[0][1])
        assertEquals(243.toByte(), frames[0][2])
        assertEquals(0x82.toByte(), frames[0][3])
        assertEquals(0x10.toByte(), frames[0][4])
        assertEquals(0x00.toByte(), frames[0][5])
        assertEquals(246, frames[0].size)

        // Frame 1:
        // Length: 162 + 3 = 165 (0xA5)
        // Addr: 0x1000 + 120 (0x78) = 0x1078 -> 0x10 0x78
        assertEquals(0x5A.toByte(), frames[1][0])
        assertEquals(0xA5.toByte(), frames[1][1])
        assertEquals(165.toByte(), frames[1][2])
        assertEquals(0x82.toByte(), frames[1][3])
        assertEquals(0x10.toByte(), frames[1][4])
        assertEquals(0x78.toByte(), frames[1][5])
        assertEquals(168, frames[1].size)

        // Verify terminator 0xFFFF at end of last frame
        assertEquals(0xFF.toByte(), frames[1][frames[1].size - 2])
        assertEquals(0xFF.toByte(), frames[1][frames[1].size - 1])
    }

    @Test
    fun test1000BytesOriginalTextChunking() {
        // 500 characters = 1000 bytes text + 2 bytes 0xFFFF = 1002 bytes payload.
        // Chunked into 5 frames:
        // Chunk 0: 240 bytes @ 0x1000
        // Chunk 1: 240 bytes @ 0x1000 + 120 (0x1078)
        // Chunk 2: 240 bytes @ 0x1000 + 240 (0x10F0)
        // Chunk 3: 240 bytes @ 0x1000 + 360 (0x1168)
        // Chunk 4: 42 bytes @ 0x1000 + 480 (0x11E0)
        val text1000B = "中".repeat(500)
        val frames = SerialPortManager.buildTextFrames(SerialPortManager.ADDR_ORIGINAL_TEXT, text1000B)
        assertEquals(5, frames.size)

        assertEquals(0x10.toByte(), frames[0][4])
        assertEquals(0x00.toByte(), frames[0][5])

        assertEquals(0x10.toByte(), frames[1][4])
        assertEquals(0x78.toByte(), frames[1][5])

        assertEquals(0x10.toByte(), frames[2][4])
        assertEquals(0xF0.toByte(), frames[2][5])

        assertEquals(0x11.toByte(), frames[3][4])
        assertEquals(0x68.toByte(), frames[3][5])

        assertEquals(0x11.toByte(), frames[4][4])
        assertEquals(0xE0.toByte(), frames[4][5])
        assertEquals(42 + 6, frames[4].size)

        // Verify last 2 bytes are 0xFFFF
        assertEquals(0xFF.toByte(), frames[4][frames[4].size - 2])
        assertEquals(0xFF.toByte(), frames[4][frames[4].size - 1])
    }

    @Test
    fun test900BytesTranslatedTextChunking() {
        // 450 characters = 900 bytes text + 2 bytes 0xFFFF = 902 bytes payload.
        // Chunked into 4 frames:
        // Chunk 0: 240 bytes @ 0x1600
        // Chunk 1: 240 bytes @ 0x1600 + 120 (0x1678)
        // Chunk 2: 240 bytes @ 0x1600 + 240 (0x16F0)
        // Chunk 3: 182 bytes @ 0x1600 + 360 (0x1768)
        val text900B = "E".repeat(450)
        val frames = SerialPortManager.buildTextFrames(SerialPortManager.ADDR_TRANSLATED_TEXT, text900B)
        assertEquals(4, frames.size)

        assertEquals(0x16.toByte(), frames[0][4])
        assertEquals(0x00.toByte(), frames[0][5])

        assertEquals(0x16.toByte(), frames[1][4])
        assertEquals(0x78.toByte(), frames[1][5])

        assertEquals(0x16.toByte(), frames[2][4])
        assertEquals(0xF0.toByte(), frames[2][5])

        assertEquals(0x17.toByte(), frames[3][4])
        assertEquals(0x68.toByte(), frames[3][5])
        assertEquals(182 + 6, frames[3].size)

        // Verify last 2 bytes are 0xFFFF
        assertEquals(0xFF.toByte(), frames[3][frames[3].size - 2])
        assertEquals(0xFF.toByte(), frames[3][frames[3].size - 1])
    }

    @Test
    fun testIncrementalFrameGeneration_AppendWord() {
        // Step 1: Initial text "Hello, hello. 1 2" (34 bytes UTF-16BE + 2B 0xFFFF = 36B payload)
        val initialText = "Hello, hello. 1 2"
        val (frames1, prevBytes1) = SerialPortManager.buildIncrementalFrames(
            SerialPortManager.ADDR_ORIGINAL_TEXT,
            initialText,
            ByteArray(0)
        )
        assertEquals(1, frames1.size)
        // Length = 36 + 3 = 39 (0x27). Address = 0x1000. Ends with 0xFFFF.
        assertEquals(0x27.toByte(), frames1[0][2])
        assertEquals(0x10.toByte(), frames1[0][4])
        assertEquals(0x00.toByte(), frames1[0][5])
        assertEquals(42, frames1[0].size) // 6 header + 34 data + 2 terminator
        assertEquals(0xFF.toByte(), frames1[0][40])
        assertEquals(0xFF.toByte(), frames1[0][41])
        assertEquals(34, prevBytes1.size)

        // Step 2: Streaming appended " 3 4." -> "Hello, hello. 1 2 3 4." (44 bytes total, diff = 10 bytes)
        // Common prefix: 34 bytes (17 words). Offset = 17 words -> Address 0x1000 + 17 = 0x1011.
        // Diff: " 3 4." (10 bytes) + 2B 0xFFFF = 12B payload. Length = 12 + 3 = 15 (0x0F).
        // Frame: 5A A5 0F 82 10 11 00 20 00 33 00 20 00 34 00 2E FF FF
        val newText = "Hello, hello. 1 2 3 4."
        val (frames2, prevBytes2) = SerialPortManager.buildIncrementalFrames(
            SerialPortManager.ADDR_ORIGINAL_TEXT,
            newText,
            prevBytes1
        )
        assertEquals(1, frames2.size)
        assertEquals("5A A5 0F 82 10 11 00 20 00 33 00 20 00 34 00 2E FF FF", frames2[0].toHexString())
        assertEquals(44, prevBytes2.size)

        // Step 3: Same text again -> 0 frames produced
        val (frames3, prevBytes3) = SerialPortManager.buildIncrementalFrames(
            SerialPortManager.ADDR_ORIGINAL_TEXT,
            newText,
            prevBytes2
        )
        assertTrue(frames3.isEmpty())
        assertEquals(44, prevBytes3.size)
    }

    @Test
    fun testIncrementalFrameGeneration_Clear() {
        val prevBytes = "Hello".toByteArray(Charsets.UTF_16BE)
        val (frames, newBytes) = SerialPortManager.buildIncrementalFrames(
            SerialPortManager.ADDR_ORIGINAL_TEXT,
            "",
            prevBytes
        )
        assertEquals(1, frames.size)
        assertEquals("5A A5 05 82 10 00 FF FF", frames[0].toHexString())
        assertEquals(0, newBytes.size)
    }

    @Test
    fun testPageSwitchCommands() {
        assertEquals("5A A5 07 82 00 84 5A 01 00 01", SerialPortManager.CMD_SWITCH_START_PAGE.toHexString())
        assertEquals("5A A5 07 82 00 84 5A 01 00 03", SerialPortManager.CMD_SWITCH_STOP_PAGE.toHexString())
    }

    @Test
    fun testButtonReturnCommands() {
        assertEquals("5A A5 06 83 20 00 01 00 02", SerialPortManager.CMD_START_BUTTON.toHexString())
        assertEquals("5A A5 06 83 20 00 01 00 01", SerialPortManager.CMD_STOP_BUTTON.toHexString())
    }

    @Test
    fun testSendIntervalAtLeast100ms() {
        assertTrue("Send interval must not be less than 100ms", SerialPortManager.SEND_INTERVAL_MS >= 100L)
    }
}
