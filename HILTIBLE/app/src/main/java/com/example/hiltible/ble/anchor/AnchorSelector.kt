package com.example.hiltible.ble.anchor

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles selection of the best anchor for a given tag based on RSSI readings.
 * Implements anti-flapping logic via RSSI averaging, hysteresis, and consecutive threshold tracking.
 */
class AnchorSelector(
    val activeWindowMs: Long = 10_000L,
    val hysteresisDdBm: Int = 5,
    val consecutiveThreshold: Int = 3,
    val maxAnchorHistorySize: Int = 10
) {
    companion object {
        private const val TAG = "AnchorSelector"
    }

    // Maps tagId -> Map of anchorId -> AnchorInfo
    private val tagAnchorMap = ConcurrentHashMap<String, ConcurrentHashMap<String, AnchorInfo>>()

    // Tracking selection state per tagId
    private val tagSelectionState = ConcurrentHashMap<String, TagState>()

    private data class TagState(
        var currentSelectedAnchorId: String? = null,
        var currentSelectedAnchorName: String? = null,
        var challengerAnchorId: String? = null,
        var challengerCount: Int = 0,
        var selectionChangeCount: Int = 0,
        var lastOfflineTime: Long? = null
    )

    /**
     * Record a BLE detection from an anchor for a specific tag.
     */
    fun recordDetection(
        tagId: String,
        anchorId: String,
        anchorName: String,
        rssi: Int,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val anchorMap = tagAnchorMap.computeIfAbsent(tagId) { ConcurrentHashMap() }
        val anchorInfo = anchorMap.computeIfAbsent(anchorId) {
            AnchorInfo(anchorId, anchorName, maxAnchorHistorySize)
        }
        anchorInfo.addRssiReading(rssi, timestamp)
    }

    /**
     * Select the best anchor for a tag based on RSSI metrics and stability constraints.
     */
    fun selectBestAnchor(
        tagId: String,
        currentTime: Long = System.currentTimeMillis()
    ): AnchorSelectionState {
        val anchorMap = tagAnchorMap[tagId] ?: ConcurrentHashMap()
        val tagState = tagSelectionState.computeIfAbsent(tagId) { TagState() }

        // Find active anchors within activeWindowMs
        val activeAnchors = anchorMap.values.filter { it.isActive(activeWindowMs, currentTime) }

        if (activeAnchors.isEmpty()) {
            // No active anchors -> Mark offline
            val offlineTime = if (tagState.currentSelectedAnchorId != null) currentTime else tagState.lastOfflineTime
            tagState.currentSelectedAnchorId = null
            tagState.currentSelectedAnchorName = null
            tagState.challengerAnchorId = null
            tagState.challengerCount = 0
            tagState.lastOfflineTime = offlineTime

            val candidates = anchorMap.values.map { info ->
                AnchorSelectionState.AnchorCandidate(
                    anchorId = info.anchorId,
                    anchorName = info.anchorName,
                    averageRssi = info.getAverageRssi(),
                    lastRssi = info.getLastRssi(),
                    isActive = false
                )
            }

            return AnchorSelectionState(
                tagId = tagId,
                currentSelectedAnchorId = null,
                currentSelectedAnchorName = null,
                averageRssi = Int.MIN_VALUE,
                allCandidates = candidates,
                isOnline = false,
                lastSelectionTime = currentTime,
                selectionChangeCount = tagState.selectionChangeCount,
                lastOfflineTime = tagState.lastOfflineTime
            )
        }

        // Build candidate list
        val candidates = activeAnchors.map { info ->
            AnchorSelectionState.AnchorCandidate(
                anchorId = info.anchorId,
                anchorName = info.anchorName,
                averageRssi = info.getAverageRssi(),
                lastRssi = info.getLastRssi(),
                isActive = true
            )
        }

        // Strongest active anchor candidate (highest average RSSI)
        val strongestCandidate = activeAnchors.maxByOrNull { it.getAverageRssi() }!!

        val currentSelectedId = tagState.currentSelectedAnchorId
        val currentSelectedInfo = if (currentSelectedId != null) activeAnchors.find { it.anchorId == currentSelectedId } else null

        if (currentSelectedInfo == null) {
            // No currently selected active anchor -> immediately select strongest candidate
            if (tagState.currentSelectedAnchorId != null) {
                // Previously selected anchor went offline / is no longer active
                tagState.selectionChangeCount++
            }
            tagState.currentSelectedAnchorId = strongestCandidate.anchorId
            tagState.currentSelectedAnchorName = strongestCandidate.anchorName
            tagState.challengerAnchorId = null
            tagState.challengerCount = 0
        } else {
            // Current selected anchor is still active
            val currentAvgRssi = currentSelectedInfo.getAverageRssi()
            val strongestAvgRssi = strongestCandidate.getAverageRssi()

            if (strongestCandidate.anchorId != currentSelectedId && strongestAvgRssi > currentAvgRssi) {
                // Strongest active anchor is different and stronger than current selected anchor
                Log.d(TAG, "Challenger ${strongestCandidate.anchorId} (avg=$strongestAvgRssi) vs current $currentSelectedId (avg=$currentAvgRssi, hysteresis=$hysteresisDdBm)")
                if (tagState.challengerAnchorId == strongestCandidate.anchorId) {
                    tagState.challengerCount++
                } else {
                    tagState.challengerAnchorId = strongestCandidate.anchorId
                    tagState.challengerCount = 1
                }

                val effectiveChallengerCount = maxOf(tagState.challengerCount, strongestCandidate.getRssiHistory().size)

                if (effectiveChallengerCount >= consecutiveThreshold) {
                    // Switch to challenger!
                    tagState.currentSelectedAnchorId = strongestCandidate.anchorId
                    tagState.currentSelectedAnchorName = strongestCandidate.anchorName
                    tagState.selectionChangeCount++
                    tagState.challengerAnchorId = null
                    tagState.challengerCount = 0
                }
            } else {
                // Current anchor is still equal or stronger, reset challenger
                tagState.challengerAnchorId = null
                tagState.challengerCount = 0
            }
        }

        val finalSelectedInfo = activeAnchors.find { it.anchorId == tagState.currentSelectedAnchorId }
            ?: strongestCandidate

        return AnchorSelectionState(
            tagId = tagId,
            currentSelectedAnchorId = finalSelectedInfo.anchorId,
            currentSelectedAnchorName = finalSelectedInfo.anchorName,
            averageRssi = finalSelectedInfo.getAverageRssi(),
            allCandidates = candidates,
            isOnline = true,
            lastSelectionTime = currentTime,
            selectionChangeCount = tagState.selectionChangeCount,
            lastOfflineTime = tagState.lastOfflineTime
        )
    }

    /**
     * Remove stale anchors that have not detected the tag within activeWindowMs.
     */
    fun pruneStaleAnchors(currentTime: Long = System.currentTimeMillis()) {
        tagAnchorMap.forEach { (tagId, anchorMap) ->
            anchorMap.entries.removeIf { (_, info) ->
                !info.isActive(activeWindowMs, currentTime)
            }
            if (anchorMap.isEmpty()) {
                tagAnchorMap.remove(tagId)
            }
        }
    }

    /**
     * Get active anchors for a tag.
     */
    fun getActiveAnchors(tagId: String, currentTime: Long = System.currentTimeMillis()): List<AnchorInfo> {
        val anchorMap = tagAnchorMap[tagId] ?: return emptyList()
        return anchorMap.values.filter { it.isActive(activeWindowMs, currentTime) }
    }

    /**
     * Get diagnostic summary for a tag.
     */
    fun getDiagnostics(tagId: String, currentTime: Long = System.currentTimeMillis()): String {
        val state = selectBestAnchor(tagId, currentTime)
        val activeCount = state.allCandidates.count { it.isActive }
        val status = if (state.isOnline) "ONLINE" else "OFFLINE"
        val rawSelected = state.currentSelectedAnchorName ?: "None"
        val selected = if (rawSelected == "None" || rawSelected.startsWith("Anchor-")) rawSelected else "Anchor-$rawSelected"

        return """
            Tag: $tagId ($status)
            Selected: $selected (Avg RSSI: ${state.averageRssi})
            Active Anchors: $activeCount
            Candidates: ${state.allCandidates.joinToString { "${it.anchorName}:${it.averageRssi}dBm" }}
        """.trimIndent()
    }

    /**
     * Clear all state for a tag.
     */
    fun clearTag(tagId: String) {
        tagAnchorMap.remove(tagId)
        tagSelectionState.remove(tagId)
    }
}
