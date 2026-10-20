package com.example.hiltible.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hiltible.data.entity.Tool
import com.example.hiltible.data.repository.ToolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolsListViewModel @Inject constructor(
    private val repository: ToolRepository,
    private val bleTagDetector: com.example.hiltible.ble.BleTagDetector
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Tools with dashboard-level anchor selection applied.
     * Automatically selects the strongest anchor (by RSSI) when multiple anchors detect the same tag.
     * Selection is cached for 30 seconds and re-evaluated on cache expiration.
     */
    val tools: StateFlow<List<Tool>> = repository.getToolsWithDashboardSelectionFlow()
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun addToolWithName(name: String) {
        viewModelScope.launch {
            try {
                repository.insertTool(
                    Tool(name = name)
                )
            } catch (_: Exception) { }
        }
    }

    fun deleteTool(toolId: String) {
        viewModelScope.launch {
            try {
                repository.deleteTool(toolId)
            } catch (_: Exception) { }
        }
    }

    fun unmapTag(toolId: String) {
        viewModelScope.launch {
            try {
                repository.unmapTag(toolId)
                // Refresh BLE tag cache so service picks up the change immediately
                bleTagDetector.refreshTagCache()
            } catch (_: Exception) { }
        }
    }

    fun clearAllLastSeen() {
        viewModelScope.launch {
            try {
                repository.clearAllLastSeen()
            } catch (_: Exception) { }
        }
    }
}
