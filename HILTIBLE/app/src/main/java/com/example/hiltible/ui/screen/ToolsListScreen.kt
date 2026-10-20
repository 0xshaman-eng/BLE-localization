package com.example.hiltible.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.os.Build
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hiltible.data.entity.Tool
import com.example.hiltible.ui.viewmodel.ToolsListViewModel
import java.text.SimpleDateFormat
import java.util.Locale

// Format tag UUID for display: ELA tags shown as "C ID 015E58", Hilti tags converted hex->decimal
fun formatTagForDisplay(tagUuid: String?): String {
    if (tagUuid == null || tagUuid.isEmpty()) return ""
    
    // ELA tags are stored as "C ID XXXXXX" - display directly
    if (tagUuid.startsWith("C ID ")) return tagUuid
    
    return try {
        // Keep raw hex for Milwaukee tags so they display as 0100082C59
        val cleanHex = tagUuid.replace("[^0-9a-fA-F]".toRegex(), "").lowercase()
        if (cleanHex.length == 10) return cleanHex.uppercase()
        
        // Hilti tags: hex -> decimal conversion
        // Check if it has the d322 prefix, if so, remove it for conversion
        val variablePart = if (cleanHex.startsWith("d322")) {
            cleanHex.substring(4) // Remove d322 prefix
        } else {
            cleanHex
        }
        
        // Reverse bytes for little-endian: 18666c12 -> 126c6618
        val reversedHex = StringBuilder()
        for (i in variablePart.length - 2 downTo 0 step 2) {
            reversedHex.append(variablePart.substring(i, i + 2))
        }
        
        // Convert entire hex number to decimal
        val decimal = reversedHex.toString().toLong(16)
        
        decimal.toString()
    } catch (e: Exception) {
        tagUuid // Return original if conversion fails
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsListScreen(
    onNavigateToTagMapping: (toolId: String) -> Unit,
    onLocateTool: (Tool) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ToolsListViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddToolDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                viewModel.addToolWithName(name)
                showAddDialog = false
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Service Status Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BLE Tracking Service",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Continuously scans for Hilti tags",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Service status indicator
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = androidx.compose.ui.graphics.Color.Green,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Running",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.Green
                        )
                    }
                }
            }

            // Tools List
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                if (isLoading && tools.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (tools.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.NearMe,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No tools yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap + to add your first tool",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        items(tools, key = { it.id }) { tool ->
                            ToolCard(
                                tool = tool,
                                onMapTag = { onNavigateToTagMapping(tool.id) },
                                onUnmapTag = { viewModel.unmapTag(tool.id) },
                                onDelete = { viewModel.deleteTool(tool.id) },
                                onLocate = { onLocateTool(tool) }
                            )
                        }
                    }
                }
            }
        }

        // Floating Action Button
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Tool")
        }
    }
}

@Composable
private fun ToolCard(
    tool: Tool,
    onMapTag: () -> Unit,
    onUnmapTag: () -> Unit,
    onDelete: () -> Unit,
    onLocate: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${tool.name}?") },
            text = { Text("This will permanently remove this tool and unlink any mapped tag.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        // Tapping the card shows the tool on the map
        onClick = onLocate,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row: name + delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Tag info
            if (tool.tagUuid != null) {
                Text(
                    text = "Tag: ${formatTagForDisplay(tool.tagUuid)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "No tag mapped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Last seen info
            if (tool.lastSeenAt != null && (tool.lastLat != 0.0 || tool.lastLng != 0.0)) {
                Spacer(modifier = Modifier.height(4.dp))
                val sdf = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Last seen: ${sdf.format(java.util.Date(tool.lastSeenAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Selected anchor info
            if (tool.selectedAnchorName != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (tool.isOnline) androidx.compose.ui.graphics.Color.Green
                                        else androidx.compose.ui.graphics.Color.Gray,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Anchor: ${tool.selectedAnchorName} (RSSI: ${tool.lastRssi ?: "N/A"} dBm)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons, stacked so each can take the full width
            Column(modifier = Modifier.fillMaxWidth()) {
                if (tool.tagUuid == null) {
                    Button(
                        onClick = onMapTag,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Map Tag")
                    }
                } else {
                    OutlinedButton(
                        onClick = onUnmapTag,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Unmap")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FilledTonalButton(
                        onClick = onLocate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Locate")
                    }
                }
            }
        }
    }
}

@Composable
private fun AddToolDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var toolName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tool") },
        text = {
            OutlinedTextField(
                value = toolName,
                onValueChange = { toolName = it },
                label = { Text("Tool name (e.g. Hammer, Drill)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (toolName.isNotBlank()) onConfirm(toolName.trim()) },
                enabled = toolName.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
