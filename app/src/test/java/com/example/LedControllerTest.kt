package com.example

import com.example.hardware.LedController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMicStateOn() {
        val pi15File = tempFolder.newFile("pi15_out_brightness")
        val pi12File = tempFolder.newFile("pi12_out_brightness")

        LedController.pathLedPi15 = pi15File.absolutePath
        LedController.pathLedPi12 = pi12File.absolutePath

        LedController.setMicState(true)

        assertEquals("1", pi15File.readText().trim())
        assertEquals("0", pi12File.readText().trim())
    }

    @Test
    fun testMicStateOff() {
        val pi15File = tempFolder.newFile("pi15_out_brightness_off")
        val pi12File = tempFolder.newFile("pi12_out_brightness_off")

        LedController.pathLedPi15 = pi15File.absolutePath
        LedController.pathLedPi12 = pi12File.absolutePath

        LedController.setMicState(false)

        assertEquals("0", pi15File.readText().trim())
        assertEquals("1", pi12File.readText().trim())
    }

    @Test
    fun testNonExistentFileHandledGracefully() {
        LedController.pathLedPi15 = "/non/existent/path/pi15"
        LedController.pathLedPi12 = "/non/existent/path/pi12"

        // Should not throw exceptions
        LedController.setMicState(true)
        LedController.setMicState(false)
    }
}
