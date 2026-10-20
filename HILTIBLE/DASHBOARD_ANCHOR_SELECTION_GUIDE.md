# Dashboard-Level Anchor Selection Implementation Guide

## 📋 Overview

This document explains the **dashboard-level** anchor selection system that compares multiple anchors detecting the same BLE tag and displays only the strongest one (by RSSI). This implementation **does NOT modify BLE packet reception or Firebase write logic** - it only changes what the dashboard displays.

---

## 🎯 Key Principle

**Keep all BLE scanning and Firebase writes exactly as they are. Add intelligent selection logic ONLY at the dashboard display layer.**

```
Firebase (Unchanged)
    ↓ Raw data from multiple anchors
    ↓
[Dashboard Selection Layer] ← NEW (This document)
    ↓ Select strongest anchor
    ↓
UI Display (ToolsListScreen, MapScreen)
```

---

## 📁 Files to Create/Modify

### **NEW FILES**

#### 1. **`DashboardAnchorSelector.kt`**
**Path:** `app/src/main/java/com/example/hiltible/dashboard/DashboardAnchorSelector.kt`

**Purpose:** Core selection logic that compares RSSI from all anchors for a tag.

**Key Methods:**
- `selectStrongestAnchor()` - Finds strongest anchor by RSSI for a single tag
- `selectStrongestAnchorsForTags()` - Batch operation for multiple tags
- `applySelectedAnchor()` - Updates tool with selected anchor info

**Example Usage:**
```kotlin
val selector = DashboardAnchorSelector()
val selectedAnchor = selector.selectStrongestAnchor(tools, "tag-uuid", currentTime)
// Returns: AnchorReading(anchorId, anchorName, rssi, lastSeenAt, lat, lng)
// or null if no active anchors
```

---

#### 2. **`AnchorSelectionCache.kt`**
**Path:** `app/src/main/java/com/example/hiltible/dashboard/AnchorSelectionCache.kt`

**Purpose:** Caches selection results for 30 seconds to avoid excessive recalculation.

**Key Methods:**
- `getCachedSelection()` - Returns cached result if valid
- `cacheSelection()` - Store new selection result
- `invalidateAll()` - Clear entire cache
- `invalidateTag()` - Clear specific tag's cache

**Why Caching?** Each Firebase update triggers a new flow emission. Without caching, we'd recalculate selections on every data change, even if no new detections occurred. Caching ensures we only recalculate every 30 seconds.

---

### **MODIFIED FILES**

#### 3. **`ToolRepository.kt`**
**Path:** `app/src/main/java/com/example/hiltible/data/repository/ToolRepository.kt`

**Changes:**
- Added `DashboardAnchorSelector` instance
- Added `AnchorSelectionCache` instance
- **NEW METHOD:** `getToolsWithDashboardSelectionFlow()`
- **NEW METHOD:** `applyDashboardAnchorSelection()`
- **NEW METHOD:** `invalidateSelectionCache()`

**Example:**
```kotlin
// Before (raw data from Firebase)
val tools = repository.getAllToolsFlow()  // Multiple anchors per tag

// After (with dashboard selection applied)
val tools = repository.getToolsWithDashboardSelectionFlow()  // Only strongest anchor displayed
```

---

#### 4. **`ToolsListViewModel.kt`**
**Path:** `app/src/main/java/com/example/hiltible/ui/viewmodel/ToolsListViewModel.kt`

**Changes:**
- Changed from `getAllToolsFlow()` to `getToolsWithDashboardSelectionFlow()`
- Now automatically selects strongest anchor before emitting to UI

**Impact:**
- ToolsListScreen and MapScreen receive pre-selected anchors
- No changes needed to UI code
- Dashboard automatically displays strongest anchor

---

## 🔄 Data Flow

```
1. BLE Packets Received (Unchanged)
   └─ All anchors upload their RSSI values
   └─ Firebase stores each anchor's detection

2. ToolRepository.getAllToolsFlow() (Unchanged)
   └─ Reads all tools from Firebase
   └─ Emits list to subscribers

3. NEW: applyDashboardAnchorSelection() (NEW)
   └─ For each tag, find all tools detecting it
   └─ Compare RSSI values
   └─ Check cache (valid for 30 seconds)
   └─ If cache miss: Calculate strongest anchor
   └─ Cache result for next 30 seconds
   └─ Return tools with selectedAnchorId/Name populated

4. ToolsListViewModel.tools (Modified flow)
   └─ Receives pre-selected tools
   └─ Emits to UI

5. UI Display (Unchanged)
   └─ Shows selectedAnchorName (not lastAnchorName)
   └─ Shows online/offline status
   └─ Shows RSSI from selected anchor
```

---

## 🧮 Selection Algorithm

### **How Selection Works**

For each BLE tag, the algorithm:

1. **Find all tools with this tag**
   - Query all tools in the list
   - Filter to only those with matching tagUuid

2. **Filter active anchors**
   - Only consider anchors with data < 60 seconds old
   - Skip anchors that haven't reported recently

3. **Compare RSSI values**
   - Strongest = highest/closest to 0 (e.g., -45 > -60)
   - If equal, keep current selection (ties don't trigger switch)

4. **Update tool with selected anchor**
   - Set `selectedAnchorId` = strongest anchor ID
   - Set `selectedAnchorName` = strongest anchor name
   - Set `lastRssi` = strongest RSSI value
   - Set `isOnline` = true (if any active anchors)

5. **Cache for 30 seconds**
   - Store result with timestamp
   - Skip recalculation if cache valid
   - Invalidate on expiration

---

## 📊 Example Scenarios

### **Scenario 1: Multiple Anchors, Display Strongest**

**Firebase (Raw):**
```
Tool 1:
  name: "Drill"
  tagUuid: "tag-abc"
  lastAnchorId: "anchor-a"
  lastAnchorName: "Phone A"
  lastRssi: -48

Tool 2:
  name: "Drill" (same tag, different record)
  tagUuid: "tag-abc"
  lastAnchorId: "anchor-b"
  lastAnchorName: "Phone B"
  lastRssi: -61
```

**Dashboard Processing:**
```
Inputs: [Tool1, Tool2]
Step 1: Find tools with "tag-abc" → [Tool1, Tool2]
Step 2: Filter active (both < 60s old) → [Tool1, Tool2]
Step 3: Compare RSSI (-48 vs -61) → -48 is strongest
Step 4: Select: anchorId=anchor-a, anchorName="Phone A", rssi=-48
Step 5: Cache result (valid for 30 seconds)
```

**Dashboard Display:**
```
Tool: "Drill"
Anchor: "Phone A" ✓ (selected)
RSSI: -48 dBm
Status: Online
Last Seen: 2 seconds ago
```

---

### **Scenario 2: Re-evaluation After 30 Seconds**

**Time = 0s:**
```
Phone A: -48 dBm ← Selected (strongest)
Phone B: -61 dBm
Display: Phone A
Cache: Valid
```

**Time = 25s (within cache):**
```
Phone A: -58 dBm
Phone B: -42 dBm
Display: Phone A (cache not expired, uses cached selection)
```

**Time = 30s+ (cache expired, recalculate):**
```
Phone A: -58 dBm
Phone B: -42 dBm
Cache: Expired → Recalculate
Result: Phone B is now strongest (-42 > -58)
Display: Phone B ← Switched!
Cache: New result cached for another 30 seconds
```

---

### **Scenario 3: Anchor Goes Offline**

**Time = 0s:**
```
Phone A: -48 dBm ← Selected
Phone B: -61 dBm
Display: Phone A, Online
```

**Time = 45s (after Phone A stops reporting):**
```
Phone A: No data (last report 45 seconds ago) ← Filtered out (> 60s? No, still active)
Phone B: -62 dBm (still reporting)
Display: Phone A (still selected, but...)
```

**Time = 70s (Phone A truly offline):**
```
Phone A: No data for 70 seconds ← FILTERED OUT (> 60s window)
Phone B: -62 dBm
Re-evaluation: Only Phone B active
Result: Switch to Phone B
Display: Phone B, Online
```

---

### **Scenario 4: All Anchors Offline**

```
Phone A: No data for 70 seconds ← Filtered out
Phone B: No data for 80 seconds ← Filtered out

Re-evaluation: No active anchors
Result: selectedAnchorId = null
Display: Last Known Anchor (keep previous value)
Status: Offline
Note: Tool data still visible, but marked as offline
```

---

## 🔧 Configuration

**Edit in `AnchorSelectionCache` constructor (or make configurable):**

```kotlin
private val cacheDurationMs: Long = 30_000L  // 30 seconds

// To adjust:
AnchorSelectionCache(cacheDurationMs = 60_000L)  // 60 seconds instead
```

**Edit in `DashboardAnchorSelector` companion object:**

```kotlin
companion object {
    private const val ACTIVE_DETECTION_WINDOW_MS = 60_000L  // 60 seconds
}

// Anchors considered active only if data < 60 seconds old
// Adjust based on your use case
```

---

## ✅ Verification & Testing

### **Run Unit Tests**

```bash
# Test DashboardAnchorSelector logic
./gradlew test com.example.hiltible.dashboard.DashboardAnchorSelectorTest

# Test caching mechanism
./gradlew test com.example.hiltible.dashboard.AnchorSelectionCacheTest
```

### **Test Scenarios Covered**

✅ Select strongest anchor (basic RSSI comparison)  
✅ Update selection after 30 seconds  
✅ Offline anchor not selected  
✅ No active anchors returns offline  
✅ Apply selection updates tool info  
✅ Null selection keeps last known  
✅ Batch selection for multiple tags  
✅ Equal RSSI keeps current selection  
✅ Cache hit/miss behavior  
✅ Cache expiration timing  
✅ Cache invalidation  

---

## 🚀 Integration Checklist

- [ ] Copy `DashboardAnchorSelector.kt` to project
- [ ] Copy `AnchorSelectionCache.kt` to project
- [ ] Copy test files to project
- [ ] Modify `ToolRepository.kt` with new methods
- [ ] Modify `ToolsListViewModel.kt` to use new flow
- [ ] Verify ToolsListScreen displays `selectedAnchorName` (already done)
- [ ] Verify MapScreen displays anchor info (already done)
- [ ] Run unit tests
- [ ] Test with 2+ real devices
- [ ] Monitor selection changes in Logcat

---

## 📝 Important Notes

### **What This Does NOT Change**
- ✅ BLE scanning continues normally
- ✅ Firebase writes happen at original frequency
- ✅ All raw data is still stored
- ✅ No impact on detection reliability

### **What This Changes**
- ❌ What the dashboard displays (strongest anchor only)
- ❌ Selection updates every 30 seconds (cached)
- ❌ Offline handling (marks offline after 60s silence)

### **Thread Safety**
- ✅ All collections are thread-safe
- ✅ Caching uses ConcurrentHashMap
- ✅ Safe for concurrent Firebase updates

### **Performance Impact**
- ✅ Minimal CPU: Selection comparison is O(n) where n = anchors per tag (usually 2-5)
- ✅ Memory: Cache stores only selection result per tag, not full data
- ✅ Firebase: No additional reads/writes (uses existing data)

---

## 🔍 Debugging

### **Enable Verbose Logging**

Look for logs with prefix:
- `DashboardAnchorSelector` - Selection decisions
- `AnchorSelectionCache` - Cache hits/misses
- `ToolRepository` - Flow emissions

### **Check Cache Stats**

```kotlin
val stats = repository.getSelectionCacheStats()
Log.d("Dashboard", stats)
```

Output example:
```
Cache Size: 3
Time Since Last Update: 5000ms
Cache Duration: 30000ms
Entries: tag-ab... (age=5200ms), tag-cd... (age=8500ms), ...
```

### **Force Recalculation**

If selections seem stale:
```kotlin
repository.invalidateSelectionCache()
// Next flow emission will recalculate all selections
```

---

## 🎓 How It Integrates With Previous System

**Previous Implementation (BLE Layer):**
- BleTagDetector tracks RSSI history per anchor
- Applies hysteresis + consecutive threshold
- Designed for local device with multiple anchors detected simultaneously

**New Implementation (Dashboard Layer):**
- Works with any BLE system
- Compares multiple independent anchor records from Firebase
- Designed for aggregating detections across multiple devices
- Can be used WITHOUT the previous system

**They Co-Exist:**
- Previous system: Handles BLE packet processing
- New system: Handles dashboard display aggregation
- Both can be active simultaneously without conflict

---

## 📞 Key Files Reference

| File | Lines | Purpose | Status |
|------|-------|---------|--------|
| DashboardAnchorSelector.kt | 150 | Selection algorithm | NEW |
| AnchorSelectionCache.kt | 100 | 30-second caching | NEW |
| ToolRepository.kt | +40 | Integration layer | MODIFIED |
| ToolsListViewModel.kt | +5 | Flow source | MODIFIED |
| DashboardAnchorSelectorTest.kt | 300 | Selection tests | NEW |
| AnchorSelectionCacheTest.kt | 200 | Cache tests | NEW |

---

## 🎯 Success Criteria

After deployment:

- [ ] Dashboard shows only 1 anchor per tag (strongest RSSI)
- [ ] Switching anchors happens every 30s max (when RSSI changes significantly)
- [ ] No rapid flapping between anchors
- [ ] Offline tags shown with last known anchor
- [ ] All unit tests pass
- [ ] No increase in Firebase usage (reads/writes)
- [ ] No performance degradation on UI
- [ ] Selection changes visible in Logcat with timestamps

---

**Summary:** This dashboard-level selection system is completely isolated from BLE reception. It works by comparing what's already in Firebase and intelligently selecting which anchor to display, creating a unified view of the strongest detection for each tag.

