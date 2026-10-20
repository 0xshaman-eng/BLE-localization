package com.example.hiltible.dashboard

import com.example.hiltible.data.entity.Tool
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class DashboardAnchorSelectorTest {

    private lateinit var selector: DashboardAnchorSelector
    private val currentTime = System.currentTimeMillis()

    @Before
    fun setUp() {
        selector = DashboardAnchorSelector()
    }

    @Test
    fun testSelectStrongestAnchorBasic() {
        // Two anchors detect the same tag
        val tools = listOf(
            Tool(
                id = "tool-1",
                name = "Drill",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -48,
                lastSeenAt = currentTime,
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                name = "Hammer",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -61,
                lastSeenAt = currentTime,
                lastLat = 40.1,
                lastLng = -74.1
            )
        )

        val selected = selector.selectStrongestAnchor(tools, "tag-abc", currentTime)

        assertNotNull(selected)
        assertEquals("anchor-a", selected!!.anchorId)
        assertEquals("Phone A", selected.anchorName)
        assertEquals(-48, selected.rssi)
    }

    @Test
    fun testSelectStrongestAnchorAfterUpdate() {
        // Initial: Anchor A strongest
        var tools = listOf(
            Tool(
                id = "tool-1",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -48,
                lastSeenAt = currentTime,
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -61,
                lastSeenAt = currentTime,
                lastLat = 40.1,
                lastLng = -74.1
            )
        )

        var selected = selector.selectStrongestAnchor(tools, "tag-abc", currentTime)
        assertEquals("anchor-a", selected!!.anchorId)

        // After 30 seconds: Anchor B now stronger
        val laterTime = currentTime + 30_000L
        tools = listOf(
            Tool(
                id = "tool-1",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -58,
                lastSeenAt = laterTime,
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -42,
                lastSeenAt = laterTime,
                lastLat = 40.1,
                lastLng = -74.1
            )
        )

        selected = selector.selectStrongestAnchor(tools, "tag-abc", laterTime)
        assertEquals("anchor-b", selected!!.anchorId)
        assertEquals("Phone B", selected.anchorName)
        assertEquals(-42, selected.rssi)
    }

    @Test
    fun testOfflineAnchorNotSelected() {
        // Anchor A is offline (data older than 60 seconds)
        val tools = listOf(
            Tool(
                id = "tool-1",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -48,
                lastSeenAt = currentTime - 70_000L,  // Too old
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -61,
                lastSeenAt = currentTime,
                lastLat = 40.1,
                lastLng = -74.1
            )
        )

        val selected = selector.selectStrongestAnchor(tools, "tag-abc", currentTime)

        assertNotNull(selected)
        // Anchor A should be skipped because it's offline
        assertEquals("anchor-b", selected!!.anchorId)
    }

    @Test
    fun testNoActiveAnchorsReturnsNull() {
        // All anchors are offline
        val tools = listOf(
            Tool(
                id = "tool-1",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -48,
                lastSeenAt = currentTime - 70_000L,
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -61,
                lastSeenAt = currentTime - 70_000L,
                lastLat = 40.1,
                lastLng = -74.1
            )
        )

        val selected = selector.selectStrongestAnchor(tools, "tag-abc", currentTime)

        assertNull(selected)
    }

    @Test
    fun testApplySelectedAnchorUpdatesToolWithOnlineStatus() {
        val originalTool = Tool(
            id = "tool-1",
            name = "Drill",
            tagUuid = "tag-abc",
            lastAnchorId = "anchor-old",
            lastAnchorName = "Old Anchor",
            lastRssi = -80,
            isOnline = false
        )

        val selectedReading = DashboardAnchorSelector.AnchorReading(
            anchorId = "anchor-new",
            anchorName = "New Anchor",
            rssi = -45,
            lastSeenAt = currentTime,
            lat = 40.0,
            lng = -74.0
        )

        val updatedTool = selector.applySelectedAnchor(originalTool, selectedReading)

        assertEquals("anchor-new", updatedTool.selectedAnchorId)
        assertEquals("New Anchor", updatedTool.selectedAnchorName)
        assertEquals(-45, updatedTool.lastRssi)
        assertTrue(updatedTool.isOnline)
    }

    @Test
    fun testApplySelectedAnchorNullMarkesOfflineButKeepsLastKnown() {
        val originalTool = Tool(
            id = "tool-1",
            name = "Drill",
            tagUuid = "tag-abc",
            selectedAnchorId = "anchor-known",
            selectedAnchorName = "Last Known Anchor",
            lastRssi = -55,
            isOnline = true
        )

        val updatedTool = selector.applySelectedAnchor(originalTool, null)

        // Should mark as offline but keep last known anchor
        assertFalse(updatedTool.isOnline)
        assertEquals("anchor-known", updatedTool.selectedAnchorId)
        assertEquals("Last Known Anchor", updatedTool.selectedAnchorName)
    }

    @Test
    fun testSelectStrongestAnchorsForTagsBatch() {
        val tools = listOf(
            // Tag 1: Anchor A strongest
            Tool(
                id = "tool-1",
                tagUuid = "tag-1",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -48,
                lastSeenAt = currentTime,
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-1",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -61,
                lastSeenAt = currentTime,
                lastLat = 40.1,
                lastLng = -74.1
            ),
            // Tag 2: Anchor C strongest
            Tool(
                id = "tool-3",
                tagUuid = "tag-2",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -70,
                lastSeenAt = currentTime,
                lastLat = 40.2,
                lastLng = -74.2
            ),
            Tool(
                id = "tool-4",
                tagUuid = "tag-2",
                lastAnchorId = "anchor-c",
                lastAnchorName = "Phone C",
                lastRssi = -35,
                lastSeenAt = currentTime,
                lastLat = 40.3,
                lastLng = -74.3
            )
        )

        val selections = selector.selectStrongestAnchorsForTags(
            tools,
            listOf("tag-1", "tag-2"),
            currentTime
        )

        assertEquals(2, selections.size)
        assertEquals("anchor-a", selections["tag-1"]!!.anchorId)
        assertEquals("anchor-c", selections["tag-2"]!!.anchorId)
    }

    @Test
    fun testMultipleAnchorsOneWins() {
        // Three anchors, middle one is strongest
        val tools = listOf(
            Tool(
                id = "tool-1",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -55,
                lastSeenAt = currentTime,
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -42,
                lastSeenAt = currentTime,
                lastLat = 40.1,
                lastLng = -74.1
            ),
            Tool(
                id = "tool-3",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-c",
                lastAnchorName = "Phone C",
                lastRssi = -68,
                lastSeenAt = currentTime,
                lastLat = 40.2,
                lastLng = -74.2
            )
        )

        val selected = selector.selectStrongestAnchor(tools, "tag-abc", currentTime)

        assertEquals("anchor-b", selected!!.anchorId)
        assertEquals(-42, selected.rssi)
    }

    @Test
    fun testToolWithNoAnchorInfo() {
        val tools = listOf(
            Tool(
                id = "tool-1",
                tagUuid = "tag-abc",
                lastAnchorId = null,  // No anchor info
                lastAnchorName = null,
                lastRssi = null,
                lastSeenAt = null
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -61,
                lastSeenAt = currentTime,
                lastLat = 40.1,
                lastLng = -74.1
            )
        )

        val selected = selector.selectStrongestAnchor(tools, "tag-abc", currentTime)

        // Should skip tool-1 and select tool-2
        assertNotNull(selected)
        assertEquals("anchor-b", selected!!.anchorId)
    }

    @Test
    fun testEqualRssiKeepsOrder() {
        // Two anchors with equal RSSI
        val tools = listOf(
            Tool(
                id = "tool-1",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-a",
                lastAnchorName = "Phone A",
                lastRssi = -55,
                lastSeenAt = currentTime,
                lastLat = 40.0,
                lastLng = -74.0
            ),
            Tool(
                id = "tool-2",
                tagUuid = "tag-abc",
                lastAnchorId = "anchor-b",
                lastAnchorName = "Phone B",
                lastRssi = -55,
                lastSeenAt = currentTime,
                lastLat = 40.1,
                lastLng = -74.1
            )
        )

        val selected = selector.selectStrongestAnchor(tools, "tag-abc", currentTime)

        // When equal, maxByOrNull returns first occurrence
        assertEquals("anchor-a", selected!!.anchorId)
    }
}
