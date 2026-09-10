package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.engine.CameraEngine
import com.example.engine.OpenGLRenderer
import com.example.model.PresetData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LiveLightCameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsState()

    // Camera Permission handling
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Preset File Picker Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importPresetFromUri(it)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Engine & Renderer references
    val cameraEngine = remember { CameraEngine(context) }
    var openGlRenderer by remember { mutableStateOf<OpenGLRenderer?>(null) }
    var glSurfaceViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }

    // Shutter flash animation
    val shutterAlpha = remember { Animatable(0f) }

    // Fullscreen photo preview dialog state
    var previewPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // Sync active preset and intensity to OpenGL renderer
    LaunchedEffect(uiState.selectedPreset) {
        openGlRenderer?.updatePreset(uiState.selectedPreset)
    }

    LaunchedEffect(uiState.presetIntensity) {
        openGlRenderer?.presetIntensity = uiState.presetIntensity
        glSurfaceViewRef?.requestRender()
    }

    // Initialize CameraEngine
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraEngine.initialize()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraEngine.shutdown()
        }
    }

    if (!hasCameraPermission) {
        CameraPermissionRationale(
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = modifier
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. OpenGL Camera Viewport (Zero-lag viewfinder)
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    val renderer = OpenGLRenderer(ctx) { surfaceTexture ->
                        cameraEngine.startCamera(lifecycleOwner, surfaceTexture)
                    }
                    renderer.setGlSurfaceView(this)
                    renderer.presetIntensity = uiState.presetIntensity
                    renderer.updatePreset(uiState.selectedPreset)

                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

                    openGlRenderer = renderer
                    glSurfaceViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("camera_viewfinder")
        )

        // Shutter flash visual blink
        if (shutterAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = shutterAlpha.value))
            )
        }

        // 2. Top Bar (Controls)
        TopControlBar(
            flashMode = uiState.flashMode,
            isRawMode = uiState.isRawModeEnabled,
            onImportClick = {
                filePickerLauncher.launch(
                    arrayOf(
                        "*/*",
                        "text/xml",
                        "application/xml",
                        "application/octet-stream"
                    )
                )
            },
            onFlashClick = {
                val nextMode = cameraEngine.toggleFlash()
                viewModel.setFlashMode(nextMode)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            onRawToggle = {
                viewModel.toggleRawMode()
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // 3. Middle Viewfinder Area: Right-aligned Vertical Intensity Slider
        VerticalIntensitySlider(
            intensity = uiState.presetIntensity,
            onIntensityChange = { viewModel.setPresetIntensity(it) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .testTag("intensity_slider")
        )

        // Subtle Active Preset overlay tag at bottom of viewfinder
        ActivePresetOverlayTag(
            preset = uiState.selectedPreset,
            intensity = uiState.presetIntensity,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 200.dp)
        )

        // 4. Bottom Controls Area (Preset Carousel + Shutter Row)
        BottomControlArea(
            presets = uiState.presets,
            selectedPreset = uiState.selectedPreset,
            lastCapturedUri = uiState.lastCapturedUri,
            isCapturing = uiState.isCapturing,
            onPresetSelect = { preset ->
                viewModel.selectPreset(preset)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            onShutterClick = {
                if (!uiState.isCapturing) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        shutterAlpha.snapTo(0.85f)
                        shutterAlpha.animateTo(0f, tween(180))
                    }
                    viewModel.onCaptureStart()
                    cameraEngine.capturePhoto(
                        preset = uiState.selectedPreset,
                        presetIntensity = uiState.presetIntensity,
                        saveRaw = uiState.isRawModeEnabled,
                        onSuccess = { uri ->
                            viewModel.onCaptureSuccess(uri)
                        },
                        onError = { error ->
                            viewModel.onCaptureError(error.localizedMessage ?: "Unknown error")
                        }
                    )
                }
            },
            onFlipCameraClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                cameraEngine.flipCamera(lifecycleOwner)
                viewModel.setLensFacing(cameraEngine.lensFacing)
            },
            onGalleryClick = {
                uiState.lastCapturedUri?.let { uri ->
                    previewPhotoUri = uri
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )

        // Status Snackbar / Notification
        uiState.statusMessage?.let { msg ->
            LaunchedEffect(msg) {
                delay(2800)
                viewModel.clearStatusMessage()
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 20.dp, end = 20.dp),
                containerColor = Color(0xDD1C1C1E),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = msg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Photo Preview Dialog
        previewPhotoUri?.let { uri ->
            PhotoPreviewDialog(
                uri = uri,
                presetName = uiState.selectedPreset.name,
                onDismiss = { previewPhotoUri = null },
                onShare = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share LiveLight Photo"))
                }
            )
        }
    }
}

/**
 * Top control bar with Preset Import, Flash cycle, and RAW toggle.
 */
@Composable
private fun TopControlBar(
    flashMode: Int,
    isRawMode: Boolean,
    onImportClick: () -> Unit,
    onFlashClick: () -> Unit,
    onRawToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                )
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Import .xml/.xmp Preset Button
        Surface(
            onClick = onImportClick,
            shape = RoundedCornerShape(20.dp),
            color = Color(0x66000000),
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.testTag("import_preset_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FileOpen,
                    contentDescription = "Import Lightroom Preset",
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Import Preset",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Right controls: Flash & RAW Mode
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // RAW / JPEG badge button
            Surface(
                onClick = onRawToggle,
                shape = RoundedCornerShape(16.dp),
                color = if (isRawMode) Color(0xFFFFA726) else Color(0x66000000),
                border = BorderStroke(1.dp, if (isRawMode) Color(0xFFFFD54F) else Color(0x33FFFFFF)),
                modifier = Modifier.testTag("raw_mode_toggle_button")
            ) {
                Text(
                    text = if (isRawMode) "RAW+JPG" else "JPEG",
                    color = if (isRawMode) Color.Black else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            // Flash Mode Button
            IconButton(
                onClick = onFlashClick,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0x66000000), CircleShape)
                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                    .testTag("flash_toggle_button")
            ) {
                val icon = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                    else -> Icons.Default.FlashOff
                }
                val tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) Color(0xFFFFEB3B) else Color.White
                Icon(
                    imageVector = icon,
                    contentDescription = "Toggle Flash",
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Right-aligned vertical slider to control "Preset Intensity/Opacity" (0% to 100%).
 */
@Composable
private fun VerticalIntensitySlider(
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackHeight = 180.dp
    val trackWidth = 42.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Percentage Badge
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0x99000000),
            border = BorderStroke(0.8.dp, Color(0x44FFFFFF)),
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(
                text = "${(intensity * 100).roundToInt()}%",
                color = Color(0xFFFFCA28),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Tactile Drag Track
        BoxWithConstraints(
            modifier = Modifier
                .height(trackHeight)
                .width(trackWidth)
                .clip(RoundedCornerShape(21.dp))
                .background(Color(0x77111113))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(21.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        val y = change.position.y
                        val newIntensity = (1.0f - (y / size.height.toFloat())).coerceIn(0f, 1f)
                        onIntensityChange(newIntensity)
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            val filledHeight = trackHeight * intensity

            // Gradient fill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(filledHeight)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFB74D), Color(0xFFFF7043))
                        )
                    )
            )

            // Slider Icon
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Preset Opacity",
                tint = if (intensity > 0.3f) Color.Black else Color.White,
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .size(18.dp)
            )
        }
    }
}

/**
 * Subtle overlay tag at the bottom showing active preset name.
 */
@Composable
private fun ActivePresetOverlayTag(
    preset: PresetData,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0x88000000),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(preset.accentColor), CircleShape)
            )
            Text(
                text = "${preset.name} (${(intensity * 100).roundToInt()}%)",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
            Text(
                text = if (preset.isBuiltIn) ".xmp" else preset.fileName.substringAfterLast(".", "xml"),
                color = Color(0xAAFFFFFF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Bottom Bar containing Preset Carousel and Shutter control row.
 */
@Composable
private fun BottomControlArea(
    presets: List<PresetData>,
    selectedPreset: PresetData,
    lastCapturedUri: Uri?,
    isCapturing: Boolean,
    onPresetSelect: (PresetData) -> Unit,
    onShutterClick: () -> Unit,
    onFlipCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                )
            )
            .padding(top = 10.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Preset Carousel (LazyRow)
        PresetCarousel(
            presets = presets,
            selectedPreset = selectedPreset,
            onPresetSelect = onPresetSelect,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Shutter Row
        ShutterControlRow(
            lastCapturedUri = lastCapturedUri,
            isCapturing = isCapturing,
            onShutterClick = onShutterClick,
            onFlipCameraClick = onFlipCameraClick,
            onGalleryClick = onGalleryClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )
    }
}

/**
 * Horizontal Preset Carousel showing thumbnail cards for imported presets.
 */
@Composable
private fun PresetCarousel(
    presets: List<PresetData>,
    selectedPreset: PresetData,
    onPresetSelect: (PresetData) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.testTag("preset_carousel")
    ) {
        items(presets, key = { it.id }) { preset ->
            val isSelected = preset.id == selectedPreset.id

            PresetThumbnailCard(
                preset = preset,
                isSelected = isSelected,
                onClick = { onPresetSelect(preset) },
                modifier = Modifier.testTag("preset_item_${preset.id}")
            )
        }
    }
}

/**
 * Individual preset thumbnail card in the carousel.
 */
@Composable
private fun PresetThumbnailCard(
    preset: PresetData,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) Color(0xFFFFB74D) else Color(0x33FFFFFF)
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x44222224)),
        border = BorderStroke(borderWidth, borderColor),
        modifier = modifier
            .width(84.dp)
            .height(96.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Preset visual preview swatches
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(preset.accentColor),
                                Color(preset.accentColor).copy(alpha = 0.4f)
                            )
                        )
                    )
                    .border(1.dp, Color(0x44FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = preset.name.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Preset Name
            Text(
                text = preset.name,
                color = if (isSelected) Color(0xFFFFB74D) else Color.White,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            // Format badge (.xml or .xmp)
            Text(
                text = if (preset.isBuiltIn) "XMP" else preset.fileName.substringAfterLast(".", "XML").uppercase(),
                color = if (isSelected) Color(0xFFFFB74D) else Color(0x88FFFFFF),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Shutter Control Row: Gallery Thumbnail (Left), Large Shutter Button (Center), Flip Camera (Right).
 */
@Composable
private fun ShutterControlRow(
    lastCapturedUri: Uri?,
    isCapturing: Boolean,
    onShutterClick: () -> Unit,
    onFlipCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Recent Photo Gallery Thumbnail
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x44222224))
                .border(1.5.dp, Color(0x44FFFFFF), RoundedCornerShape(14.dp))
                .clickable(enabled = lastCapturedUri != null, onClick = onGalleryClick)
                .testTag("gallery_thumbnail_button"),
            contentAlignment = Alignment.Center
        ) {
            if (lastCapturedUri != null) {
                AsyncImage(
                    model = lastCapturedUri,
                    contentDescription = "Recent Captured Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "No Photo Yet",
                    tint = Color(0x66FFFFFF),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Center: Large White Circular Shutter Button
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(if (isCapturing) Color(0xFFFFB74D) else Color.White)
                .clickable(enabled = !isCapturing, onClick = onShutterClick)
                .testTag("shutter_button"),
            contentAlignment = Alignment.Center
        ) {
            if (isCapturing) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                )
            }
        }

        // Right: Camera Flip/Switch Icon Button
        IconButton(
            onClick = onFlipCameraClick,
            modifier = Modifier
                .size(54.dp)
                .background(Color(0x44222224), CircleShape)
                .border(1.5.dp, Color(0x44FFFFFF), CircleShape)
                .testTag("flip_camera_button")
        ) {
            Icon(
                imageVector = Icons.Default.Cameraswitch,
                contentDescription = "Flip Camera",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * Camera Permission Request UI.
 */
@Composable
private fun CameraPermissionRationale(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F1012))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color(0x22FFA726), CircleShape)
                    .border(1.5.dp, Color(0xFFFFB74D), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = "LiveLight Cam",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Real-time Adobe Lightroom .xml/.xmp preset camera.\nCamera permission is required to view live frames and capture color-graded photos.",
                color = Color(0xBBFFFFFF),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA726)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = "Grant Camera Access",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

/**
 * Fullscreen photo preview dialog when tapping the recent thumbnail.
 */
@Composable
private fun PhotoPreviewDialog(
    uri: Uri,
    presetName: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = uri,
                contentDescription = "Captured Photo Preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // Top bar of dialog
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0x77000000), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Preview",
                        tint = Color.White
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x77000000)
                ) {
                    Text(
                        text = "Preset: $presetName",
                        color = Color(0xFFFFCA28),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0x77000000), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Photo",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
