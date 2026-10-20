package com.example.hiltible.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hiltible.ble.HiltiPacketParser
import com.example.hiltible.ble.ElaPacketParser
import com.example.hiltible.ble.EmPacketParser
import com.example.hiltible.data.repository.ToolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

data class DiscoveredTag(
    val tagUuid: String,
    val tagUuidDecimal: String,
    val deviceName: String?,
    val deviceAddress: String,
    val rssi: Int,
    val isHilti: Boolean,
    val isEla: Boolean = false,
    val isEm: Boolean = false,
    val assignedToolName: String? = null
)

enum class ScanState {
    IDLE, SCANNING, DONE
}

@HiltViewModel
class TagMappingViewModel @Inject constructor(
    private val repository: ToolRepository,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val bleTagDetector: com.example.hiltible.ble.BleTagDetector
) : ViewModel() {

    companion object {
        private const val TAG = "TagMappingVM"
        private const val SCAN_DURATION_MS = 10_000L
        
        // Convert hex tag UUID to decimal format for display (matches dashboard exactly)
        fun hexToDecimal(hexUuid: String): String {
            if (hexUuid.isEmpty()) return ""
            
            Log.d(TAG, "Converting hex to decimal: $hexUuid")
            
            return try {
                // Remove any non-hex characters and convert to lowercase
                val cleanHex = hexUuid.replace("[^0-9a-fA-F]".toRegex(), "").lowercase()
                
                // Check if it has the d322 prefix, if so, remove it for conversion
                val variablePart = if (cleanHex.startsWith("d322")) {
                    cleanHex.substring(4) // Remove d322 prefix
                } else {
                    cleanHex
                }
                
                Log.d(TAG, "Variable part for conversion: $variablePart")
                
                // Reverse bytes for little-endian: 18666c12 -> 126c6618
                val reversedHex = StringBuilder()
                for (i in variablePart.length - 2 downTo 0 step 2) {
                    reversedHex.append(variablePart.substring(i, i + 2))
                }
                
                // Convert entire hex number to decimal
                val decimal = reversedHex.toString().toLong(16)
                Log.d(TAG, "Decimal result: $decimal")
                
                decimal.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Conversion error: ${e.message}")
                hexUuid // Return original if conversion fails
            }
        }
        
        // Convert raw UUID to storage format (reversed like web app)
        // Raw: d32218666c12 -> Storage: d322126c6618
        fun rawUuidToStorageFormat(rawUuid: String): String {
            if (rawUuid.isEmpty()) return rawUuid
            
            Log.d(TAG, "Converting raw UUID to storage format: $rawUuid")
            
            return try {
                // Remove d322 prefix if present
                val cleanHex = rawUuid.replace("[^0-9a-fA-F]".toRegex(), "").lowercase()
                val variablePart = if (cleanHex.startsWith("d322")) {
                    cleanHex.substring(4)
                } else {
                    cleanHex
                }
                
                // Reverse bytes: 18666c12 -> 126c6618
                val reversedHex = StringBuilder()
                for (i in variablePart.length - 2 downTo 0 step 2) {
                    reversedHex.append(variablePart.substring(i, i + 2))
                }
                
                // Add d322 prefix back
                val storageFormat = "d322$reversedHex"
                Log.d(TAG, "Storage format: $storageFormat")
                
                storageFormat
            } catch (e: Exception) {
                Log.e(TAG, "Conversion error: ${e.message}")
                rawUuid
            }
        }

        // Convert decimal barcode string to storage format UUID
        // Rules: decimal -> hex -> pad to 8 chars -> reverse bytes -> add d322 prefix
        fun encodeBarcodeToTagUuid(decimalBarcode: String): String {
            if (decimalBarcode.isEmpty()) return ""

            return try {
                // Parse decimal to hex
                val decimalValue = decimalBarcode.toLong()
                val hexString = decimalValue.toString(16)

                // Left-pad to exactly 8 characters (4 bytes)
                val paddedHex = hexString.padStart(8, '0')

                // Reverse bytes (little-endian swap)
                val reversedHex = java.lang.StringBuilder()
                for (i in paddedHex.length - 2 downTo 0 step 2) {
                    reversedHex.append(paddedHex.substring(i, i + 2))
                }

                // Prefix with d322
                "d322$reversedHex"
            } catch (e: Exception) {
                Log.e(TAG, "Failed encoding barcode: $decimalBarcode", e)
                ""
            }
        }
    }

    private var _toolId: String? = savedStateHandle.get<String>("toolId")
    val toolId: String? get() = _toolId

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _discoveredTags = MutableStateFlow<List<DiscoveredTag>>(emptyList())
    val discoveredTags: StateFlow<List<DiscoveredTag>> = _discoveredTags.asStateFlow()

    private val _mappingComplete = MutableStateFlow(false)
    val mappingComplete: StateFlow<Boolean> = _mappingComplete.asStateFlow()

    private val _mappingError = MutableStateFlow<String?>(null)
    val mappingError: StateFlow<String?> = _mappingError.asStateFlow()

    private var isProcessingBarcode = false
    private val discoveredMap = mutableMapOf<String, DiscoveredTag>()

    private val handler = Handler(Looper.getMainLooper())
    private var scanStopRunnable: Runnable? = null

    fun startScan() {
        try {
            // Reset mapping complete state
            _mappingComplete.value = false
            // Clear previous results
            discoveredMap.clear()
            _discoveredTags.value = emptyList()
            _scanState.value = ScanState.SCANNING

            // Check permissions
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                _scanState.value = ScanState.DONE
                return
            }

            // Start new scan session - this clears previous session tags
            bleTagDetector.startScanSession()
            
            // Use centralized BleTagDetector for scanning
            // The background service should already be scanning, we just need to collect results
            Log.d(TAG, "Starting tag mapping scan - using centralized scanner")
            
            // Collect session tags from centralized scanner
            collectSessionTags()
            
            // Start collecting scan results for 10 seconds
            scanStopRunnable = Runnable {
                _scanState.value = ScanState.DONE
                Log.d(TAG, "Tag mapping scan completed")
            }
            handler.postDelayed(scanStopRunnable!!, SCAN_DURATION_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            _scanState.value = ScanState.DONE
        }
    }
    
    private fun collectSessionTags() {
        viewModelScope.launch {
            // Collect session tags every second for 10 seconds
            val endTime = System.currentTimeMillis() + SCAN_DURATION_MS
            
            while (System.currentTimeMillis() < endTime && _scanState.value == ScanState.SCANNING) {
                try {
                    val sessionTags = bleTagDetector.getSessionTags()
                    
                    // Convert to DiscoveredTag format
                    sessionTags.forEach { tagUuid ->
                        if (!discoveredMap.containsKey(tagUuid)) {
                            // Get tag type directly from BLE filter detection (single source of truth)
                            val tagType = bleTagDetector.getTagType(tagUuid) ?: "hilti" // Default fallback
                            
                            val isElaTag = (tagType == "ela")
                            val isEmTag = (tagType == "em")
                            val isHiltiTag = (tagType == "hilti")
                            
                            discoveredMap[tagUuid] = DiscoveredTag(
                                tagUuid = tagUuid,
                                tagUuidDecimal = when (tagType) {
                                    "ela" -> tagUuid // Display C ID directly
                                    "em" -> EmPacketParser.macToDecimal(tagUuid) // Convert MAC to decimal serial
                                    "milwaukee" -> tagUuid // Milwaukee tags should display raw hex
                                    else -> hexToDecimal(tagUuid) // Hilti: convert hex UUID to decimal
                                },
                                deviceName = null,
                                deviceAddress = "Unknown",
                                rssi = -50, // Default RSSI since we don't have it from centralized scanner
                                isHilti = isHiltiTag,
                                isEla = isElaTag,
                                isEm = isEmTag
                            )
                        }
                    }
                    
                    // Update UI with current results
                    enrichAndEmitTags()
                    
                    delay(1000) // Check every second
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting session tags from central scanner", e)
                }
            }
        }
    }

    init {
        // Reset mapping complete state when ViewModel is created
        _mappingComplete.value = false
    }

    fun setToolId(id: String) {
        _toolId = id
        // Reset mapping complete when setting new tool ID
        _mappingComplete.value = false
    }

    fun resetMappingComplete() {
        _mappingComplete.value = false
    }

    fun clearMappingError() {
        _mappingError.value = null
    }

    fun refreshTags() {
        viewModelScope.launch {
            enrichAndEmitTags()
        }
    }

    fun selectTag(tagUuid: String) {
        viewModelScope.launch {
            val currentToolId = toolId ?: return@launch
            val tool = repository.getToolById(currentToolId) ?: return@launch
            
            Log.d(TAG, "Storing tag UUID: $tagUuid")

            // Prevent duplicate bindings in DB: Clear this tagUuid from any other tools
            val allTools = try { repository.getAllToolsFlow().first() } catch (e: Exception) { emptyList() }
            val toolsToClear = allTools.filter { it.tagUuid == tagUuid && it.id != currentToolId }
            toolsToClear.forEach { t ->
                Log.d(TAG, "Clearing tag UUID from old tool: ${t.name}")
                repository.updateTool(t.copy(tagUuid = null))
            }
            
            // Assign to current tool
            repository.updateTool(tool.copy(tagUuid = tagUuid))
            
            // Refresh BLE tag cache so service picks up the new mapping immediately
            bleTagDetector.refreshTagCache()
            
            _mappingComplete.value = true
        }
    }

    // Connects barcode scan to selection
    fun selectTagFromBarcode(decimalBarcode: String) {
        if (isProcessingBarcode) return
        isProcessingBarcode = true

        viewModelScope.launch {
            try {
                // First try to normalize as ELA C ID format
                val normalizedCId = ElaPacketParser.normalizeCId(decimalBarcode)
                val tagUuid = if (normalizedCId != null) {
                    // It's an ELA C ID format
                    normalizedCId
                } else if (EmPacketParser.isValidSerialFormat(decimalBarcode)) {
                    // It's an EM serial number (15 digits)
                    EmPacketParser.decimalToMac(decimalBarcode)
                } else {
                    // Try as Hilti decimal barcode
                    encodeBarcodeToTagUuid(decimalBarcode)
                }
                
                if (tagUuid.isNotEmpty()) {
                    // Fetch accurate latest DB state to ensure we check assignments without stale flow cache
                    val existingTool = repository.getToolByTagUuid(tagUuid)
                    
                    if (existingTool != null) {
                        if (existingTool.id != toolId) {
                            // Block the assignment and notify the user
                            _mappingError.value = "Cannot assign: Tag is already mapped to \"${existingTool.name}\""
                        } else {
                            // Tag is already mapped to THIS exact tool successfully.
                            _mappingComplete.value = true
                        }
                    } else {
                        // Tag is perfectly clear. Assign it!
                        selectTag(tagUuid)
                    }
                } else {
                    _mappingError.value = "Invalid tag format. Expected Hilti decimal ID, ELA C ID format (e.g., '015E58', 'CID015E58'), or EM serial (15 digits)."
                }
            } finally {
                // Short cooldown to block ghost double-fire frames from camera
                delay(1000)
                isProcessingBarcode = false
            }
        }
    }

    private suspend fun enrichAndEmitTags() {
        // Get all tools to check which tags are already mapped
        val allTools = try {
            repository.getAllToolsFlow().first()
        } catch (e: Exception) {
            emptyList()
        }
        
        // Create a map of tagUuid to tool name
        val mappedTags = allTools
            .filter { it.tagUuid != null }
            .associateBy({ it.tagUuid!! }, { it.name })
        
        // Enrich tags with assignment info
        val enrichedTags = discoveredMap.values.map { tag ->
            val assignedToolName = mappedTags[tag.tagUuid]
            tag.copy(assignedToolName = assignedToolName)
        }
        
        _discoveredTags.value = enrichedTags.sortedByDescending { it.rssi }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up Handler callbacks
        scanStopRunnable?.let { handler.removeCallbacks(it) }
    }
}
