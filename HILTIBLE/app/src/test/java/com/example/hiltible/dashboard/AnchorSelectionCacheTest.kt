package com.example.hiltible.dashboard

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class AnchorSelectionCacheTest {

    private lateinit var cache: AnchorSelectionCache
    private val currentTime = System.currentTimeMillis()

    @Before
    fun setUp() {
        cache = AnchorSelectionCache(cacheDurationMs = 30_000L)
    }

    @Test
    fun testCacheHit() {
        val selection = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-1",
            anchorName = "Phone A",
            rssi = -48,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        cache.cacheSelection("tag-1", selection, currentTime)

        // Should return cached value within 30 seconds
        val cached = cache.getCachedSelection("tag-1", currentTime + 15_000L)
        assertNotNull(cached)
        assertEquals("anchor-1", cached!!.anchorId)
    }

    @Test
    fun testCacheMissAfterExpiration() {
        val selection = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-1",
            anchorName = "Phone A",
            rssi = -48,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        cache.cacheSelection("tag-1", selection, currentTime)

        // After 30+ seconds, cache should be invalid
        val cached = cache.getCachedSelection("tag-1", currentTime + 31_000L)
        assertNull(cached)
    }

    @Test
    fun testCacheNullSelection() {
        // Cache a null selection (tag offline)
        cache.cacheSelection("tag-1", null, currentTime)

        // Should return null (offline state) if requested within cache duration
        val cached = cache.getCachedSelection("tag-1", currentTime + 15_000L)
        assertNull(cached)
    }

    @Test
    fun testInvalidateAll() {
        val selection = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-1",
            anchorName = "Phone A",
            rssi = -48,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        cache.cacheSelection("tag-1", selection, currentTime)
        cache.cacheSelection("tag-2", selection, currentTime)

        assertEquals(2, cache.getCacheSize())

        cache.invalidateAll()

        assertEquals(0, cache.getCacheSize())
        assertNull(cache.getCachedSelection("tag-1", currentTime))
        assertNull(cache.getCachedSelection("tag-2", currentTime))
    }

    @Test
    fun testInvalidateSpecificTag() {
        val selection = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-1",
            anchorName = "Phone A",
            rssi = -48,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        cache.cacheSelection("tag-1", selection, currentTime)
        cache.cacheSelection("tag-2", selection, currentTime)

        cache.invalidateTag("tag-1")

        assertNull(cache.getCachedSelection("tag-1", currentTime + 15_000L))
        assertNotNull(cache.getCachedSelection("tag-2", currentTime + 15_000L))
    }

    @Test
    fun testCacheSizeTracking() {
        val selection = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-1",
            anchorName = "Phone A",
            rssi = -48,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        assertEquals(0, cache.getCacheSize())

        cache.cacheSelection("tag-1", selection, currentTime)
        assertEquals(1, cache.getCacheSize())

        cache.cacheSelection("tag-2", selection, currentTime)
        assertEquals(2, cache.getCacheSize())

        cache.cacheSelection("tag-3", selection, currentTime)
        assertEquals(3, cache.getCacheSize())
    }

    @Test
    fun testTimeSinceLastUpdate() {
        val selection = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-1",
            anchorName = "Phone A",
            rssi = -48,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        cache.cacheSelection("tag-1", selection, currentTime)

        val timeSince = cache.getTimeSinceLastUpdate(currentTime + 5_000L)
        assertEquals(5_000L, timeSince)
    }

    @Test
    fun testEdgeCaseExactCacheExpiration() {
        val selection = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-1",
            anchorName = "Phone A",
            rssi = -48,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        cache.cacheSelection("tag-1", selection, currentTime)

        // At exactly 30 seconds, should still be valid (< 30_000)
        val cached29 = cache.getCachedSelection("tag-1", currentTime + 29_999L)
        assertNotNull(cached29)

        // After 30 seconds (>= 30_000), should be invalid
        val cached30 = cache.getCachedSelection("tag-1", currentTime + 30_000L)
        assertNull(cached30)
    }
}
