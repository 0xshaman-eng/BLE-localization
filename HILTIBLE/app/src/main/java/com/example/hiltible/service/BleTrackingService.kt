package com.example.hiltible.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.hiltible.R
import com.example.hiltible.ble.BleTagDetector
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class BleTrackingService : LifecycleService() {

    companion object {
        private const val TAG = "BleTrackingService"
        private const val CHANNEL_ID = "ble_tracking_channel"
        private const val NOTIFICATION_ID = 1
    }

    @Inject
    lateinit var bleTagDetector: BleTagDetector

    private var screenOffReceiver: ScreenOffReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tagCacheRefreshJob: kotlinx.coroutines.Job? = null
    private var bleScanRestartJob: kotlinx.coroutines.Job? = null
    private var wakeLockRefreshJob: kotlinx.coroutines.Job? = null
    private var isServiceRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        createNotificationChannel()
        registerScreenOffReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "Service onStartCommand() - isServiceRunning: $isServiceRunning")
        
        // Start foreground service with proper type for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        
        // Only initialize components if not already running
        if (!isServiceRunning) {
            Log.d(TAG, "Initializing service components for the first time")
            
            // Start BLE scanning
            bleTagDetector.refreshTagCache()
            bleTagDetector.startScanning()
            
            // Start periodic BLE scan restart every 60 seconds to handle Android stopping background scans
            startBleScanRestart()
            
            // Start periodic WakeLock refresh every 8 minutes to handle Android releasing locks
            startWakeLockRefresh()
            
            // Acquire WakeLock for screen-off operation
            acquireWakeLock()
            
            isServiceRunning = true
            Log.d(TAG, "Service fully started with all components")
        } else {
            Log.d(TAG, "Service already running - skipping initialization")
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy() - Stopping BLE scanning")
        isServiceRunning = false
        
        try {
            bleTagDetector.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up BleTagDetector", e)
        }
        stopBleScanRestart()
        stopWakeLockRefresh()
        releaseWakeLock()
        unregisterScreenOffReceiver()
        
        // Clean up any remaining resources
        bleScanRestartJob?.cancel()
        bleScanRestartJob = null
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        
        super.onDestroy()
        Log.d(TAG, "Service destroyed cleanly")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed - System killed the service")
        // Restart the service
        val restartServiceIntent = Intent(applicationContext, BleTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartServiceIntent)
        } else {
            startService(restartServiceIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks nearby Hilti BLE tags"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String = "Scanning for Hilti tags..."): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Tracking Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun registerScreenOffReceiver() {
        screenOffReceiver = ScreenOffReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
        Log.d(TAG, "Screen off receiver registered")
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Screen off receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen off receiver", e)
            }
        }
    }

    
    private fun startTagCacheRefresh() {
        tagCacheRefreshJob = lifecycleScope.launch {
            while (true) {
                try {
                    delay(30000) // Wait 30 seconds
                    bleTagDetector.refreshTagCache()
                    Log.d(TAG, "Tag cache refreshed automatically")
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing tag cache", e)
                }
            }
        }
    }

    private fun stopTagCacheRefresh() {
        tagCacheRefreshJob?.cancel()
        tagCacheRefreshJob = null
    }

    private fun startBleScanRestart() {
        bleScanRestartJob = lifecycleScope.launch {
            while (true) {
                try {
                    delay(60000) // Wait 60 seconds
                    Log.d(TAG, "Restarting BLE scan to handle Android background restrictions")
                    bleTagDetector.stopScanning()
                    delay(1000) // Brief pause before restart
                    bleTagDetector.startScanning()
                    Log.d(TAG, "BLE scan restarted successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting BLE scan", e)
                    delay(60000) // Wait 60 seconds even on error
                }
            }
        }
    }

    private fun stopBleScanRestart() {
        bleScanRestartJob?.cancel()
        bleScanRestartJob = null
    }

    private fun startWakeLockRefresh() {
        wakeLockRefreshJob = lifecycleScope.launch {
            while (true) {
                try {
                    delay(8 * 60 * 1000L) // Wait 8 minutes
                    Log.d(TAG, "Refreshing WakeLock to handle Android releasing locks")
                    releaseWakeLock()
                    delay(1000) // Brief pause
                    acquireWakeLock()
                    Log.d(TAG, "WakeLock refreshed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing WakeLock", e)
                    delay(8 * 60 * 1000L) // Wait 8 minutes even on error
                }
            }
        }
    }

    private fun stopWakeLockRefresh() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                Log.d(TAG, "WakeLock already held")
                return
            }
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BLETrackingService:WakeLock"
            ).apply {
                acquire(10 * 60 * 1000L /* 10 minutes */) // Acquire with timeout
                // Set reference to allow reacquisition
                setReferenceCounted(false)
            }
            Log.d(TAG, "WakeLock acquired (indefinite)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    inner class ScreenOffReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen turned OFF - BLE scanning continues in background")
                    updateNotification("Scanning in background...")
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen turned ON")
                    updateNotification("Scanning for Hilti tags...")
                }
            }
        }
    }
}
