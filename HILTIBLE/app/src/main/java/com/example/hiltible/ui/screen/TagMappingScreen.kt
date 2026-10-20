package com.example.hiltible.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hiltible.ui.viewmodel.DiscoveredTag
import com.example.hiltible.ui.viewmodel.ScanState
import com.example.hiltible.ui.viewmodel.TagMappingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagMappingScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBarcodeScan: () -> Unit = {},
    toolId: String? = null,
    viewModel: TagMappingViewModel = hiltViewModel()
) {
    // Set the toolId in ViewModel if provided
    LaunchedEffect(toolId) {
        toolId?.let { viewModel.setToolId(it) }
    }

    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val discoveredTags by viewModel.discoveredTags.collectAsStateWithLifecycle()
    val mappingComplete by viewModel.mappingComplete.collectAsStateWithLifecycle()
    val mappingError by viewModel.mappingError.collectAsStateWithLifecycle()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Reset mapping complete and refresh tags when screen is first displayed
    LaunchedEffect(Unit) {
        viewModel.resetMappingComplete()
        viewModel.refreshTags()
    }

    LaunchedEffect(mappingComplete) {
        if (mappingComplete) {
            viewModel.resetMappingComplete()
            scope.launch {
                snackbarHostState.showSnackbar("Tag mapped successfully!")
            }
            kotlinx.coroutines.delay(500) // Brief delay to show snackbar
            onNavigateBack()
        }
    }

    LaunchedEffect(mappingError) {
        mappingError?.let { errorMsg ->
            viewModel.clearMappingError()
            scope.launch {
                snackbarHostState.showSnackbar(errorMsg)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Map Tag") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Unified action buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { viewModel.startScan() },
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("BLE Scan")
                }
                
                Button(
                    onClick = onNavigateToBarcodeScan,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Camera Scan")
                }
            }

            when (scanState) {
                ScanState.IDLE -> {
                    IdleState()
                }

                ScanState.SCANNING -> {
                    ScanningState()
                    Spacer(modifier = Modifier.height(16.dp))
                    TagList(
                        tags = discoveredTags,
                        onSelectTag = { viewModel.selectTag(it) }
                    )
                }

                ScanState.DONE -> {
                    DoneState(
                        tagsFound = discoveredTags.isNotEmpty()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TagList(
                        tags = discoveredTags,
                        onSelectTag = { viewModel.selectTag(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.BluetoothSearching,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Ready to map BLE tags",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Choose a scan method above to begin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScanningState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Scanning for BLE tags...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DoneState(tagsFound: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (tagsFound) "Scan complete. Select a tag below." else "No tags found nearby.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TagList(
    tags: List<DiscoveredTag>,
    onSelectTag: (String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tags, key = { it.tagUuid }) { tag ->
            val isAssigned = tag.assignedToolName != null
            val alpha by animateFloatAsState(
                targetValue = if (isAssigned) 0.5f else 1f,
                label = "tagAlpha"
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(alpha)
                    .then(
                        if (!isAssigned) {
                            Modifier.clickable { onSelectTag(tag.tagUuid) }
                        } else {
                            Modifier
                        }
                    ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (!isAssigned) 2.dp else 1.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (!isAssigned)
                        MaterialTheme.colorScheme.surface
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (!isAssigned)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outline
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        // Device name or address as primary identifier
                        val displayName = tag.deviceName ?: tag.deviceAddress
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (!isAssigned)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Text(
                            text = if (!isAssigned) {
                                when {
                                    tag.isEla -> "ELA Blue Coin Tag - Available"
                                    tag.isHilti -> "Hilti BLE Tag - Available"
                                    else -> "BLE Tag - Available"
                                }
                            } else {
                                "Already mapped to \"${tag.assignedToolName}\""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!isAssigned)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                            fontWeight = if (isAssigned) FontWeight.Bold else FontWeight.Normal
                        )
                        
                        Text(
                            text = if (tag.isEla) "Tag: ${tag.tagUuidDecimal}" else "UUID: ${tag.tagUuidDecimal}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Text(
                            text = "RSSI: ${tag.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
