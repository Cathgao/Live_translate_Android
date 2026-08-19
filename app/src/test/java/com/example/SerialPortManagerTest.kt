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

        // "你好吗？" in UTF-16BE: 4F 60 59 7D 54 17 FF 1F (8 bytes)
        // With 0xFFFF terminator: 4F 60 59 7D 54 17 FF 1F FF FF (10 bytes)
        // Length = 10 + 3 = 13 (0x0D)
        // Address: 0x1401
        // Expected: 5A A5 0D 82 14 01 4F 60 59 7D 54 17 FF 1F FF FF
        val expectedHex = "5A A5 0D 82 14 01 4F 60 59 7D 54 17 FF 1F FF FF"
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

        // "Hello" in UTF-16BE: 00 48 00 65 00 6C 00 6C 00 6F (10 bytes)
        // With 0xFFFF terminator: 00 48 00 65 00 6C 00 6C 00 6F FF FF (12 bytes)
        // Length = 12 + 3 = 15 (0x0F)
        // Address: 0x1001
        val expectedHex = "5A A5 0F 82 10 01 00 48 00 65 00 6C 00 6C 00 6F FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(18, frame.size)
    }

    @Test
    fun testEmptyTextFrameGeneration() {
        val frame = SerialPortManager.buildTextFrame(SerialPortManager.ADDR_ORIGINAL_TEXT, "")
        // Expected: 5A A5 05 82 14 01 FF FF
        val expectedHex = "5A A5 05 82 14 01 FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(8, frame.size)
    }

    @Test
    fun testMultiFrameChunkingAndAutomaticOffset() {
        // 200 characters = 400 bytes text + 2 bytes 0xFFFF = 402 bytes payload
        // Should split into 2 chunks:
        // Chunk 0: 240 bytes @ 0x1401 (120 words)
        // Chunk 1: 162 bytes @ 0x1401 + 120 = 0x1479
        val longText = "A".repeat(200)
        val frames = SerialPortManager.buildTextFrames(SerialPortManager.ADDR_ORIGINAL_TEXT, longText)
        assertEquals(2, frames.size)

        // Frame 0:
        // Length: 240 + 3 = 243 (0xF3)
        // Addr: 0x14 0x01
        assertEquals(0x5A.toByte(), frames[0][0])
        assertEquals(0xA5.toByte(), frames[0][1])
        assertEquals(243.toByte(), frames[0][2])
        assertEquals(0x82.toByte(), frames[0][3])
        assertEquals(0x14.toByte(), frames[0][4])
        assertEquals(0x01.toByte(), frames[0][5])
        assertEquals(246, frames[0].size)

        // Frame 1:
        // Length: 162 + 3 = 165 (0xA5)
        // Addr: 0x1401 + 120 (0x78) = 0x1479 -> 0x14 0x79
        assertEquals(0x5A.toByte(), frames[1][0])
        assertEquals(0xA5.toByte(), frames[1][1])
        assertEquals(165.toByte(), frames[1][2])
        assertEquals(0x82.toByte(), frames[1][3])
        assertEquals(0x14.toByte(), frames[1][4])
        assertEquals(0x79.toByte(), frames[1][5])
        assertEquals(168, frames[1].size)

        // Verify terminator 0xFFFF at end of last frame
        assertEquals(0xFF.toByte(), frames[1][frames[1].size - 2])
        assertEquals(0xFF.toByte(), frames[1][frames[1].size - 1])
    }

    @Test
    fun test900BytesLimitDiscardsOldestCharacters() {
        // 600 characters = 1200 bytes. Exceeds 900 bytes limit (450 chars).
        // It should discard first 150 characters and keep the last 450 characters.
        val prefix = "X".repeat(150)
        val suffix = "Y".repeat(450)
        val fullText = prefix + suffix

        val trimmed = SerialPortManager.trimToMaxBytes(fullText, SerialPortManager.MAX_TEXT_BYTES)
        assertEquals(450, trimmed.length)
        assertEquals(suffix, trimmed)

        // Payload: 450 chars * 2 bytes + 2 bytes 0xFFFF = 902 bytes
        // Chunked into:
        // Chunk 0: 240 bytes @ 0x1401
        // Chunk 1: 240 bytes @ 0x1401 + 120 (0x1479)
        // Chunk 2: 240 bytes @ 0x1401 + 240 (0x14F1)
        // Chunk 3: 182 bytes @ 0x1401 + 360 (0x1569)
        val frames = SerialPortManager.buildTextFrames(SerialPortManager.ADDR_ORIGINAL_TEXT, fullText)
        assertEquals(4, frames.size)

        assertEquals(0x14.toByte(), frames[0][4])
        assertEquals(0x01.toByte(), frames[0][5])

        assertEquals(0x14.toByte(), frames[1][4])
        assertEquals(0x79.toByte(), frames[1][5])

        assertEquals(0x14.toByte(), frames[2][4])
        assertEquals(0xF1.toByte(), frames[2][5])

        assertEquals(0x15.toByte(), frames[3][4])
        assertEquals(0x69.toByte(), frames[3][5])

        // Verify last 2 bytes are 0xFFFF
        assertEquals(0xFF.toByte(), frames[3][frames[3].size - 2])
        assertEquals(0xFF.toByte(), frames[3][frames[3].size - 1])
    }

    @Test
    fun testSurrogatePairHandlingOnBoundary() {
        // Emoji "\uD83D\uDE00" is 2 chars (4 bytes).
        // Case 1: Total 452 chars -> startIndex = 452 - 450 = 2, which lands on low surrogate (\uDE00).
        // It must skip the low surrogate and return the remaining 449 chars.
        val textWithOrphanLowSurrogate = "A\uD83D\uDE00" + "B".repeat(449)
        val trimmed1 = SerialPortManager.trimToMaxBytes(textWithOrphanLowSurrogate, 900)
        assertEquals(449, trimmed1.length)
        assertEquals("B".repeat(449), trimmed1)

        // Case 2: Total 451 chars -> startIndex = 451 - 450 = 1, which lands on high surrogate (\uD83D).
        // Both high and low surrogate are preserved -> total 450 chars.
        val textWithPreservedEmoji = "A\uD83D\uDE00" + "B".repeat(448)
        val trimmed2 = SerialPortManager.trimToMaxBytes(textWithPreservedEmoji, 900)
        assertEquals(450, trimmed2.length)
        assertEquals("\uD83D\uDE00" + "B".repeat(448), trimmed2)
    }

    @Test
    fun testPageSwitchCommands() {
        assertEquals("5A A5 07 82 00 84 5A 01 00 01", SerialPortManager.CMD_SWITCH_START_PAGE.toHexString())
        assertEquals("5A A5 07 82 00 84 5A 01 00 03", SerialPortManager.CMD_SWITCH_STOP_PAGE.toHexString())
    }

    @Test
    fun testButtonReturnCommands() {
        assertEquals("5A A5 06 83 20 01 01 00 02", SerialPortManager.CMD_START_BUTTON.toHexString())
        assertEquals("5A A5 06 83 20 01 01 00 01", SerialPortManager.CMD_STOP_BUTTON.toHexString())
    }

    @Test
    fun testSendIntervalAtLeast100ms() {
        assertTrue("Send interval must not be less than 100ms", SerialPortManager.SEND_INTERVAL_MS >= 100L)
    }
}
