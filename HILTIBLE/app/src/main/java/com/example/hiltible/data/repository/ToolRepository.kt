package com.example.hiltible.data.repository

import com.example.hiltible.data.entity.Tool
import com.example.hiltible.dashboard.DashboardAnchorSelector
import com.example.hiltible.dashboard.AnchorSelectionCache
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import com.example.hiltible.di.ToolsReference

@Singleton
class ToolRepository @Inject constructor(
    @ToolsReference private val toolsRef: DatabaseReference
) {
    private val dashboardSelector = DashboardAnchorSelector()
    private val selectionCache = AnchorSelectionCache(cacheDurationMs = 30_000L)

    fun getAllToolsFlow(): Flow<List<Tool>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tools = snapshot.children.mapNotNull { child ->
                    child.getValue(Tool::class.java)?.copy(id = child.key ?: "")
                }
                trySend(tools)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        toolsRef.addValueEventListener(listener)
        awaitClose { toolsRef.removeEventListener(listener) }
    }

    /**
     * Get tools with dashboard-level anchor selection applied.
     * Compares all anchors for each tag and selects the strongest RSSI.
     * Results are cached for 30 seconds to avoid excessive recalculation.
     *
     * This does NOT modify Firebase writes - it only changes what the dashboard displays.
     */
    fun getToolsWithDashboardSelectionFlow(): Flow<List<Tool>> {
        return getAllToolsFlow().map { tools ->
            applyDashboardAnchorSelection(tools)
        }
    }

    /**
     * Apply dashboard-level anchor selection to a list of tools.
     * For each unique tag, finds the strongest anchor among all tools detecting it.
     */
    private fun applyDashboardAnchorSelection(tools: List<Tool>): List<Tool> {
        val currentTime = System.currentTimeMillis()

        // Group tools by tag to find all anchors detecting the same tag
        val toolsByTag = tools.groupBy { it.tagUuid }

        return tools.map { tool ->
            if (tool.tagUuid == null) {
                // Tool with no tag - return as-is
                return@map tool
            }

            val tagUuid = tool.tagUuid

            // Try to get cached selection
            val cachedSelection = selectionCache.getCachedSelection(tagUuid, currentTime)
            if (cachedSelection != null) {
                // Use cached selection
                return@map dashboardSelector.applySelectedAnchor(tool, cachedSelection)
            }

            // Cache miss - perform new selection
            val allToolsWithThisTag = toolsByTag[tagUuid] ?: listOf(tool)
            val selectedAnchor = dashboardSelector.selectStrongestAnchor(
                allToolsWithThisTag,
                tagUuid,
                currentTime
            )

            // Cache the result
            selectionCache.cacheSelection(tagUuid, selectedAnchor, currentTime)

            // Apply selection to this tool
            dashboardSelector.applySelectedAnchor(tool, selectedAnchor)
        }
    }

    /**
     * Invalidate selection cache (forces recalculation on next flow emission).
     * Useful when you want to trigger immediate re-evaluation.
     */
    fun invalidateSelectionCache() {
        selectionCache.invalidateAll()
    }

    /**
     * Get cache statistics for diagnostics.
     */
    fun getSelectionCacheStats(): String = selectionCache.getCacheStats()

    suspend fun getToolById(id: String): Tool? {
        val snapshot = toolsRef.child(id).get().await()
        return snapshot.getValue(Tool::class.java)?.copy(id = id)
    }

    suspend fun getToolByTagUuid(tagUuid: String): Tool? {
        val snapshot = toolsRef.orderByChild("tagUuid").equalTo(tagUuid).get().await()
        return snapshot.children.firstOrNull()?.let { child ->
            child.getValue(Tool::class.java)?.copy(id = child.key ?: "")
        }
    }

    suspend fun insertTool(tool: Tool): String {
        val newRef = toolsRef.push()
        val toolId = newRef.key ?: throw Exception("Failed to generate tool ID")
        val data = mapOf(
            "name" to tool.name,
            "tagUuid" to tool.tagUuid,
            "lastLat" to tool.lastLat,
            "lastLng" to tool.lastLng,
            "lastSeenAt" to tool.lastSeenAt
        )
        newRef.setValue(data).await()
        return toolId
    }

    suspend fun updateTool(tool: Tool) {
        val data = mapOf(
            "name" to tool.name,
            "tagUuid" to tool.tagUuid,
            "lastLat" to tool.lastLat,
            "lastLng" to tool.lastLng,
            "lastSeenAt" to tool.lastSeenAt
        )
        toolsRef.child(tool.id).updateChildren(data).await()
    }

    suspend fun deleteTool(toolId: String) {
        toolsRef.child(toolId).removeValue().await()
    }

    suspend fun unmapTag(toolId: String) {
        toolsRef.child(toolId).child("tagUuid").removeValue().await()
    }

    suspend fun clearAllLastSeen() {
        val snapshot = toolsRef.get().await()
        val updates = mutableMapOf<String, Any?>()
        snapshot.children.forEach { child ->
            val toolId = child.key ?: return@forEach
            updates["$toolId/lastLat"] = 0.0
            updates["$toolId/lastLng"] = 0.0
            updates["$toolId/lastSeenAt"] = null
        }
        toolsRef.updateChildren(updates).await()
    }

    suspend fun updateAnchorSelection(
        toolId: String,
        selectedAnchorId: String?,
        selectedAnchorName: String?,
        averageRssi: Int
    ) {
        val data = mapOf(
            "selectedAnchorId" to selectedAnchorId,
            "selectedAnchorName" to selectedAnchorName,
            "lastRssi" to averageRssi
        )
        toolsRef.child(toolId).updateChildren(data).await()
    }

    suspend fun markToolOnline(toolId: String, isOnline: Boolean) {
        toolsRef.child(toolId).child("isOnline").setValue(isOnline).await()
    }

    suspend fun updateToolCandidates(
        toolId: String,
        candidates: List<Tool.AnchorCandidate>
    ) {
        toolsRef.child(toolId).child("anchorCandidates").setValue(candidates).await()
    }
}
