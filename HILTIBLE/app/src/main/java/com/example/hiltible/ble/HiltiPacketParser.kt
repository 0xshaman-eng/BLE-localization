package com.example.hiltible.ble

import android.util.Log

data class HiltiPacket(
    val tagUuid: String
)

object HiltiPacketParser {
    // Hilti manufacturer ID is 780 decimal = 0x030C hex
    const val MANUFACTURER_ID = 780

    fun parse(manufacturerData: ByteArray): HiltiPacket? {
        try {
            // Hilti payload should be at least 8 bytes to extract UUID
            if (manufacturerData.size < 8) {
                Log.d("HiltiPacketParser", "Payload too small: ${manufacturerData.size} bytes")
                return null
            }

            // Extract Tag UUID from bytes 2-7 (6 bytes)
            val uuidBytes = manufacturerData.copyOfRange(2, 8)
            val rawUuid = uuidBytes.joinToString("") { 
                "%02x".format(it) 
            }

            // The 6 BLE bytes (positions 2-7) already contain the full tag UUID
            // including the d322 prefix, e.g. bytes [0xd3, 0x22, 0x18, 0x66, 0x6c, 0x12]
            // -> rawUuid = "d32218666c12" which is the correct storage format
            val tagUuid = rawUuid

            Log.d("HiltiPacketParser", "BLE bytes: ${uuidBytes.joinToString(", ") { "0x%02x".format(it) }}")
            Log.d("HiltiPacketParser", "Tag UUID: $tagUuid")
            return HiltiPacket(tagUuid = tagUuid)
        } catch (e: Exception) {
            Log.e("HiltiPacketParser", "Error parsing packet: ${e.message}")
            return null
        }
    }
}
