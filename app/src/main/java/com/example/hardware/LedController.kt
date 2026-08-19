package com.example.hardware

import android.util.Log
import java.io.File

/**
 * Controller for hardware status LEDs via sysfs.
 * - Mic ON:  /sys/class/leds/pi15_out/brightness = 1, /sys/class/leds/pi12_out/brightness = 0
 * - Mic OFF: /sys/class/leds/pi15_out/brightness = 0, /sys/class/leds/pi12_out/brightness = 1
 */
object LedController {
    private const val TAG = "LedController"

    var pathLedPi15: String = "/sys/class/leds/pi15_out/brightness"
    var pathLedPi12: String = "/sys/class/leds/pi12_out/brightness"

    /**
     * Update LED states based on microphone/recording status:
     * - isMicOn == true  -> PI15=1, PI12=0
     * - isMicOn == false -> PI15=0, PI12=1
     */
    fun setMicState(isMicOn: Boolean) {
        val pi15Val = if (isMicOn) 1 else 0
        val pi12Val = if (isMicOn) 0 else 1

        writeBrightness(pathLedPi15, pi15Val)
        writeBrightness(pathLedPi12, pi12Val)
        Log.i(TAG, "setMicState(isMicOn=$isMicOn) -> pi15=$pi15Val, pi12=$pi12Val")
    }

    fun writeBrightness(path: String, value: Int): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.d(TAG, "LED file $path does not exist")
                return false
            }
            if (!file.canWrite()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "chmod 666 $path")).waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "chmod failed for $path: ${e.message}")
                }
            }
            file.writeText(value.toString())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct write failed for $path ($value), attempting shell echo fallback: ${e.message}")
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo $value > $path"))
                proc.waitFor() == 0
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to write $value to $path: ${ex.message}")
                false
            }
        }
    }
}
