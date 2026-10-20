package com.example.hiltible

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.hiltible.service.BleTrackingService
import com.example.hiltible.ui.screen.MapScreen
import com.example.hiltible.ui.screen.TagMappingScreen
import com.example.hiltible.ui.screen.ToolsListScreen
import com.example.hiltible.ui.screen.BarcodeScanScreen
import com.example.hiltible.ui.viewmodel.TagMappingViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.hiltible.ui.theme.HILTIBLETheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var hasRequestedBatteryOptimization = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startTrackingService()
        }
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d("MainActivity", "Background location permission granted")
        } else {
            Log.w("MainActivity", "Background location permission denied - app won't work with screen off")
        }
        startTrackingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request permissions and start service automatically
        requestPermissionsAndStartService()
        
        setContent {
            HILTIBLETheme {
                var currentScreen by remember { mutableStateOf("map") }
                var selectedToolId by remember { mutableStateOf<String?>(null) }
                // Tool to zoom to on the map, set when a tool is tapped in the Tools tab
                var focusToolId by remember { mutableStateOf<String?>(null) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (currentScreen == "map" || currentScreen == "tools") {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = currentScreen == "map",
                                    onClick = { currentScreen = "map" },
                                    icon = { Icon(Icons.Default.Map, contentDescription = "Map") },
                                    label = { Text("Map") }
                                )
                                NavigationBarItem(
                                    selected = currentScreen == "tools",
                                    onClick = { currentScreen = "tools" },
                                    icon = { Icon(Icons.Default.Build, contentDescription = "Tools") },
                                    label = { Text("Tools") }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    // Use when with proper keys to prevent unnecessary recomposition
                    when (currentScreen) {
                        "map" -> {
                            androidx.compose.runtime.key("map_screen") {
                                MapScreen(
                                    onRefresh = {
                                        // clearAllLastSeen is handled inside MapScreen via its ViewModel
                                    },
                                    modifier = Modifier.padding(innerPadding),
                                    focusToolId = focusToolId,
                                    onFocusHandled = { focusToolId = null }
                                )
                            }
                        }
                        "tools" -> {
                            androidx.compose.runtime.key("tools_screen") {
                                ToolsListScreen(
                                    onNavigateToTagMapping = { toolId ->
                                        selectedToolId = toolId
                                        currentScreen = "tagMapping"
                                    },
                                    onLocateTool = { tool ->
                                        focusToolId = tool.id
                                        currentScreen = "map"
                                    },
                                    modifier = Modifier.padding(innerPadding)
                                )
                            }
                        }
                        "tagMapping" -> {
                            androidx.compose.runtime.key("tagMapping_screen") {
                                val viewModel: TagMappingViewModel = hiltViewModel()
                                TagMappingScreen(
                                    onNavigateBack = {
                                        currentScreen = "tools"
                                        selectedToolId = null
                                    },
                                    onNavigateToBarcodeScan = {
                                        currentScreen = "barcodeScan"
                                    },
                                    toolId = selectedToolId,
                                    viewModel = viewModel
                                )
                            }
                        }
                        "barcodeScan" -> {
                            androidx.compose.runtime.key("barcodeScan_screen") {
                                val viewModel: TagMappingViewModel = hiltViewModel()
                                BarcodeScanScreen(
                                    onNavigateBack = {
                                        currentScreen = "tagMapping"
                                    },
                                    onBarcodeScanned = { barcode ->
                                        viewModel.selectTagFromBarcode(barcode)
                                        currentScreen = "tagMapping"
                                    },
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Ensure service is running when app comes to foreground
        // Only restart the service, not the battery optimization dialog
        ensureServiceRunning()
    }

    private fun ensureServiceRunning() {
        try {
            // Check if we have the required permissions
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                this, 
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasBluetoothPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            
            // Only start service if we have permissions
            if (hasLocationPermission && hasBluetoothPermission) {
                val serviceIntent = Intent(this, BleTrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d("MainActivity", "Service restart requested in onResume")
            } else {
                Log.w("MainActivity", "Cannot start service - missing permissions")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error ensuring service is running", e)
        }
    }

    private fun requestPermissionsAndStartService() {
        try {
            // Request basic permissions first
            val basicPermissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                basicPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            val missingBasicPermissions = basicPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingBasicPermissions.isNotEmpty()) {
                permissionLauncher.launch(missingBasicPermissions.toTypedArray())
            } else {
                // Basic permissions granted, now check background location
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestBackgroundLocation()
                } else {
                    startTrackingService()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting permissions", e)
        }
    }

    private fun requestBackgroundLocation() {
        // Android 11+ requires separate request for background location
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Show rationale first
            AlertDialog.Builder(this)
                .setTitle("Background Location Required")
                .setMessage("This app needs background location access to track BLE tags when the screen is off. Please select 'Allow all the time' on the next screen.")
                .setPositiveButton("Continue") { _, _ ->
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    // Start service without background location (won't work with screen off)
                    startTrackingService()
                }
                .show()
        } else {
            // Android 10 and below can request directly
            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun startTrackingService() {
        // Request battery optimization exemption only once
        if (!hasRequestedBatteryOptimization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasRequestedBatteryOptimization = true
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to request battery optimization exemption", e)
                }
            }
        }
        
        // Start the service
        val serviceIntent = Intent(this, BleTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}