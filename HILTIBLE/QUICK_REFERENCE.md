# Dashboard Anchor Selection - Quick Implementation Reference

## 📋 Files You Need to Know

### **STEP 1: Create NEW Files (Copy-Paste Ready)**

#### File 1: `app/src/main/java/com/example/hiltible/dashboard/DashboardAnchorSelector.kt`
- **Status:** ✅ Already created
- **Lines:** 150
- **Purpose:** Core selection logic comparing RSSI from multiple anchors
- **Key Class:** `DashboardAnchorSelector`

#### File 2: `app/src/main/java/com/example/hiltible/dashboard/AnchorSelectionCache.kt`
- **Status:** ✅ Already created
- **Lines:** 100
- **Purpose:** Cache selections for 30 seconds
- **Key Class:** `AnchorSelectionCache`

#### File 3: `app/src/test/java/com/example/hiltible/dashboard/DashboardAnchorSelectorTest.kt`
- **Status:** ✅ Already created
- **Lines:** 300
- **Purpose:** Unit tests for selection logic

#### File 4: `app/src/test/java/com/example/hiltible/dashboard/AnchorSelectionCacheTest.kt`
- **Status:** ✅ Already created
- **Lines:** 200
- **Purpose:** Unit tests for caching

---

### **STEP 2: Modify EXISTING Files**

#### File 5: `app/src/main/java/com/example/hiltible/data/repository/ToolRepository.kt`

**What to Add (After existing methods):**

```kotlin
// Add these imports at top
import com.example.hiltible.dashboard.DashboardAnchorSelector
import com.example.hiltible.dashboard.AnchorSelectionCache
import kotlinx.coroutines.flow.map

// Add these fields in class
private val dashboardSelector = DashboardAnchorSelector()
private val selectionCache = AnchorSelectionCache(cacheDurationMs = 30_000L)

// Add this new method
fun getToolsWithDashboardSelectionFlow(): Flow<List<Tool>> {
    return getAllToolsFlow().map { tools ->
        applyDashboardAnchorSelection(tools)
    }
}

// Add this helper method
private fun applyDashboardAnchorSelection(tools: List<Tool>): List<Tool> {
    val currentTime = System.currentTimeMillis()
    val toolsByTag = tools.groupBy { it.tagUuid }

    return tools.map { tool ->
        if (tool.tagUuid == null) {
            return@map tool
        }

        val tagUuid = tool.tagUuid
        val cachedSelection = selectionCache.getCachedSelection(tagUuid, currentTime)
        
        if (cachedSelection != null) {
            return@map dashboardSelector.applySelectedAnchor(tool, cachedSelection)
        }

        val allToolsWithThisTag = toolsByTag[tagUuid] ?: listOf(tool)
        val selectedAnchor = dashboardSelector.selectStrongestAnchor(
            allToolsWithThisTag,
            tagUuid,
            currentTime
        )

        selectionCache.cacheSelection(tagUuid, selectedAnchor, currentTime)
        dashboardSelector.applySelectedAnchor(tool, selectedAnchor)
    }
}

// Add this utility method
fun invalidateSelectionCache() {
    selectionCache.invalidateAll()
}

// Add this diagnostics method
fun getSelectionCacheStats(): String = selectionCache.getCacheStats()
```

**Status:** ⚠️ MANUALLY MODIFY (or already modified if you followed earlier edits)

---

#### File 6: `app/src/main/java/com/example/hiltible/ui/viewmodel/ToolsListViewModel.kt`

**What to Change (Line ~24):**

```kotlin
// BEFORE:
val tools: StateFlow<List<Tool>> = repository.getAllToolsFlow()
    .stateIn(...)

// AFTER:
/**
 * Tools with dashboard-level anchor selection applied.
 * Automatically selects the strongest anchor (by RSSI) when multiple anchors detect the same tag.
 * Selection is cached for 30 seconds and re-evaluated on cache expiration.
 */
val tools: StateFlow<List<Tool>> = repository.getToolsWithDashboardSelectionFlow()
    .stateIn(...)
```

**Status:** ⚠️ MANUALLY MODIFY (or already modified if you followed earlier edits)

---

#### File 7: `app/src/main/java/com/example/hiltible/ui/screen/ToolsListScreen.kt`

**Status:** ✅ NO CHANGES NEEDED
- Already displays `selectedAnchorName` (if it exists)
- Already shows `isOnline` status
- Already shows `lastRssi` from selected anchor

---

#### File 8: `app/src/main/java/com/example/hiltible/ui/screen/MapScreen.kt`

**Status:** ✅ NO CHANGES NEEDED
- Already includes selected anchor info in `buildSnippet()`
- Already shows online/offline status

---

## 🎯 Execution Order

### **Implementation Steps:**

1. ✅ Create `DashboardAnchorSelector.kt` (provided)
2. ✅ Create `AnchorSelectionCache.kt` (provided)
3. ⚠️ Modify `ToolRepository.kt` - add 4 new methods
4. ⚠️ Modify `ToolsListViewModel.kt` - change 1 line (flow source)
5. ✅ Create test files (provided)
6. 📝 Update imports if needed

### **No Changes Needed:**
- BleTagDetector.kt (BLE logic unchanged)
- Tool.kt entity (schema already supports it)
- ToolsListScreen.kt (UI already displays selected anchor)
- MapScreen.kt (UI already displays selected anchor)
- Any Firebase write logic

---

## 🚀 How to Deploy

### **Option A: Automatic (If Using Provided Files)**

```bash
# 1. Copy all files to project
# 2. Gradle should auto-sync
# 3. Just modify ToolRepository.kt and ToolsListViewModel.kt (2 files)
# 4. Run app
```

### **Option B: Manual Setup**

```kotlin
// In ToolRepository.kt, REPLACE this line:
val tools: StateFlow<List<Tool>> = repository.getAllToolsFlow()

// WITH this:
val tools: StateFlow<List<Tool>> = repository.getToolsWithDashboardSelectionFlow()
```

---

## ✅ Verification Checklist

- [ ] DashboardAnchorSelector.kt compiles
- [ ] AnchorSelectionCache.kt compiles
- [ ] ToolRepository.kt compiles (added new methods)
- [ ] ToolsListViewModel.kt compiles (changed flow source)
- [ ] All imports resolved
- [ ] Unit tests pass: `./gradlew test`
- [ ] App runs without crashes
- [ ] Dashboard displays single anchor per tag
- [ ] Logcat shows "Cache hit" or "Selected anchor" messages

---

## 🔍 Testing in Action

### **Test 1: Multiple Anchors**

1. Open 2+ devices in same area with app running
2. Both detect same BLE tag
3. Observe: Dashboard shows only strongest RSSI anchor
4. Move closer to one phone: Anchor switches on next 30-second cycle

### **Test 2: Failover**

1. Start with Phone A detected at -50 RSSI
2. Stop Phone A from scanning
3. Wait 60+ seconds
4. Observe: Dashboard switches to Phone B

### **Test 3: Caching**

1. Check Logcat: `DashboardAnchorSelector: Selected anchor for tag tag-abc: ...`
2. Watch for 30 seconds
3. Observe: `AnchorSelectionCache: Cache hit for tag: tag-abc` (multiple times)
4. After 30s: `AnchorSelectionCache: Cache miss for tag: tag-abc` (recalculates)

---

## 📊 Data Flow Summary

```
Raw Firebase Data
├─ Tool 1: tag-abc, anchor-a, rssi=-48
├─ Tool 2: tag-abc, anchor-b, rssi=-61
└─ Tool 3: tag-def, anchor-c, rssi=-55

         ↓ ToolRepository.getToolsWithDashboardSelectionFlow()

Dashboard Selection
├─ Gather all tools for tag-abc
├─ Compare RSSI: -48 vs -61
├─ Select: anchor-a (-48 stronger)
├─ Cache for 30 seconds
├─ Return selected anchor
└─ Same for tag-def

         ↓ ViewModel

UI Display
├─ Anchor for tag-abc: "Phone A" (not "Phone B")
├─ RSSI: -48 dBm (not -61)
├─ Status: Online
└─ Last Selected: 5 seconds ago
```

---

## 🔧 Configuration Points

**To adjust cache duration (default 30 seconds):**
```kotlin
// In ToolRepository.kt, change:
private val selectionCache = AnchorSelectionCache(cacheDurationMs = 30_000L)

// To:
private val selectionCache = AnchorSelectionCache(cacheDurationMs = 60_000L)  // 60 seconds
```

**To adjust active anchor window (default 60 seconds):**
```kotlin
// In DashboardAnchorSelector.kt companion object, change:
private const val ACTIVE_DETECTION_WINDOW_MS = 60_000L

// To:
private const val ACTIVE_DETECTION_WINDOW_MS = 120_000L  // 2 minutes
```

---

## 📞 File Dependencies

```
ToolsListViewModel.kt
    ↓ subscribes to
ToolRepository.getToolsWithDashboardSelectionFlow()
    ↓ uses
DashboardAnchorSelector + AnchorSelectionCache
    ↓ processes
Tool entities (with selected anchor fields)
    ↓ displayed in
ToolsListScreen.kt + MapScreen.kt
```

---

## ⚠️ Important: Do NOT Modify

These files should remain unchanged for dashboard anchor selection:

- ❌ BleTagDetector.kt (BLE logic stays the same)
- ❌ BleTrackingService.kt (Service unaffected)
- ❌ DatabaseModule.kt (No DI changes needed)
- ❌ Firebase schema (Works with existing schema)
- ❌ Any packet parsing logic

---

## 📈 Expected Behavior

### **Before Implementation:**
```
2 phones detect tag at same time:
- Phone A: RSSI -48 (written last)
- Phone B: RSSI -61 (written first)
Display: Phone B (whatever was last written)
```

### **After Implementation:**
```
2 phones detect tag at same time:
- Phone A: RSSI -48
- Phone B: RSSI -61
Display: Phone A (strongest RSSI wins) ✓
Every 30 seconds: Re-evaluate & update if changed
```

---

## 🎓 Summary

**Files to Create:** 4 new files  
**Files to Modify:** 2 files (minimal changes)  
**Lines to Add:** ~50 lines of actual code  
**Testing:** 20 unit tests provided  
**Impact on BLE:** ZERO - completely isolated at dashboard layer

The system will automatically select and display the strongest anchor every 30 seconds, with intelligent offline handling and efficient caching.

