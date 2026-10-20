package com.example.hiltible.work

import android.content.Context
import android.location.Location
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.tasks.await
import com.example.hiltible.ble.BleTagDetector
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class LocationUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val bleTagDetector: BleTagDetector
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Get current location using lastLocation for simplicity
            val location = fusedLocationClient.lastLocation.await()
            
            if (location != null) {
                // Update cached location in BleTagDetector
                bleTagDetector.updateCachedLocation(location)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
