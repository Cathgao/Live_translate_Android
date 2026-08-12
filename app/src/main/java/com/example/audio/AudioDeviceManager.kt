package com.example.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.model.AudioDeviceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioDeviceManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _availableDevices = MutableStateFlow<List<AudioDeviceItem>>(emptyList())
    val availableDevices: StateFlow<List<AudioDeviceItem>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDeviceItem?>(null)
    val selectedDevice: StateFlow<AudioDeviceItem?> = _selectedDevice.asStateFlow()

    private var rawAudioDeviceMap = mutableMapOf<Int, AudioDeviceInfo>()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        refreshDevices()
    }

    fun refreshDevices() {
        val devices = mutableListOf<AudioDeviceItem>()
        rawAudioDeviceMap.clear()

        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { 
                it.type != 25 && // TYPE_REMOTE_SUBMIX
                it.type != 14 && // TYPE_FM_TUNER
                it.type != 17 && // TYPE_TV_TUNER
                it.type != 20    // TYPE_IP
            }

        for (device in inputDevices) {
            val typeName = getDeviceTypeName(device.type)
            val isUsb = isUsbDevice(device.type)

            var productName = device.productName?.toString() ?: ""
            if (productName.isBlank() || productName == Build.MODEL || productName == Build.PRODUCT || productName == Build.DEVICE) {
                productName = if (device.address.isNotBlank() && device.address != "0" && device.address != "null") {
                    "$typeName (${device.address})"
                } else {
                    typeName
                }
            }

            rawAudioDeviceMap[device.id] = device

            val sampleRates = if (device.sampleRates.isNotEmpty()) {
                device.sampleRates.joinToString(", ") + " Hz"
            } else {
                "16000, 24000, 44100 Hz"
            }

            devices.add(
                AudioDeviceItem(
                    id = device.id,
                    name = productName,
                    typeName = typeName,
                    isUsb = isUsb,
                    isSelected = false,
                    supportedSampleRates = sampleRates
                )
            )
        }

        if (devices.isEmpty()) {
            // Fallback default built-in mic entry if system returns empty
            devices.add(
                AudioDeviceItem(
                    id = -1,
                    name = "Default Internal Microphone",
                    typeName = "Built-in Microphone",
                    isUsb = false,
                    isSelected = true
                )
            )
        }

        // Auto-select external device if available
        val externalDevice = devices.firstOrNull { !it.typeName.contains("Built-in") }
        val currentSelected = _selectedDevice.value

        val targetSelected = when {
            // Initial startup
            currentSelected == null -> externalDevice?.id ?: devices.first().id
            // Currently selected device was unplugged
            !devices.any { it.id == currentSelected.id } -> externalDevice?.id ?: devices.first().id
            // New external device plugged in while we were using built-in
            externalDevice != null && currentSelected.typeName.contains("Built-in") -> externalDevice.id
            // Otherwise keep current selection
            else -> currentSelected.id
        }

        val updatedList = devices.map { item ->
            item.copy(isSelected = item.id == targetSelected)
        }

        _availableDevices.value = updatedList
        _selectedDevice.value = updatedList.firstOrNull { it.isSelected }
    }

    fun selectDevice(deviceId: Int) {
        val updatedList = _availableDevices.value.map { item ->
            item.copy(isSelected = item.id == deviceId)
        }
        _availableDevices.value = updatedList
        _selectedDevice.value = updatedList.firstOrNull { it.isSelected }
    }

    fun getRawAudioDeviceInfo(deviceId: Int): AudioDeviceInfo? {
        return rawAudioDeviceMap[deviceId]
    }

    private fun isUsbDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset Microphone"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO Headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony Audio"
            25 -> "Remote Submix (Loopback)" // TYPE_REMOTE_SUBMIX
            21 -> "Bus Audio Input" // TYPE_BUS
            15 -> "Built-in Microphone"
            19 -> "AUX Line Input" // TYPE_AUX_LINE
            14 -> "FM Tuner"
            17 -> "TV Tuner"
            9 -> "HDMI Input"
            20 -> "IP Audio"
            4 -> "Wired Headphones"
            5 -> "Analog Line In"
            6 -> "Digital Line In"
            8 -> "Bluetooth A2DP"
            else -> "Audio Input (Type $type)"
        }
    }
}
