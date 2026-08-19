package com.example

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.hardware.DevInputListener
import com.example.ui.LiveTranslateScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel

@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEBOUNCE_INTERVAL_MS = 300L
    }

    private val viewModel: MainViewModel by viewModels()

    private var lastKeyToggleTime = 0L

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleRecording()
        } else {
            Toast.makeText(this, "需要麦克风权限才能进行同传录音", Toast.LENGTH_SHORT).show()
        }
    }

    // Direct /dev/input/event1 listener for embedded board hardware keys
    private val devInputListener = DevInputListener(
        devicePath = "/dev/input/event1",
        targetKeyCode = DevInputListener.KEY_F1
    ) {
        handleHardwareKeyToggle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(android.content.Intent(this, AppLifecycleService::class.java))
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LiveTranslateScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.example.hardware.LedController.setMicState(false)
    }

    override fun onResume() {
        super.onResume()
        devInputListener.start()
    }

    override fun onPause() {
        super.onPause()
        devInputListener.stop()
    }

    /**
     * Intercept hardware key events dispatched by Android InputManager.
     * KEY_F1 (Linux scancode 59) is mapped to KeyEvent.KEYCODE_F1 (131).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_F1) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.d(TAG, "dispatchKeyEvent: KEYCODE_F1 pressed")
                handleHardwareKeyToggle()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F1) {
            if (event?.repeatCount == 0) {
                Log.d(TAG, "onKeyDown: KEYCODE_F1 pressed")
                handleHardwareKeyToggle()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Handle microphone toggle triggered by hardware button (with debounce and permission check).
     */
    private fun handleHardwareKeyToggle() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyToggleTime < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "Key toggle debounced (delta=${now - lastKeyToggleTime}ms)")
            return
        }
        lastKeyToggleTime = now

        Log.i(TAG, "Hardware key toggling microphone state")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.toggleRecording()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}


