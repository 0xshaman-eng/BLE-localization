package com.example.hiltible.ble.anchor

import android.util.Log
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import com.example.hiltible.di.ToolsReference

/**
 * Repository for persisting anchor selection results to Firebase.
 * Handles updating Tool entity with selected anchor and candidate list.
 */
@Singleton
class AnchorSelectionRepository @Inject constructor(
    @ToolsReference private val toolsRef: DatabaseReference
) {
    companion object {
        private const val TAG = "AnchorSelectionRepo"
    }

    /**
     * Update Tool in Firebase with anchor selection result.
     *
     * @param toolId Tool being updated
     * @param selectionState Selection state containing anchor choice
     * @param timestamp Detection timestamp
     */
    suspend fun updateAnchorSelection(
        toolId: String,
        selectionState: AnchorSelectionState,
        timestamp: Long
    ) {
        try {
            val updates = mutableMapOf<String, Any?>(
                "lastSeenAt" to timestamp,
                "lastRssi" to selectionState.averageRssi,
                "selectedAnchorId" to selectionState.currentSelectedAnchorId,
                "selectedAnchorName" to selectionState.currentSelectedAnchorName
            )

            // Store candidate anchors as a list for dashboard analytics
            if (selectionState.allCandidates.isNotEmpty()) {
                val candidatesData = selectionState.allCandidates.map { candidate ->
                    mapOf(
                        "anchorId" to candidate.anchorId,
                        "anchorName" to candidate.anchorName,
                        "averageRssi" to candidate.averageRssi,
                        "lastRssi" to candidate.lastRssi,
                        "isActive" to candidate.isActive
                    )
                }
                updates["anchorCandidates"] = candidatesData
            }

            // If offline, mark appropriately
            if (selectionState.isOffline()) {
                updates["isOnline"] = false
                updates["selectedAnchorId"] = null
                updates["selectedAnchorName"] = null
            } else {
                updates["isOnline"] = true
            }

            toolsRef.child(toolId).updateChildren(updates).await()
            Log.d(TAG, "Updated anchor selection for tool $toolId: selected=${selectionState.currentSelectedAnchorName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update anchor selection for tool $toolId", e)
            throw e
        }
    }

    /**
     * Batch update multiple tools' anchor selections.
     */
    suspend fun batchUpdateAnchorSelections(
        updates: Map<String, Pair<String, AnchorSelectionState>>,
        timestamp: Long
    ) {
        try {
            val batchUpdates = mutableMapOf<String, Any?>()

            updates.forEach { (toolId, pair) ->
                val state = pair.second
                batchUpdates["$toolId/lastSeenAt"] = timestamp
                batchUpdates["$toolId/lastRssi"] = state.averageRssi
                batchUpdates["$toolId/selectedAnchorId"] = state.currentSelectedAnchorId
                batchUpdates["$toolId/selectedAnchorName"] = state.currentSelectedAnchorName

                if (state.isOffline()) {
                    batchUpdates["$toolId/isOnline"] = false
                } else {
                    batchUpdates["$toolId/isOnline"] = true
                }

                if (state.allCandidates.isNotEmpty()) {
                    val candidatesData = state.allCandidates.map { candidate ->
                        mapOf(
                            "anchorId" to candidate.anchorId,
                            "anchorName" to candidate.anchorName,
                            "averageRssi" to candidate.averageRssi,
                            "lastRssi" to candidate.lastRssi,
                            "isActive" to candidate.isActive
                        )
                    }
                    batchUpdates["$toolId/anchorCandidates"] = candidatesData
                }
            }

            toolsRef.updateChildren(batchUpdates).await()
            Log.d(TAG, "Batch updated ${updates.size} tools with anchor selections")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch update anchor selections", e)
            throw e
        }
    }
}
