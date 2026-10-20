package com.example.hiltible.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.hiltible.ui.viewmodel.TagMappingViewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun BarcodeScanScreen(
    viewModel: TagMappingViewModel,
    onNavigateBack: () -> Unit,
    onBarcodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // A tone generator for audio feedback
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }

    // Consensus state
    var recentReadings by remember { mutableStateOf(listOf<String>()) }
    var scanResult by remember { mutableStateOf<String?>(null) }
    var scanMessage by remember { mutableStateOf("Align barcode within the crosshair") }
    var scanError by remember { mutableStateOf(false) }

    val scanner = remember { BarcodeScanning.getClient() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            scanner.close()
            toneGenerator.release()
        }
    }

    if (!hasCameraPermission) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera permission is required to scan barcodes.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Permission")
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (scanResult != null) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                    val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                    
                                    scanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            // Find barcode that contains the center crosshair
                                            // Note: boundingBox coordinates are in the frame space, considering rotation.
                                            val centerX = image.width / 2
                                            val centerY = image.height / 2

                                            // Find the barcode closest to the center, rather than strict point-overlap
                                            val overlappingBarcode = barcodes.minByOrNull { barcode ->
                                                val box = barcode.boundingBox
                                                if (box != null) {
                                                    val dx = box.centerX() - centerX
                                                    val dy = box.centerY() - centerY
                                                    (dx * dx + dy * dy)
                                                } else {
                                                    Int.MAX_VALUE
                                                }
                                            }

                                            if (overlappingBarcode != null) {
                                                // Apply ISO/IEC 8859-1 encoding fallback from SFI architecture
                                                var raw = overlappingBarcode.rawValue ?: ""
                                                val format = overlappingBarcode.format
                                                if (format == Barcode.FORMAT_DATA_MATRIX || format == Barcode.FORMAT_QR_CODE) {
                                                    try {
                                                        val bytes = raw.toByteArray(StandardCharsets.ISO_8859_1)
                                                        val correctedValue = String(bytes, StandardCharsets.ISO_8859_1)
                                                        raw = correctedValue
                                                    } catch (e: Exception) {
                                                        Log.w("BarcodeScanScreen", "Failed to apply ISO encoding fallback", e)
                                                    }
                                                }

                                                // Clean to strictly digits to map to tag logic
                                                val digitsOnly = raw.replace(Regex("[^0-9]"), "")
                                                
                                                if (digitsOnly.isNotEmpty() && !scanError) {
                                                    val newReadings = recentReadings + digitsOnly
                                                    if (newReadings.size >= 5) {
                                                        val majority = newReadings.takeLast(5).groupBy { it }.maxByOrNull { it.value.size }
                                                        if (majority != null && majority.value.size == 5) {
                                                            scanResult = majority.key
                                                            coroutineScope.launch {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                toneGenerator.startTone(ToneGenerator.TONE_SUP_PIP, 120)
                                                                scanMessage = "Success: ${majority.key}"
                                                                scanError = false
                                                                delay(1000)
                                                                onBarcodeScanned(majority.key)
                                                            }
                                                        } else {
                                                            recentReadings = emptyList()
                                                            coroutineScope.launch {
                                                                scanError = true
                                                                scanMessage = "Consensus failed, retrying..."
                                                                toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 120)
                                                                delay(150)
                                                                toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 120)
                                                                delay(1000)
                                                                scanMessage = "Align barcode within the crosshair"
                                                                scanError = false
                                                            }
                                                        }
                                                    } else {
                                                        recentReadings = newReadings
                                                    }
                                                }
                                            } else {
                                                // If we have no overlapping barcode, clear the running readings
                                                if (recentReadings.isNotEmpty()) {
                                                    recentReadings = emptyList()
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                    } catch (e: Exception) {
                        Log.e("BarcodeScanScreen", "Use case binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Viewfinder
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            // 320x160 dp equivalent
            val rectWidth = 320.dp.toPx()
            val rectHeight = 160.dp.toPx()
            
            val left = (canvasWidth - rectWidth) / 2
            val top = (canvasHeight - rectHeight) / 2
            val right = left + rectWidth
            val bottom = top + rectHeight

            // Draw semi-transparent background with clear window
            val p = Path().apply {
                addRect(Rect(0f, 0f, canvasWidth, canvasHeight))
                addRoundRect(RoundRect(left, top, right, bottom, CornerRadius(16f)))
                fillType = PathFillType.EvenOdd
            }
            drawPath(path = p, color = Color.Black.copy(alpha = 0.6f))
            
            // Draw crosshair
            val crosshairLength = 20.dp.toPx()
            val crosshairThickness = 2.dp.toPx()
            val crosshairGap = 4.dp.toPx()
            val centerX = canvasWidth / 2
            val centerY = canvasHeight / 2
            
            val paintColor = if (scanError) Color.Red else (if (scanResult != null) Color.Green else Color.White)

            // Horizontal lines
            drawLine(paintColor, Offset(centerX - crosshairGap - crosshairLength, centerY), Offset(centerX - crosshairGap, centerY), strokeWidth = crosshairThickness)
            drawLine(paintColor, Offset(centerX + crosshairGap, centerY), Offset(centerX + crosshairGap + crosshairLength, centerY), strokeWidth = crosshairThickness)
            // Vertical lines
            drawLine(paintColor, Offset(centerX, centerY - crosshairGap - crosshairLength), Offset(centerX, centerY - crosshairGap), strokeWidth = crosshairThickness)
            drawLine(paintColor, Offset(centerX, centerY + crosshairGap), Offset(centerX, centerY + crosshairGap + crosshairLength), strokeWidth = crosshairThickness)
        }
        
        // Status Text
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = scanMessage,
                color = if (scanError) Color.Red else (if (scanResult != null) Color.Green else Color.White),
                modifier = Modifier.padding(bottom = 64.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        // Back Button
        IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.padding(16.dp).statusBarsPadding()
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}
