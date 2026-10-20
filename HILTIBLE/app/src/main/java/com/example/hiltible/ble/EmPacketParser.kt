package com.example.hiltible.ble

import android.bluetooth.BluetoothDevice
import android.util.Log

data class EmPacket(
    val tagId: String // Stored as MAC address hex (12 chars, no colons)
)

object EmPacketParser {
    private const val TAG = "EmPacketParser"
    
    // EM Microelectronic manufacturer ID: 0x005A (90 decimal)
    const val MANUFACTURER_ID = 90
    
    // AltBeacon signature: 0xBEAC (big-endian)
    private const val ALTBEACON_SIGNATURE_BYTE_0 = 0xBE.toByte()
    private const val ALTBEACON_SIGNATURE_BYTE_1 = 0xAC.toByte()
    
    /**
     * Parse EM AltBeacon packet
     * @param manufacturerData The manufacturer specific data from BLE scan result
     *                         Used only to verify AltBeacon signature
     * @param device The Bluetooth device containing the protocol-level MAC address from link layer
     * @return EmPacket with MAC address from protocol level, or null if parsing fails
     */
    fun parse(manufacturerData: ByteArray, device: BluetoothDevice?): EmPacket? {
        try {
            // Validate minimum size for AltBeacon
            if (manufacturerData.size < 2) {
                Log.d(TAG, "AltBeacon payload too small: ${manufacturerData.size} bytes")
                return null
            }
            
            // Check AltBeacon signature (bytes 0-1: 0xBEAC)
            if (manufacturerData[0] != ALTBEACON_SIGNATURE_BYTE_0 || 
                manufacturerData[1] != ALTBEACON_SIGNATURE_BYTE_1) {
                Log.d(TAG, "Invalid AltBeacon signature: ${"%02X".format(manufacturerData[0])}${"%02X".format(manufacturerData[1])}")
                return null
            }
            
            // Get protocol-level MAC address directly from Bluetooth link layer
            val protocolMac = device?.address // Format: "58:C3:56:08:EE:B0"
            if (protocolMac.isNullOrEmpty()) {
                Log.e(TAG, "Device MAC address not available")
                return null
            }
            
            // Convert protocol MAC to hex format (remove colons)
            val macHex = protocolMac.replace(":", "").uppercase()
            
            Log.d(TAG, "AltBeacon detected - Protocol MAC: $protocolMac")
            Log.d(TAG, "MAC hex: $macHex")
            
            return EmPacket(tagId = macHex)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing AltBeacon packet: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Check if data looks like an AltBeacon packet
     */
    fun isAltBeaconFormat(manufacturerData: ByteArray): Boolean {
        if (manufacturerData.size < 2) return false
        return manufacturerData[0] == ALTBEACON_SIGNATURE_BYTE_0 && 
               manufacturerData[1] == ALTBEACON_SIGNATURE_BYTE_1
    }
    
    /**
     * Convert MAC address (hex string) to decimal serial number
     * MAC is reversed byte-by-byte before conversion
     */
    fun macToDecimal(macHex: String): String {
        try {
            if (macHex.length != 12) {
                Log.w(TAG, "Invalid MAC hex length: ${macHex.length}")
                return macHex
            }
            
            // Reverse bytes: 58C35608EEB0 -> B0EE0856C358
            val reversedHex = StringBuilder()
            for (i in macHex.length - 2 downTo 0 step 2) {
                reversedHex.append(macHex.substring(i, i + 2))
            }
            
            // Convert 48-bit hex to decimal
            val decimal = reversedHex.toString().toLong(16)
            Log.d(TAG, "MAC to decimal: $macHex -> $decimal")
            
            return decimal.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error converting MAC to decimal: ${e.message}")
            return macHex
        }
    }
    
    /**
     * Convert decimal serial number to MAC address (hex string)
     * Result is byte-reversed to match broadcast format
     */
    fun decimalToMac(decimalSerial: String): String {
        try {
            // Parse decimal to hex
            val decimalValue = decimalSerial.toLong()
            val hexString = decimalValue.toString(16).uppercase()
            
            // Pad to exactly 12 characters (6 bytes)
            val paddedHex = hexString.padStart(12, '0')
            
            // Reverse bytes: B0EE0856C358 -> 58C35608EEB0
            val reversedHex = StringBuilder()
            for (i in paddedHex.length - 2 downTo 0 step 2) {
                reversedHex.append(paddedHex.substring(i, i + 2))
            }
            
            val result = reversedHex.toString()
            Log.d(TAG, "Decimal to MAC: $decimalSerial -> $result")
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error converting decimal to MAC: ${e.message}")
            return ""
        }
    }
    
    /**
     * Check if a string represents a valid EM serial number format (15 digits)
     */
    fun isValidSerialFormat(input: String): Boolean {
        return input.matches("\\d{15}".toRegex())
    }
    
    /**
     * Check if a string represents a valid MAC format (12 hex chars)
     */
    fun isValidMacFormat(input: String): Boolean {
        return input.matches("[0-9A-Fa-f]{12}".toRegex())
    }
}
