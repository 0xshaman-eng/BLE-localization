package com.example.hiltible.ble

import android.util.Log

data class MilwaukeePacket(
    val tagUuid: String
)

object MilwaukeePacketParser {
    private const val TAG = "MilwaukeePacketParser"
    // Milwaukee manufacturer ID is 357 decimal = 0x0165 hex
    const val MANUFACTURER_ID = 357

    fun parse(manufacturerData: ByteArray): MilwaukeePacket? {
        try {
            // Android's manufacturerData has the 2-byte company ID stripped.
            // For the packet you provided the tag ID bytes are the first 5 bytes
            // of the manufacturer payload (raw bytes: 01 00 08 2C 59).
            if (manufacturerData.size < 5) {
                Log.d(TAG, "Payload too small: ${manufacturerData.size} bytes")
                return null
            }

            // Extract first 5 bytes (0..4) as the tag unique ID
            val idBytes = manufacturerData.copyOfRange(0, 5)
            val tagId = idBytes.joinToString("") { "%02X".format(it) }

            Log.d(TAG, "BLE bytes: ${idBytes.joinToString(", ") { "0x%02X".format(it) }}")
            Log.d(TAG, "Milwaukee Tag ID: $tagId")
            return MilwaukeePacket(tagUuid = tagId)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing packet: ${e.message}")
            return null
        }
    }
}
