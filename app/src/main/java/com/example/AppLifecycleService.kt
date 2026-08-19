package com.example

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.hardware.LedController

/**
 * Background service to monitor task lifecycle and ensure hardware LEDs and states
 * are properly turned off when the user swipes away the app in Recent Tasks.
 */
class AppLifecycleService : Service() {

    companion object {
        private const val TAG = "AppLifecycleService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed from Recents. Setting LED state to OFF.")
        LedController.setMicState(false)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AppLifecycleService destroyed. Ensuring LED state is OFF.")
        LedController.setMicState(false)
    }
}
