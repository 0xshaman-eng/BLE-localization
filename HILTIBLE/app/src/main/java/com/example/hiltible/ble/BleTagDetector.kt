package com.example.hiltible.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.hiltible.R
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

class BleTagDetector(
    private val context: Context,
    private val toolsRef: DatabaseReference,
    private val sightingsRef: DatabaseReference,
    private val fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient,
    private val anchorSelector: com.example.hiltible.ble.anchor.AnchorSelector,
    private val anchorSelectionRepository: com.example.hiltible.ble.anchor.AnchorSelectionRepository
) {
    
    companion object {
        private const val TAG = "BleTagDetector"
        private const val UNMAPPED_TAG_CHANNEL_ID = "unmapped_tag_channel"
        private const val UNMAPPED_TAG_NOTIFICATION_ID = 1001
        private const val BATCH_INTERVAL_MS = 5_000L
        private const val LOCATION_UPDATE_INTERVAL_MS = 5_000L
        private const val MAX_IDLE_CYCLES = 2
        private const val PREFS_NAME = "BleTagDetectorPrefs"
        private const val PREF_ANCHOR_ID = "anchorId"
        private const val PREF_ANCHOR_NAME = "anchorName"
        private const val DEFAULT_STRONGEST_WINDOW_MS = 1000L
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var isScanning = false
    
    // Cache for tag-to-tool mappings for quick lookup
    private val mappedTags = ConcurrentHashMap<String, String>()
    private val tagLock = Any()
    
    // Accumulated tags from scan results (cleared every batch cycle)
    private val pendingTags = ConcurrentHashMap.newKeySet<String>()

    // Most recent detection timestamp per tag (ms precision)
    private val tagDetectionTimes = ConcurrentHashMap<String, Long>()
    
    // RSSI values per tag (strongest in current batch)
    private val tagRssiMap = ConcurrentHashMap<String, Int>()
    
    // Session tags for manual scan (detected after pressing scan button)
    private val sessionTags = ConcurrentHashMap.newKeySet<String>()
    private val sessionStartTime = AtomicLong(0)
    
    // Tag type tracking (identified by BLE filter manufacturer ID)
    private val tagTypes = ConcurrentHashMap<String, String>() // Maps tagId -> "hilti"|"ela"|"em"|"milwaukee"
    
    // Cache last known location for background updates
    private var lastKnownLocation: android.location.Location? = null
    
    // Use ScheduledExecutorService instead of Handler to prevent memory leaks
    private var executorService: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val isShutdown = AtomicBoolean(false)
    
    // Adaptive location update variables
    private var locationUpdateJob: kotlinx.coroutines.Job? = null
    private var locationUpdateExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    // Starts stopped so the first mapped tag detection kicks off location updates
    private val isLocationShutdown = AtomicBoolean(true)
    private var consecutiveIdleCycles = 0
    private val locationLock = Any()
    
    // Track last unmapped tag notification to avoid spam
    @Volatile
    private var lastUnmappedTagNotification = ""
    @Volatile
    private var lastUnmappedTagTime = 0L
    private val UNMAPPED_TAG_THROTTLE_MS = 30_000L
    private val notificationLock = Any()
    private var tagCacheListener: ValueEventListener? = null
    
    // Anchor identity (per-install UUID and friendly name)
    private val anchorId: String
    private var anchorName: String
    
    // Configurable window for multi-anchor RSSI winner selection (used by backend)
    @Volatile
    private var strongestWindowMs: Long = DEFAULT_STRONGEST_WINDOW_MS
    
    init {
        // Initialize anchor identity
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Get or create anchor ID
        anchorId = prefs.getString(PREF_ANCHOR_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(PREF_ANCHOR_ID, newId).apply()
            Log.d(TAG, "Generated new anchor ID: $newId")
            newId
        }
        
        // Get or create anchor name (default to device model)
        anchorName = prefs.getString(PREF_ANCHOR_NAME, null) ?: run {
            val defaultName = "Anchor-${Build.MODEL}"
            prefs.edit().putString(PREF_ANCHOR_NAME, defaultName).apply()
            Log.d(TAG, "Set default anchor name: $defaultName")
            defaultName
        }
        
        Log.d(TAG, "Anchor identity: ID=$anchorId, Name=$anchorName")
        
        createUnmappedTagNotificationChannel()
        startTagCacheListener()
    }

    // Public setters for configuration
    fun setAnchorName(name: String) {
        anchorName = name
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_ANCHOR_NAME, name).apply()
        Log.d(TAG, "Anchor name updated to: $name")
    }
    
    fun getAnchorId(): String = anchorId
    fun getAnchorName(): String = anchorName
    
    fun setStrongestWindowMs(windowMs: Long) {
        strongestWindowMs = windowMs
        Log.d(TAG, "Strongest window updated to: ${windowMs}ms")
    }
    
    private fun startTagCacheListener() {
        if (tagCacheListener != null) return

        tagCacheListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    synchronized(tagLock) {
                        mappedTags.clear()
                        if (snapshot.exists()) {
                            snapshot.children.forEach { toolSnapshot ->
                                val toolId = toolSnapshot.key
                                val tagUuid = toolSnapshot.child("tagUuid").getValue(String::class.java)
                                if (!toolId.isNullOrEmpty() && !tagUuid.isNullOrEmpty()) {
                                    mappedTags[tagUuid] = toolId
                                }
                            }
                        }
                    }
                    Log.d(TAG, "Tag cache updated via listener: ${mappedTags.size} mappings loaded")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating tag cache from listener", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Tag cache listener cancelled", error.toException())
            }
        }

        toolsRef.addValueEventListener(tagCacheListener!!)
        Log.d(TAG, "Tag cache listener registered")
    }

    private fun stopTagCacheListener() {
        tagCacheListener?.let {
            toolsRef.removeEventListener(it)
            tagCacheListener = null
            Log.d(TAG, "Tag cache listener removed")
        }
    }
    
    // --- Scan callback: accumulate tags with RSSI and exact timestamp ---
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            
            try {
                val manufacturerData = result.scanRecord?.manufacturerSpecificData
                val rssi = result.rssi
                
                if (manufacturerData != null) {
                    // Detection time with millisecond precision
                    val detectionTime = System.currentTimeMillis()

                    // Check for Hilti tags
                    val hiltiData = manufacturerData.get(HiltiPacketParser.MANUFACTURER_ID)
                    if (hiltiData != null && hiltiData.size >= 8) {
                        val packet = HiltiPacketParser.parse(hiltiData)
                        if (packet != null) {
                            // Accumulate tag
                            pendingTags.add(packet.tagUuid)
                            tagTypes[packet.tagUuid] = "hilti"
                            
                            // Track most recent RSSI in this batch (always overwrite with latest)
                            tagRssiMap[packet.tagUuid] = rssi
                            tagDetectionTimes[packet.tagUuid] = detectionTime
                            Log.d(TAG, "Tag ${packet.tagUuid}: RSSI=$rssi at t=$detectionTime")
                            
                            // Store in session tags if scan session is active
                            if (sessionStartTime.get() > 0) {
                                sessionTags.add(packet.tagUuid)
                            }
                        }
                    }
                    
                    // Check for ELA tags
                    val elaData = manufacturerData.get(ElaPacketParser.MANUFACTURER_ID)
                    if (elaData != null && elaData.isNotEmpty()) {
                        val packet = ElaPacketParser.parse(elaData, result.scanRecord)
                        if (packet != null) {
                            // Accumulate tag
                            pendingTags.add(packet.tagId)
                            tagTypes[packet.tagId] = "ela"
                            
                            // Track most recent RSSI in this batch (always overwrite with latest)
                            tagRssiMap[packet.tagId] = rssi
                            tagDetectionTimes[packet.tagId] = detectionTime
                            Log.d(TAG, "Tag ${packet.tagId}: RSSI=$rssi at t=$detectionTime")
                            
                            // Store in session tags if scan session is active
                            if (sessionStartTime.get() > 0) {
                                sessionTags.add(packet.tagId)
                            }
                        }
                    }

                    // Check for Milwaukee tags
                    val milwaukeeData = manufacturerData.get(MilwaukeePacketParser.MANUFACTURER_ID)
                    if (milwaukeeData != null && milwaukeeData.isNotEmpty()) {
                        val packet = MilwaukeePacketParser.parse(milwaukeeData)
                        if (packet != null) {
                            pendingTags.add(packet.tagUuid)
                            tagTypes[packet.tagUuid] = "milwaukee"
                            tagRssiMap[packet.tagUuid] = rssi
                            tagDetectionTimes[packet.tagUuid] = detectionTime
                            Log.d(TAG, "Tag ${packet.tagUuid}: RSSI=$rssi at t=$detectionTime")
                            if (sessionStartTime.get() > 0) {
                                sessionTags.add(packet.tagUuid)
                            }
                        }
                    }
                    
                    // Check for EM tags (AltBeacon format)
                    val emData = manufacturerData.get(EmPacketParser.MANUFACTURER_ID)
                    if (emData != null && emData.size >= 24) {
                        // AltBeacon format: check signature and parse with device MAC
                        if (EmPacketParser.isAltBeaconFormat(emData)) {
                            val packet = EmPacketParser.parse(emData, result.device)
                            if (packet != null) {
                                // Accumulate tag using raw MAC hex (for Firebase matching)
                                pendingTags.add(packet.tagId)
                                tagTypes[packet.tagId] = "em"
                                
                                // Track most recent RSSI in this batch (always overwrite with latest)
                                tagRssiMap[packet.tagId] = rssi
                                tagDetectionTimes[packet.tagId] = detectionTime
                                Log.d(TAG, "EM AltBeacon Tag ${packet.tagId}: RSSI=$rssi at t=$detectionTime")
                                
                                // Store in session tags if scan session is active
                                if (sessionStartTime.get() > 0) {
                                    sessionTags.add(packet.tagId)
                                }
                            }
                        }
                    } else {
                        // Fallback: Check raw scan record bytes for EM manufacturer ID pattern
                        val scanRecord = result.scanRecord
                        if (scanRecord != null) {
                            val rawBytes = scanRecord.getBytes()
                            if (rawBytes != null && rawBytes.size >= 9) {
                                // Look for pattern: FF 5A 00 (FF=manufacturer type, 5A00=EM ID in little-endian)
                                // Byte layout at offset i:
                                // i+0: 0xFF (AD Type)
                                // i+1: 0x5A (Company ID byte 1)
                                // i+2: 0x00 (Company ID byte 2)
                                // i+3: 0x58 (MAC byte 1) <-- MAC starts here
                                // i+4: 0xC3 (MAC byte 2)
                                // i+5: 0x56 (MAC byte 3)
                                // i+6: 0x08 (MAC byte 4)
                                // i+7: 0xEE (MAC byte 5)
                                // i+8: 0xB0 (MAC byte 6)
                                for (i in 0 until rawBytes.size - 8) {
                                    if (rawBytes[i] == 0xFF.toByte() && 
                                        rawBytes[i + 1] == 0x5A.toByte() && 
                                        rawBytes[i + 2] == 0x00.toByte()) {
                                        
                                        // Found EM manufacturer ID, extract MAC from bytes i+3 to i+8 (copyOfRange is exclusive of upper bound)
                                        if (i + 8 < rawBytes.size) {
                                            val macBytes = rawBytes.copyOfRange(i + 3, i + 9)
                                            val macHex = macBytes.joinToString("") { "%02X".format(it) }
                                            
                                            Log.d(TAG, "EM fallback: Found manufacturer ID at offset $i, MAC: $macHex")
                                            
                                            pendingTags.add(macHex)
                                            tagTypes[macHex] = "em"
                                            tagRssiMap[macHex] = rssi
                                            tagDetectionTimes[macHex] = detectionTime
                                            Log.d(TAG, "EM fallback: Tag $macHex: RSSI=$rssi at t=$detectionTime")
                                            
                                            // Store in session tags if scan session is active
                                            if (sessionStartTime.get() > 0) {
                                                sessionTags.add(macHex)
                                            }
                                            break // Found EM tag, stop searching
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing scan result", e)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
        }
    }
    
    // --- Batch processing: runs every 5 seconds ---
    private fun startBatchProcessing() {
        if (isShutdown.get()) {
            isShutdown.set(false)
        }

        if (executorService.isShutdown) {
            executorService = Executors.newSingleThreadScheduledExecutor()
        }
        
        try {
            executorService.scheduleWithFixedDelay({
                if (!isShutdown.get()) {
                    try {
                        processPendingTags()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in batch processing", e)
                    }
                }
            }, BATCH_INTERVAL_MS, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS)
            
            Log.d(TAG, "Started batch processing (every ${BATCH_INTERVAL_MS / 1000}s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start batch processing", e)
        }
    }
    
    private fun stopBatchProcessing() {
        isShutdown.set(true)
        try {
            executorService.shutdown()
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
            Log.d(TAG, "Batch processing stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping batch processing", e)
            executorService.shutdownNow()
        }
    }
    
    private fun processPendingTags() {
        // Take a snapshot and clear the pending set
        val tagsToProcess = pendingTags.toSet()
        pendingTags.clear()
        
        var hasMappedTagsInCycle = false
        
        if (tagsToProcess.isEmpty()) {
            Log.d(TAG, "No tags detected in this cycle")
            onNoMappedTagsDetected()
            return
        }
        
        Log.d(TAG, "Processing batch: ${tagsToProcess.size} unique tag(s): $tagsToProcess")
        
        // Get current location ONCE for this entire batch
        val location = synchronized(locationLock) { lastKnownLocation }
        
        val isValidLocation = location != null && !(location.latitude == 0.0 && location.longitude == 0.0)
        
        if (!isValidLocation) {
            Log.w(TAG, "No valid location available - UI updates will proceed, but database syncing will be skipped")
        } else {
            Log.d(TAG, "Using location: (${location!!.latitude}, ${location.longitude})")
        }
        
        // Process each tag (pass location if valid, null otherwise)
        val locationToUse = if (isValidLocation) location else null
        for (tagUuid in tagsToProcess) {
            // Use the actual most recent detection time and RSSI; fall back to defaults if missing
            val detectionTime = tagDetectionTimes[tagUuid] ?: System.currentTimeMillis()
            val rssi = tagRssiMap[tagUuid] ?: -100 // Default to very weak if missing
            
            val isMapped = processTag(tagUuid, locationToUse, detectionTime, rssi)
            if (isMapped) {
                hasMappedTagsInCycle = true
                // Immediate cleanup for mapped tags - data consumed
                tagDetectionTimes.remove(tagUuid)
                tagRssiMap.remove(tagUuid)
            }
        }
        
        // Handle adaptive location updates based on mapped tag detection
        if (hasMappedTagsInCycle) {
            onMappedTagDetected()
        } else {
            onNoMappedTagsDetected()
        }
    }
    
    private fun processTag(tagUuid: String, location: android.location.Location?, detectionTimestamp: Long, rssi: Int): Boolean {
        try {
            // Check cache first
            val cachedToolId = synchronized(tagLock) { mappedTags[tagUuid] }
            
            if (cachedToolId != null) {
                Log.d(TAG, "Tag $tagUuid found in cache for tool $cachedToolId (RSSI=$rssi)")
                if (location != null) {
                    updateDatabaseWithLocation(cachedToolId, tagUuid, location, detectionTimestamp, rssi)
                }
                return true // Tag is mapped
            } else {
                // Query Firebase on background thread
                try {
                    toolsRef.orderByChild("tagUuid").equalTo(tagUuid).get()
                        .addOnSuccessListener { snapshot ->
                            try {
                                if (snapshot.exists() && snapshot.childrenCount > 0) {
                                    val toolEntry = snapshot.children.firstOrNull()
                                    if (toolEntry != null) {
                                        val toolId = toolEntry.key
                                        if (toolId != null && toolId.isNotEmpty()) {
                                            // Update cache
                                            synchronized(tagLock) {
                                                mappedTags[tagUuid] = toolId
                                            }
                                            Log.d(TAG, "Tag $tagUuid mapped to tool $toolId - updating location (RSSI=$rssi)")
                                            if (location != null) {
                                                updateDatabaseWithLocation(toolId, tagUuid, location, detectionTimestamp, rssi)
                                            }
                                            // Async mapped-tag path: clean up detection data
                                            tagDetectionTimes.remove(tagUuid)
                                            tagRssiMap.remove(tagUuid)
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "Tag $tagUuid not mapped to any tool")
                                    showUnmappedTagNotification(tagUuid)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing Firebase result for tag $tagUuid", e)
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to check if tag $tagUuid is mapped", e)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initiating Firebase query for tag $tagUuid", e)
                }
                return false // Tag not found in cache, async check in progress
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing tag $tagUuid", e)
            return false
        }
    }
    
    private fun updateDatabaseWithLocation(toolId: String, tagUuid: String, location: android.location.Location, detectionTimestamp: Long, rssi: Int) {
        try {
            val lat = location.latitude
            val lng = location.longitude
            
            Log.d(TAG, "Updating tool $toolId with location: ($lat, $lng), detectedAt=$detectionTimestamp, RSSI=$rssi, anchor=$anchorName")
            
            // Update tool location with RSSI and anchor identity (dumb app - just send data)
            val updates = mapOf<String, Any>(
                "lastLat" to lat,
                "lastLng" to lng,
                "lastSeenAt" to detectionTimestamp,
                "lastRssi" to rssi,
                "lastAnchorId" to anchorId,
                "lastAnchorName" to anchorName
            )
            
            toolsRef.child(toolId).updateChildren(updates)
                .addOnSuccessListener {
                    Log.d(TAG, "SUCCESS: Updated tool $toolId location to ($lat, $lng) with RSSI=$rssi from anchor $anchorName")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "FAILED to update tool $toolId location", e)
                }
            
            // Also log sighting with RSSI and anchor identity (append-only)
            val sighting = mapOf<String, Any>(
                "tagUuid" to tagUuid,
                "anchorLat" to lat,
                "anchorLng" to lng,
                "timestamp" to detectionTimestamp,
                "rssi" to rssi,
                "anchorId" to anchorId,
                "anchorName" to anchorName
            )
            sightingsRef.push().setValue(sighting)
                .addOnSuccessListener {
                    Log.d(TAG, "SUCCESS: Logged sighting for tag $tagUuid with RSSI=$rssi from anchor $anchorName")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "FAILED to log sighting for tag $tagUuid", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating database for tool $toolId", e)
        }
    }
    
    fun updateCachedLocation(location: android.location.Location) {
        synchronized(locationLock) {
            lastKnownLocation = location
            Log.d(TAG, "Cached location updated: (${location.latitude}, ${location.longitude})")
        }
    }
    
    private fun startAdaptiveLocationUpdates() {
        if (isLocationShutdown.get()) {
            isLocationShutdown.set(false)
        }

        if (locationUpdateExecutor.isShutdown) {
            locationUpdateExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        
        try {
            locationUpdateExecutor.scheduleWithFixedDelay({
                if (!isLocationShutdown.get()) {
                    try {
                        requestLocationUpdate()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in adaptive location update", e)
                    }
                }
            }, 0, LOCATION_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            
            Log.d(TAG, "Started adaptive location updates (every ${LOCATION_UPDATE_INTERVAL_MS / 1000}s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start adaptive location updates", e)
        }
    }
    
    private fun stopAdaptiveLocationUpdates() {
        isLocationShutdown.set(true)
        try {
            locationUpdateExecutor.shutdown()
            if (!locationUpdateExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                locationUpdateExecutor.shutdownNow()
            }
            Log.d(TAG, "Adaptive location updates stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping adaptive location updates", e)
            locationUpdateExecutor.shutdownNow()
        }
    }
    
    private fun requestLocationUpdate() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted. Cannot request location update.")
            return
        }

        try {
            val cancellationTokenSource = CancellationTokenSource()
            locationUpdateJob = kotlinx.coroutines.GlobalScope.launch {
                try {
                    val location = fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY, 
                        cancellationTokenSource.token
                    ).await()
                    
                    if (location != null) {
                        Log.d(TAG, "Got adaptive location: ${location.latitude}, ${location.longitude}")
                        updateCachedLocation(location)
                    } else {
                        Log.e(TAG, "Failed to get adaptive location from GPS")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting adaptive location update", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating adaptive location request", e)
        }
    }
    
    private fun onMappedTagDetected() {
        consecutiveIdleCycles = 0
        if (isLocationShutdown.get()) {
            Log.d(TAG, "Mapped tag detected - starting adaptive location updates")
            startAdaptiveLocationUpdates()
        }
    }
    
    private fun onNoMappedTagsDetected() {
        consecutiveIdleCycles++
        Log.d(TAG, "No mapped tags detected - idle cycle: $consecutiveIdleCycles/$MAX_IDLE_CYCLES")
        
        if (consecutiveIdleCycles >= MAX_IDLE_CYCLES) {
            Log.d(TAG, "Stopping adaptive location updates after $MAX_IDLE_CYCLES idle cycles")
            stopAdaptiveLocationUpdates()
        }
    }
    
    fun refreshTagCache() {
        try {
            toolsRef.get()
                .addOnSuccessListener { snapshot ->
                    try {
                        synchronized(tagLock) {
                            mappedTags.clear()
                            if (snapshot.exists()) {
                                snapshot.children.forEach { toolSnapshot ->
                                    try {
                                        val toolId = toolSnapshot.key
                                        val tagUuid = toolSnapshot.child("tagUuid").getValue(String::class.java)
                                        if (toolId != null && !toolId.isEmpty() && tagUuid != null && !tagUuid.isEmpty()) {
                                            mappedTags[tagUuid] = toolId
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error processing tool snapshot", e)
                                    }
                                }
                            }
                            Log.d(TAG, "Refreshed tag cache: ${mappedTags.size} mappings loaded")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing tag cache refresh", e)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to refresh tag cache", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating tag cache refresh", e)
        }
    }
    
    fun startScanning() {
        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted")
            return
        }
        
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner is null - Bluetooth may be off")
            return
        }
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        
        // Create ScanFilters for multiple manufacturer IDs to enable scanning when screen is off
        // Required for Android 8.1+ which blocks unfiltered scans when screen is off
        val hiltiFilter = ScanFilter.Builder()
            .setManufacturerData(HiltiPacketParser.MANUFACTURER_ID, byteArrayOf())
            .build()
        
        // ELA Blue Coin filter for manufacturer ID 1879 (0x0757)
        val elaFilter = ScanFilter.Builder()
            .setManufacturerData(ElaPacketParser.MANUFACTURER_ID, byteArrayOf())
            .build()
        
        // EM Microelectronic filter for manufacturer ID 90 (0x005A)
        val emFilter = ScanFilter.Builder()
            .setManufacturerData(EmPacketParser.MANUFACTURER_ID, byteArrayOf())
            .build()

        // Milwaukee filter for manufacturer ID 357 (0x0165)
        val milwaukeeFilter = ScanFilter.Builder()
            .setManufacturerData(MilwaukeePacketParser.MANUFACTURER_ID, byteArrayOf())
            .build()
        
        // Additional filter for testing multiple filters (manufacturer ID 67)
        val testFilter = ScanFilter.Builder()
            .setManufacturerData(67, byteArrayOf())
            .build()
        
        val filters = listOf(hiltiFilter, elaFilter, emFilter, milwaukeeFilter, testFilter)
        
        scanner.startScan(filters, scanSettings, scanCallback)
        isScanning = true
        Log.d(TAG, "BLE scanning started with multiple manufacturer filters - listening for Hilti, ELA, and EM tags...")
        
        // Start the 5-second batch processing timer
        startBatchProcessing()
    }
    
    fun stopScanning() {
        if (!isScanning) return
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) return
        
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            stopBatchProcessing()
            isScanning = false
            Log.d(TAG, "BLE scanning stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scanning", e)
            isScanning = false
        }
    }
    
        
    fun startScanSession() {
        // Clear previous session and start new one
        sessionTags.clear()
        sessionStartTime.set(System.currentTimeMillis())
        Log.d(TAG, "Started new scan session")
    }
    
    fun getSessionTags(): Set<String> {
        return sessionTags.toSet()
    }
    
    fun getTagType(tagId: String): String? {
        return tagTypes[tagId]
    }
    
    fun clearScanSession() {
        sessionTags.clear()
        sessionStartTime.set(0)
        Log.d(TAG, "Cleared scan session")
    }
    
    fun cleanup() {
        try {
            stopScanning()
            stopBatchProcessing()
            stopAdaptiveLocationUpdates()
            synchronized(tagLock) {
                mappedTags.clear()
            }
            pendingTags.clear()
            tagDetectionTimes.clear()
            tagRssiMap.clear()
            sessionTags.clear()
            sessionStartTime.set(0)
            Log.d(TAG, "BleTagDetector cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    private fun createUnmappedTagNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UNMAPPED_TAG_CHANNEL_ID,
                "Unmapped BLE Tags",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for detected BLE tags that are not mapped to tools"
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun showUnmappedTagNotification(tagUuid: String) {
        val now = System.currentTimeMillis()
        
        synchronized(notificationLock) {
            if (tagUuid == lastUnmappedTagNotification && now - lastUnmappedTagTime < UNMAPPED_TAG_THROTTLE_MS) {
                return
            }
            
            lastUnmappedTagNotification = tagUuid
            lastUnmappedTagTime = now
        }
        
        val notification = NotificationCompat.Builder(context, UNMAPPED_TAG_CHANNEL_ID)
            .setContentTitle("Unmapped Tag Detected")
            .setContentText("Tag $tagUuid is not mapped to any tool")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(5000)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(UNMAPPED_TAG_NOTIFICATION_ID, notification)
        
        Log.d(TAG, "Showed unmapped tag notification for: $tagUuid")
    }
}
