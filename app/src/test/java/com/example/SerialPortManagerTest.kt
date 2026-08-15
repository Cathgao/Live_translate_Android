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
        val frame = SerialPortManager.buildTextFrame(SerialPortManager.ADDR_ORIGINAL_TEXT, text)

        // "你好吗？" in UTF-16BE: 4F 60 59 7D 54 17 00 3F (8 bytes)
        // With 0xFFFF terminator: 4F 60 59 7D 54 17 00 3F FF FF (10 bytes)
        // Length = 10 + 3 = 13 (0x0D)
        // Expected: 5A A5 0D 82 10 01 4F 60 59 7D 54 17 00 3F FF FF
        val expectedHex = "5A A5 0D 82 10 01 4F 60 59 7D 54 17 00 3F FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(16, frame.size)
        assertEquals(0x0D.toByte(), frame[2])
    }

    @Test
    fun testTranslatedTextFrameGeneration() {
        val text = "Hello"
        val frame = SerialPortManager.buildTextFrame(SerialPortManager.ADDR_TRANSLATED_TEXT, text)

        // "Hello" in UTF-16BE: 00 48 00 65 00 6C 00 6C 00 6F (10 bytes)
        // With 0xFFFF terminator: 00 48 00 65 00 6C 00 6C 00 6F FF FF (12 bytes)
        // Length = 12 + 3 = 15 (0x0F)
        // Address: 14 01
        val expectedHex = "5A A5 0F 82 14 01 00 48 00 65 00 6C 00 6C 00 6F FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(18, frame.size)
    }

    @Test
    fun testEmptyTextFrameGeneration() {
        val frame = SerialPortManager.buildTextFrame(SerialPortManager.ADDR_ORIGINAL_TEXT, "")
        // Expected: 5A A5 05 82 10 01 FF FF
        val expectedHex = "5A A5 05 82 10 01 FF FF"
        assertEquals(expectedHex, frame.toHexString())
        assertEquals(8, frame.size)
    }

    @Test
    fun testPageSwitchCommands() {
        assertEquals("5A A5 07 82 00 84 5A 01 00 01", SerialPortManager.CMD_SWITCH_START_PAGE.toHexString())
        assertEquals("5A A5 07 82 00 84 5A 01 00 03", SerialPortManager.CMD_SWITCH_STOP_PAGE.toHexString())
    }

    @Test
    fun testButtonReturnCommands() {
        assertEquals("5A A5 06 83 20 01 01 00 01", SerialPortManager.CMD_START_BUTTON.toHexString())
        assertEquals("5A A5 06 83 20 01 01 00 02", SerialPortManager.CMD_STOP_BUTTON.toHexString())
    }

    @Test
    fun testSendIntervalAtLeast100ms() {
        assertTrue("Send interval must not be less than 100ms", SerialPortManager.SEND_INTERVAL_MS >= 100L)
    }
}
