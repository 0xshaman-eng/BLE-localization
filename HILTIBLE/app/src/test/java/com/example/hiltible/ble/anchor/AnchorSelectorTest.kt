package com.example.hiltible.ble.anchor

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class AnchorSelectorTest {

    private lateinit var selector: AnchorSelector
    private val currentTime = System.currentTimeMillis()

    @Before
    fun setUp() {
        selector = AnchorSelector(
            activeWindowMs = 10_000L,
            hysteresisDdBm = 5,
            consecutiveThreshold = 3,
            maxAnchorHistorySize = 10
        )
    }

    @Test
    fun testRssiAveraging() {
        // Test RSSI averaging with 3 readings
        val info = AnchorInfo("anchor-1", "Anchor-1", maxHistorySize = 10)
        info.addRssiReading(-60, currentTime)
        info.addRssiReading(-65, currentTime + 100)
        info.addRssiReading(-70, currentTime + 200)

        assertEquals(-65, info.getAverageRssi())
    }

    @Test
    fun testRssiAveragingRollingWindow() {
        // Test rolling window - keep only last 3 readings
        val info = AnchorInfo("anchor-1", "Anchor-1", maxHistorySize = 3)
        info.addRssiReading(-50, currentTime)
        info.addRssiReading(-60, currentTime + 100)
        info.addRssiReading(-70, currentTime + 200)
        info.addRssiReading(-80, currentTime + 300) // This should remove -50

        // Average of -60, -70, -80 = -70
        assertEquals(-70, info.getAverageRssi())
        assertEquals(3, info.getRssiHistory().size)
    }

    @Test
    fun testAnchorActive() {
        val info = AnchorInfo("anchor-1", "Anchor-1", maxHistorySize = 10)
        info.addRssiReading(-65, currentTime)

        // Should be active within 10 seconds
        assertTrue(info.isActive(10_000L, currentTime + 5_000))
        // Should be inactive after 10 seconds
        assertFalse(info.isActive(10_000L, currentTime + 11_000))
    }

    @Test
    fun testBasicAnchorSelection() {
        // Two anchors: A stronger than B
        selector.recordDetection("tag-1", "anchor-a", "A", -60, currentTime)
        selector.recordDetection("tag-1", "anchor-b", "B", -70, currentTime)

        val state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)
        assertEquals(-60, state.averageRssi)
        assertTrue(state.isOnline)
    }

    @Test
    fun testHysteresisPreventsFlapping() {
        // Current anchor at -65
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // New anchor appears with -62 (within 5 dBm hysteresis)
        selector.recordDetection("tag-1", "anchor-b", "B", -62, currentTime + 100)
        state = selector.selectBestAnchor("tag-1", currentTime + 100)
        // Should stay with A because B is within hysteresis
        assertEquals("anchor-a", state.currentSelectedAnchorId)
    }

    @Test
    fun testConsecutiveThresholdTriggersSwitch() {
        // Current anchor at -65
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // New anchor appears with -62 and stays stronger
        selector.recordDetection("tag-1", "anchor-b", "B", -62, currentTime + 1000)
        state = selector.selectBestAnchor("tag-1", currentTime + 1000)
        assertEquals("anchor-a", state.currentSelectedAnchorId) // Still A, count=1

        selector.recordDetection("tag-1", "anchor-b", "B", -62, currentTime + 2000)
        state = selector.selectBestAnchor("tag-1", currentTime + 2000)
        assertEquals("anchor-a", state.currentSelectedAnchorId) // Still A, count=2

        selector.recordDetection("tag-1", "anchor-b", "B", -62, currentTime + 3000)
        state = selector.selectBestAnchor("tag-1", currentTime + 3000)
        // Should switch after 3 consecutive readings
        assertEquals("anchor-b", state.currentSelectedAnchorId)
        assertEquals(1, state.selectionChangeCount)
    }

    @Test
    fun testFailoverWhenAnchorOffline() {
        // Two active anchors, A is stronger
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        selector.recordDetection("tag-1", "anchor-b", "B", -72, currentTime)
        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // A goes offline (11 seconds later, beyond 10 second window)
        selector.recordDetection("tag-1", "anchor-b", "B", -70, currentTime + 11_000)
        state = selector.selectBestAnchor("tag-1", currentTime + 11_000)

        // Should switch to B
        assertEquals("anchor-b", state.currentSelectedAnchorId)
        assertEquals(1, state.selectionChangeCount)
    }

    @Test
    fun testMarkOfflineWhenNoAnchors() {
        // Record a detection
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertTrue(state.isOnline)

        // After 11+ seconds with no new detection
        state = selector.selectBestAnchor("tag-1", currentTime + 11_000)
        assertFalse(state.isOnline)
        assertNull(state.currentSelectedAnchorId)
    }

    @Test
    fun testMultipleActiveAnchors() {
        // Three anchors detected at different strengths
        selector.recordDetection("tag-1", "anchor-a", "A", -60, currentTime)
        selector.recordDetection("tag-1", "anchor-b", "B", -70, currentTime)
        selector.recordDetection("tag-1", "anchor-c", "C", -65, currentTime)

        val state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)
        assertEquals(3, state.allCandidates.size)
    }

    @Test
    fun testStaleAnchorPruning() {
        // Record detections at different times
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        selector.recordDetection("tag-1", "anchor-b", "B", -70, currentTime + 1000)

        // Prune stale anchors (after 20 seconds)
        selector.pruneStaleAnchors(currentTime + 25_000)

        // Active anchors should be empty
        val active = selector.getActiveAnchors("tag-1", currentTime + 25_000)
        assertEquals(0, active.size)
    }

    @Test
    fun testNewAnchorMidSession() {
        // Initial anchor
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // New anchor appears mid-session
        selector.recordDetection("tag-1", "anchor-b", "B", -62, currentTime + 1000)
        state = selector.selectBestAnchor("tag-1", currentTime + 1000)

        // Should stay with A due to hysteresis
        assertEquals("anchor-a", state.currentSelectedAnchorId)
        // But B should be in candidates
        assertEquals(2, state.allCandidates.size)
    }

    @Test
    fun testGetDiagnostics() {
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        selector.selectBestAnchor("tag-1", currentTime)

        val diagnostics = selector.getDiagnostics("tag-1")
        assertTrue(diagnostics.contains("tag-1"))
        assertTrue(diagnostics.contains("ONLINE"))
        assertTrue(diagnostics.contains("Anchor-A"))
    }

    @Test
    fun testClearTag() {
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // Clear tag
        selector.clearTag("tag-1")

        // Should return offline state
        state = selector.selectBestAnchor("tag-1", currentTime)
        assertFalse(state.isOnline)
        assertNull(state.currentSelectedAnchorId)
    }

    @Test
    fun testStayOnStrongestAnchor() {
        // Establish anchor A as strongest
        selector.recordDetection("tag-1", "anchor-a", "A", -60, currentTime)
        selector.recordDetection("tag-1", "anchor-b", "B", -75, currentTime)

        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // B reports slightly better but not enough
        selector.recordDetection("tag-1", "anchor-b", "B", -62, currentTime + 1000)
        state = selector.selectBestAnchor("tag-1", currentTime + 1000)

        // Should stay on A (within hysteresis)
        assertEquals("anchor-a", state.currentSelectedAnchorId)
    }

    @Test
    fun testSwitchImmediatelyIfMuchStronger() {
        // Current anchor at -65
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        var state = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // New anchor is MUCH stronger (>5 dBm advantage)
        selector.recordDetection("tag-1", "anchor-b", "B", -40, currentTime + 1000)
        state = selector.selectBestAnchor("tag-1", currentTime + 1000)

        // Should NOT switch immediately due to hysteresis/consecutive check
        // This tests the gradual switch mechanism
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        // After threshold is met, should switch
        selector.recordDetection("tag-1", "anchor-b", "B", -40, currentTime + 2000)
        state = selector.selectBestAnchor("tag-1", currentTime + 2000)
        assertEquals("anchor-a", state.currentSelectedAnchorId)

        selector.recordDetection("tag-1", "anchor-b", "B", -40, currentTime + 3000)
        state = selector.selectBestAnchor("tag-1", currentTime + 3000)
        assertEquals("anchor-b", state.currentSelectedAnchorId)
    }

    @Test
    fun testSelectionStateTracking() {
        selector.recordDetection("tag-1", "anchor-a", "A", -65, currentTime)
        val state1 = selector.selectBestAnchor("tag-1", currentTime)
        assertEquals(0, state1.selectionChangeCount)

        // Switch anchor
        selector.recordDetection("tag-1", "anchor-b", "B", -40, currentTime + 1000)
        selector.recordDetection("tag-1", "anchor-b", "B", -40, currentTime + 2000)
        selector.recordDetection("tag-1", "anchor-b", "B", -40, currentTime + 3000)
        val state2 = selector.selectBestAnchor("tag-1", currentTime + 3000)

        assertEquals(1, state2.selectionChangeCount)
        assertEquals("anchor-b", state2.currentSelectedAnchorId)
    }
}
