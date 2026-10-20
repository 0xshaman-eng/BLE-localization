package com.example.hiltible.dashboard

import android.util.Log
import com.example.hiltible.data.entity.Tool
import java.util.concurrent.ConcurrentHashMap

/**
 * Dashboard-level anchor selection logic.
 * Compares RSSI from all anchors for each tag and selects the strongest.
 * Does NOT modify Firebase writes or BLE packet reception.
 */
class DashboardAnchorSelector {
    companion object {
        private const val TAG = "DashboardAnchorSelector"
        private const val ACTIVE_DETECTION_WINDOW_MS = 60_000L  // 60 seconds
    }

    /**
     * Data class representing an anchor detection for a specific tag.
     */
    data class AnchorReading(
        val anchorId: String,
        val anchorName: String,
        val rssi: Int,
        val lastSeenAt: Long,
        val lat: Double,
        val lng: Double
    )

    /**
     * Select the strongest anchor for a tag based on RSSI values.
     * Only considers anchors with detections within ACTIVE_DETECTION_WINDOW_MS.
     *
     * @param tools List of all tools (may have overlapping tags from different anchors)
     * @param tagUuid The tag UUID to select anchor for
     * @param currentTime Current system time in ms
     * @return AnchorReading of the strongest anchor, or null if no active anchors
     */
    fun selectStrongestAnchor(
        tools: List<Tool>,
        tagUuid: String,
        currentTime: Long = System.currentTimeMillis()
    ): AnchorReading? {
        // Find all tools with this tag
        val toolsWithTag = tools.filter { it.tagUuid == tagUuid }

        if (toolsWithTag.isEmpty()) {
            Log.d(TAG, "No tools found with tag: $tagUuid")
            return null
        }

        // Build anchor readings from all tools that detected this tag
        val readings = toolsWithTag.mapNotNull { tool ->
            // Skip if no anchor info or data is too old
            if (tool.lastAnchorId == null || tool.lastAnchorName == null || tool.lastSeenAt == null) {
                return@mapNotNull null
            }

            val timeSinceDetection = currentTime - tool.lastSeenAt
            if (timeSinceDetection > ACTIVE_DETECTION_WINDOW_MS) {
                Log.d(TAG, "Tool ${tool.id} anchor data too old (${timeSinceDetection}ms), skipping")
                return@mapNotNull null
            }

            val rssi = tool.lastRssi ?: -100
            AnchorReading(
                anchorId = tool.lastAnchorId,
                anchorName = tool.lastAnchorName,
                rssi = rssi,
                lastSeenAt = tool.lastSeenAt,
                lat = tool.lastLat,
                lng = tool.lastLng
            )
        }

        if (readings.isEmpty()) {
            Log.d(TAG, "No active anchor readings for tag: $tagUuid (all too old)")
            return null
        }

        // Select strongest by RSSI (highest/closest to 0)
        val strongest = readings.maxByOrNull { it.rssi }
        Log.d(TAG, "Selected anchor for tag $tagUuid: ${strongest?.anchorName} (RSSI=${strongest?.rssi})")

        return strongest
    }

    /**
     * Select strongest anchor for multiple tags (batch operation).
     *
     * @param tools List of all tools
     * @param tagUuids List of tag UUIDs to process
     * @param currentTime Current system time
     * @return Map of tagUuid -> AnchorReading (or null if no active anchors)
     */
    fun selectStrongestAnchorsForTags(
        tools: List<Tool>,
        tagUuids: Collection<String>,
        currentTime: Long = System.currentTimeMillis()
    ): Map<String, AnchorReading?> {
        return tagUuids.associateWith { tagUuid ->
            selectStrongestAnchor(tools, tagUuid, currentTime)
        }
    }

    /**
     * Update tool with selected anchor information.
     * Creates a new Tool object with selectedAnchorId/Name populated.
     *
     * @param tool Original tool from Firebase
     * @param selectedAnchor The selected anchor reading (null if offline)
     * @return Modified Tool with selection info, or original if no change
     */
    fun applySelectedAnchor(
        tool: Tool,
        selectedAnchor: AnchorReading?
    ): Tool {
        return if (selectedAnchor != null) {
            tool.copy(
                selectedAnchorId = selectedAnchor.anchorId,
                selectedAnchorName = selectedAnchor.anchorName,
                lastRssi = selectedAnchor.rssi,
                isOnline = true
            )
        } else {
            // No active anchor - mark offline but keep last known anchor visible
            tool.copy(
                isOnline = false
                // Keep selectedAnchorId/Name from previous selection
            )
        }
    }

    /**
     * Get diagnostic info about selections.
     */
    fun getDiagnostics(
        tools: List<Tool>,
        tagUuid: String,
        currentTime: Long = System.currentTimeMillis()
    ): String {
        val toolsWithTag = tools.filter { it.tagUuid == tagUuid }
        val selected = selectStrongestAnchor(tools, tagUuid, currentTime)

        val readings = toolsWithTag.mapNotNull { tool ->
            if (tool.lastAnchorName != null && tool.lastRssi != null) {
                "${tool.lastAnchorName}:${tool.lastRssi}dBm"
            } else null
        }

        return """
            Tag: $tagUuid
            Active Anchors: ${readings.size}
            Readings: ${readings.joinToString(", ")}
            Selected: ${selected?.anchorName} (RSSI=${selected?.rssi})
        """.trimIndent()
    }
}
