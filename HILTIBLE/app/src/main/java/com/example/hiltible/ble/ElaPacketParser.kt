package com.example.hiltible.ble

import android.util.Log

data class ElaPacket(
    val tagId: String // Normalized C ID format: "C ID 015E58"
)

object ElaPacketParser {
    private const val TAG = "ElaPacketParser"
    
    // ELA Innovation manufacturer ID: 0x0757 (1879 decimal)
    // Note: BLE packets come as little-endian 5707, we need to flip to get 0757
    const val MANUFACTURER_ID = 1879 // 0x0757
    
    // DataInfo byte value for "ID format" devices
    private const val DATA_INFO_ID_FORMAT = 0x06.toByte()
    
    // AD Type for Complete Local Name
    private const val AD_TYPE_COMPLETE_LOCAL_NAME = 0x09.toByte()

    /**
     * Parse ELA Blue Coin BLE packet
     * @param manufacturerData The manufacturer specific data from BLE scan result
     * @param scanRecord The complete scan record to extract local name
     * @return ElaPacket with normalized C ID, or null if parsing fails
     */
    fun parse(manufacturerData: ByteArray, scanRecord: android.bluetooth.le.ScanRecord?): ElaPacket? {
        try {
            // Validate manufacturer data structure
            // Android's getManufacturerSpecificData() strips the 2-byte company ID,
            // so the returned payload starts directly with DataInfo byte
            if (manufacturerData.isEmpty()) {
                Log.d(TAG, "ELA manufacturer data empty")
                return null
            }
            
            // DataInfo byte is at offset 0 (Android already stripped the 2-byte company ID)
            val dataInfo = manufacturerData[0]
            if (dataInfo != DATA_INFO_ID_FORMAT) {
                return null
            }
            
            // Extract C ID from Complete Local Name (AD Type 0x09)
            val localName = scanRecord?.deviceName
            if (localName.isNullOrEmpty()) {
                return null
            }
            
            // Parse and normalize C ID from local name only
            val normalizedCId = normalizeCId(localName)
            if (normalizedCId == null) {
                return null
            }
            return ElaPacket(tagId = normalizedCId)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ELA packet", e)
            return null
        }
    }
    
    /**
     * Normalize C ID format from various input formats to standard "C ID 015E58"
     * Handles: "015e58", "CID015E58", "C ID 015E58", etc.
     */
    fun normalizeCId(input: String): String? {
        if (input.isBlank()) return null
        
        try {
            // Remove all whitespace and convert to uppercase
            val cleaned = input.replace("\\s+".toRegex(), "").uppercase()
            
            // Extract hex part based on different formats
            val hexPart = when {
                // Format: "CID015E58" or "cid015e58"
                cleaned.startsWith("CID") -> {
                    val hex = cleaned.substring(3)
                    if (hex.length == 6 && hex.matches("[0-9A-F]{6}".toRegex())) hex else null
                }
                // Format: "015E58" or "015e58" (just hex)
                cleaned.matches("[0-9A-F]{6}".toRegex()) -> cleaned
                // Format: "C ID 015E58" (already processed, extract after CID)
                cleaned.startsWith("CID") && cleaned.length > 3 -> {
                    val hex = cleaned.substring(3)
                    if (hex.length == 6 && hex.matches("[0-9A-F]{6}".toRegex())) hex else null
                }
                else -> null
            }
            
            if (hexPart == null) {
                Log.w(TAG, "Invalid C ID format: $input")
                return null
            }
            
            // Return normalized format
            val normalized = "C ID $hexPart"
            Log.d(TAG, "Normalized C ID: '$input' -> '$normalized'")
            return normalized
            
        } catch (e: Exception) {
            Log.e(TAG, "Error normalizing C ID: $input", e)
            return null
        }
    }
    
    /**
     * Check if a string represents a valid C ID format
     */
    fun isValidCIdFormat(input: String): Boolean {
        return normalizeCId(input) != null
    }
    
    /**
     * Extract hex part from normalized C ID (e.g., "C ID 015E58" -> "015E58")
     */
    fun extractHexFromCId(cId: String): String? {
        if (!cId.startsWith("C ID ")) return null
        val hex = cId.substring(5)
        return if (hex.matches("[0-9A-F]{6}".toRegex())) hex else null
    }
}
