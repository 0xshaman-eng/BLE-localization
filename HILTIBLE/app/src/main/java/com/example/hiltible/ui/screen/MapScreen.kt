package com.example.hiltible.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hiltible.data.entity.Tool
import com.example.hiltible.ui.viewmodel.ToolsListViewModel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Map of all located tools. Each tool is a pin at its location; tapping one shows its details.
 *
 * @param focusToolId when set (e.g. a tool tapped in the Tools tab), the map zooms to that tool
 * and shows its details, then calls [onFocusHandled].
 */
@Composable
fun MapScreen(
    viewModel: ToolsListViewModel = hiltViewModel(),
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    focusToolId: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolsWithLocation = tools.filter { it.hasLocation() }

    var selectedToolId by remember { mutableStateOf<String?>(null) }
    // Fit the camera to the tools only once, so user zoom/pan is not reset on every update
    val initialFitDone = remember { AtomicBoolean(false) }
    val mapView = remember { createMapView(context, zoom = 3.0, center = GeoPoint(0.0, 0.0)) }

    DisposableEffect(Unit) {
        onDispose {
            mapView.onDetach()
        }
    }

    // Zoom to the tool requested from the Tools tab once its data is loaded
    LaunchedEffect(focusToolId, tools.isNotEmpty()) {
        if (focusToolId == null || tools.isEmpty()) return@LaunchedEffect
        val tool = tools.find { it.id == focusToolId }
        if (tool != null && tool.hasLocation()) {
            initialFitDone.set(true)
            mapView.controller.setZoom(mapView.maxZoomLevel)
            mapView.controller.setCenter(GeoPoint(tool.lastLat, tool.lastLng))
            selectedToolId = tool.id
        } else {
            Toast.makeText(context, "${tool?.name ?: "Tool"} has no location yet", Toast.LENGTH_SHORT).show()
        }
        onFocusHandled()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                try {
                    map.overlays.clear()
                    // Tapping empty map closes the details card
                    map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            selectedToolId = null
                            return false
                        }

                        override fun longPressHelper(p: GeoPoint?) = false
                    }))
                    map.overlays.add(CopyrightOverlay(map.context))

                    toolsWithLocation.forEach { tool ->
                        val marker = Marker(map).apply {
                            position = GeoPoint(tool.lastLat, tool.lastLng)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = tool.name
                            setOnMarkerClickListener { _, _ ->
                                selectedToolId = tool.id
                                true
                            }
                        }
                        map.overlays.add(marker)
                    }

                    if (toolsWithLocation.isNotEmpty() && initialFitDone.compareAndSet(false, true)) {
                        fitToTools(map, toolsWithLocation)
                    }

                    map.invalidate()
                } catch (e: Exception) {
                    Log.e("MapScreen", "Error updating map", e)
                }
            }
        )

        if (toolsWithLocation.isEmpty()) {
            Text(
                text = "No tool locations yet.\nStart BLE tracking to detect tools.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(onClick = { mapView.controller.zoomIn() }) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in")
            }
            SmallFloatingActionButton(onClick = { mapView.controller.zoomOut() }) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out")
            }
        }

        val selectedTool = toolsWithLocation.find { it.id == selectedToolId }
        if (selectedTool != null) {
            ToolDetailsCard(
                tool = selectedTool,
                onClose = { selectedToolId = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 32.dp)
            )
        } else {
            FloatingActionButton(
                onClick = { viewModel.clearAllLastSeen() },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 40.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Clear All Last Seen")
            }
        }
    }
}

@Composable
private fun ToolDetailsCard(
    tool: Tool,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val anchorName = tool.selectedAnchorName ?: tool.lastAnchorName

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(isLive = tool.isLive())
                    }
                    Text(
                        text = tool.tagUuid?.let { formatTagForDisplay(it) } ?: "No tag mapped",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            tool.lastSeenAt?.let { seenAt ->
                DetailRow(Icons.Default.Schedule, "${relativeTime(seenAt)} · ${formatTimestamp(seenAt)}")
            }
            tool.lastRssi?.let { rssi ->
                DetailRow(Icons.Default.Sensors, "$rssi dBm · ${signalLabel(rssi)}") { SignalBars(rssi) }
            }
            if (anchorName != null) {
                DetailRow(Icons.Default.PhoneAndroid, "Seen by $anchorName")
            }
            DetailRow(
                Icons.Default.Place,
                "%.6f, %.6f".format(Locale.US, tool.lastLat, tool.lastLng)
            ) {
                TextButton(onClick = { openDirections(context, tool) }) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Directions")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    text: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
private fun StatusBadge(isLive: Boolean) {
    val color = if (isLive) LiveGreen else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isLive) "Live" else "Offline",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val level = signalLevel(rssi)
    val activeColor = when (level) {
        4, 3 -> LiveGreen
        2 -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        (1..4).forEach { bar ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((4 + bar * 3).dp)
                    .background(
                        if (bar <= level) activeColor else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

private val LiveGreen = Color(0xFF2E7D32)

// A tag counts as live if an anchor reported it recently
private const val LIVE_THRESHOLD_MS = 2 * 60_000L


private fun Tool.hasLocation() = (lastLat != 0.0 || lastLng != 0.0) && lastLat.isFinite() && lastLng.isFinite()

private fun Tool.isLive(): Boolean =
    isOnline || (lastSeenAt != null && System.currentTimeMillis() - lastSeenAt < LIVE_THRESHOLD_MS)

private fun signalLevel(rssi: Int) = when {
    rssi >= -60 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    else -> 1
}

private fun signalLabel(rssi: Int) = when (signalLevel(rssi)) {
    4 -> "Excellent"
    3 -> "Good"
    2 -> "Fair"
    else -> "Weak"
}

private fun relativeTime(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.SECOND_IN_MILLIS
    ).toString()

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(java.util.Date(timestamp))

private fun openDirections(context: Context, tool: Tool) {
    val label = Uri.encode(tool.name)
    val uri = Uri.parse("geo:${tool.lastLat},${tool.lastLng}?q=${tool.lastLat},${tool.lastLng}($label)")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Log.w("MapScreen", "No maps app available for directions", e)
    }
}

private fun createMapView(context: Context, zoom: Double, center: GeoPoint) =
    MapView(context).apply {
        setTileSource(OSM_TILE_SOURCE)
        setMaxZoomLevel(OSM_TILE_SOURCE.maximumZoomLevel.toDouble())
        setMultiTouchControls(true)
        // Replaced by the Compose zoom buttons
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        controller.setZoom(zoom)
        controller.setCenter(center)
    }

private fun fitToTools(map: MapView, tools: List<Tool>) {
    try {
        val box = BoundingBox(
            tools.maxOf { it.lastLat },
            tools.maxOf { it.lastLng },
            tools.minOf { it.lastLat },
            tools.minOf { it.lastLng }
        ).increaseByScale(1.3f)
        // zoomToBoundingBox needs the view size, so wait for the first layout
        if (map.isLayoutOccurred) {
            map.zoomToBoundingBox(box, false)
        } else {
            map.addOnFirstLayoutListener { _, _, _, _, _ ->
                map.zoomToBoundingBox(box, false)
            }
        }
    } catch (e: Exception) {
        Log.e("MapScreen", "Error zooming to bounds", e)
    }
}

// Same as TileSourceFactory.MAPNIK, but without FLAG_USER_AGENT_NORMALIZED: MAPNIK replaces the
// configured user agent with "<applicationId>/<versionCode>", and OSM blocks "com.example.*" ids.
// The distinct name also keeps previously cached "Access blocked" tiles from being reused.
private val OSM_TILE_SOURCE = XYTileSource(
    "OpenStreetMap",
    0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors",
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or
            TileSourcePolicy.FLAG_NO_PREVENTIVE or
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
    )
)
