package com.example.hiltible

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.hiltible.service.BleTrackingService

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_PACKAGE_REPLACED) {
            
            Log.d(TAG, "Boot/package update detected - starting BLE tracking service")
            
            try {
                val serviceIntent = Intent(context, BleTrackingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "BLE tracking service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE tracking service", e)
            }
        }
    }
}
