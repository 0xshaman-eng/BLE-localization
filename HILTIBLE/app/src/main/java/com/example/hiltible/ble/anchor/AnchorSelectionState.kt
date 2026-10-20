package com.example.hiltible.ble.anchor

/**
 * Represents the current state of anchor selection for a single tag.
 * Includes selection history for anti-flapping logic.
 */
data class AnchorSelectionState(
    val tagId: String,
    val currentSelectedAnchorId: String? = null,
    val currentSelectedAnchorName: String? = null,
    val averageRssi: Int = Int.MIN_VALUE,
    val allCandidates: List<AnchorCandidate> = emptyList(),
    val isOnline: Boolean = false,
    val lastSelectionTime: Long = 0L,
    val selectionChangeCount: Int = 0,
    val lastOfflineTime: Long? = null
) {
    /**
     * Represents a candidate anchor with its RSSI metrics.
     */
    data class AnchorCandidate(
        val anchorId: String,
        val anchorName: String,
        val averageRssi: Int,
        val lastRssi: Int,
        val detectionCount: Int = 0,
        val isActive: Boolean
    )

    /**
     * Check if tag is considered offline.
     */
    fun isOffline(): Boolean = !isOnline

    /**
     * Get current selection as a pair (ID, name).
     */
    fun getSelectedAnchor(): Pair<String, String>? =
        if (currentSelectedAnchorId != null && currentSelectedAnchorName != null) {
            Pair(currentSelectedAnchorId, currentSelectedAnchorName)
        } else {
            null
        }
}
