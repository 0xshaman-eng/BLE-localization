package com.example.hiltible.ble.anchor

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Holds RSSI history and metadata for a single anchor detecting a tag.
 * Thread-safe for concurrent RSSI updates from BLE scan callbacks.
 */
data class AnchorInfo(
    val anchorId: String,
    val anchorName: String,
    val maxHistorySize: Int = 10
) {
    companion object {
        private const val TAG = "AnchorInfo"
    }

    // FIFO queue: oldest RSSI values are removed when exceeding maxHistorySize
    private val rssiHistory = ConcurrentLinkedQueue<Int>()

    @Volatile
    private var lastDetectionTime: Long = 0L

    @Volatile
    private var averageRssi: Int = Int.MIN_VALUE

    @Volatile
    private var lastRssi: Int = Int.MIN_VALUE

    /**
     * Add RSSI reading to history and update statistics.
     * @param rssi New RSSI reading
     * @param timestamp Detection timestamp in ms
     */
    fun addRssiReading(rssi: Int, timestamp: Long) {
        lastRssi = rssi
        lastDetectionTime = timestamp

        // Maintain rolling window of last N readings
        rssiHistory.add(rssi)
        if (rssiHistory.size > maxHistorySize) {
            rssiHistory.poll() // Remove oldest
        }

        // Recalculate average
        recalculateAverage()
        Log.d(TAG, "Anchor $anchorId: Added RSSI=$rssi, avg=${averageRssi}, history_size=${rssiHistory.size}")
    }

    private fun recalculateAverage() {
        if (rssiHistory.isEmpty()) {
            averageRssi = Int.MIN_VALUE
            return
        }
        averageRssi = rssiHistory.average().toInt()
    }

    /**
     * Get average RSSI across history.
     */
    fun getAverageRssi(): Int = averageRssi

    /**
     * Get most recent RSSI reading.
     */
    fun getLastRssi(): Int = lastRssi

    /**
     * Get last detection time (ms since epoch).
     */
    fun getLastDetectionTime(): Long = lastDetectionTime

    /**
     * Check if anchor is considered "active" (detected within last N ms).
     */
    fun isActive(windowMs: Long, currentTime: Long = System.currentTimeMillis()): Boolean {
        return (currentTime - lastDetectionTime) < windowMs
    }

    /**
     * Get RSSI history for diagnostics.
     */
    fun getRssiHistory(): List<Int> = rssiHistory.toList()

    /**
     * Clear history (for testing or reset).
     */
    fun clearHistory() {
        rssiHistory.clear()
        averageRssi = Int.MIN_VALUE
        lastRssi = Int.MIN_VALUE
        lastDetectionTime = 0L
    }
}
