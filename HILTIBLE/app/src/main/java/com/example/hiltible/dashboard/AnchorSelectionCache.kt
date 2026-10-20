package com.example.hiltible.dashboard

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Caching mechanism for dashboard anchor selections.
 * Caches selection results for 30 seconds to avoid excessive recalculation.
 * Invalidates and recalculates when cache expires.
 */
class AnchorSelectionCache(
    private val cacheDurationMs: Long = 30_000L  // 30 seconds
) {
    companion object {
        private const val TAG = "AnchorSelectionCache"
    }

    private data class CacheEntry(
        val selection: DashboardAnchorSelector.AnchorReading?,
        val timestamp: Long
    )

    // Map: tagUuid -> CacheEntry
    private val selectionCache = ConcurrentHashMap<String, CacheEntry>()
    private val lastCacheUpdateTime = AtomicLong(0L)

    /**
     * Check if cache entry for a tag is still valid.
     */
    private fun isCacheValid(tagUuid: String, currentTime: Long): Boolean {
        val entry = selectionCache[tagUuid] ?: return false
        return (currentTime - entry.timestamp) < cacheDurationMs
    }

    /**
     * Get cached selection if valid, otherwise returns null (cache miss).
     */
    fun getCachedSelection(
        tagUuid: String,
        currentTime: Long = System.currentTimeMillis()
    ): DashboardAnchorSelector.AnchorReading? {
        if (!isCacheValid(tagUuid, currentTime)) {
            Log.d(TAG, "Cache miss for tag: $tagUuid")
            return null
        }

        val entry = selectionCache[tagUuid]
        Log.d(TAG, "Cache hit for tag: $tagUuid (age=${currentTime - entry!!.timestamp}ms)")
        return entry.selection
    }

    /**
     * Cache a selection result.
     */
    fun cacheSelection(
        tagUuid: String,
        selection: DashboardAnchorSelector.AnchorReading?,
        currentTime: Long = System.currentTimeMillis()
    ) {
        selectionCache[tagUuid] = CacheEntry(selection, currentTime)
        lastCacheUpdateTime.set(currentTime)
        Log.d(TAG, "Cached selection for tag: $tagUuid (${selection?.anchorName ?: "offline"})")
    }

    /**
     * Invalidate all cache entries (forces recalculation).
     */
    fun invalidateAll() {
        selectionCache.clear()
        Log.d(TAG, "Cache invalidated (all entries cleared)")
    }

    /**
     * Invalidate specific tag's cache.
     */
    fun invalidateTag(tagUuid: String) {
        selectionCache.remove(tagUuid)
        Log.d(TAG, "Cache invalidated for tag: $tagUuid")
    }

    /**
     * Get number of cached entries.
     */
    fun getCacheSize(): Int = selectionCache.size

    /**
     * Get time since last cache update.
     */
    fun getTimeSinceLastUpdate(currentTime: Long = System.currentTimeMillis()): Long {
        return currentTime - lastCacheUpdateTime.get()
    }

    /**
     * Get cache statistics for diagnostics.
     */
    fun getCacheStats(): String {
        return """
            Cache Size: ${selectionCache.size}
            Time Since Last Update: ${getTimeSinceLastUpdate()}ms
            Cache Duration: ${cacheDurationMs}ms
            Entries: ${selectionCache.entries.joinToString(", ") { (tag, entry) ->
                "${tag.substring(0, 8)}... (age=${System.currentTimeMillis() - entry.timestamp}ms)"
            }}
        """.trimIndent()
    }
}
