package com.salon.nailtryon

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PaletteColors = listOf(
    Color(0xFF8D0014), // Classic Deep Red
    Color(0xFFC0445C), // Mauve
    Color(0xFFE1B9B4), // Nude Pink
    Color(0xFFF3E5E2), // Pale Pearl
    Color(0xFF2E1A47), // Deep Plum
    Color(0xFF003366), // Navy Blue
    Color(0xFF1B4D3E), // Forest Green
    Color(0xFF4A4A4A), // Charcoal
    Color(0xFFB87333), // Copper/Bronze
    Color(0xFFE5E4E2), // Platinum
)

private const val DETECT_MAX_SIDE = 1024
private const val LANDMARK_MASK_MAX_SIDE = 512

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NailTryOnScreen() {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            )
        },
    ) { innerPadding ->
        NailTryOnContent(
            modifier = Modifier.padding(innerPadding),
            cameraPermissionGranted = cameraPermission.status.isGranted,
            onRequestCameraPermission = { cameraPermission.launchPermissionRequest() },
        )
    }
}

@Composable
private fun NailTryOnContent(
    modifier: Modifier = Modifier,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val landmarker = remember {
        HandLandmarkerHelper(context.applicationContext)
    }
    val segmenter = remember {
        NailSegmentationHelper(context.applicationContext)
    }

    DisposableEffect(Unit) {
        onDispose {
            landmarker.close()
            segmenter.close()
        }
    }

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var landmarks by remember { mutableStateOf<List<Pair<Float, Float>>?>(null) }
    var processing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var selectedColor by remember { mutableStateOf(PaletteColors.first()) }
    var selectedDesign by remember { mutableStateOf(NailDesign.SOLID) }
    var nailOpacity by remember { mutableFloatStateOf(0.78f) }
    var preferTfliteMask by remember { mutableStateOf(segmenter.isReady) }
    var lastMaskSource by remember { mutableStateOf(NailMaskSource.None) }
    
    // New states for single nail isolation
    var fullMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isolatedMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isolateSingleNailMode by remember { mutableStateOf(false) }

    LaunchedEffect(sourceBitmap, landmarks, selectedColor, selectedDesign, nailOpacity, preferTfliteMask, isolatedMaskBitmap, isolateSingleNailMode) {
        val src = sourceBitmap ?: run {
            processedBitmap = null
            fullMaskBitmap = null
            isolatedMaskBitmap = null
            lastMaskSource = NailMaskSource.None
            return@LaunchedEffect
        }
        val lm = landmarks
        if (lm == null || lm.size < 21) {
            processedBitmap = src
            lastMaskSource = NailMaskSource.None
            return@LaunchedEffect
        }

        processing = true
        statusMessage = null
        try {
            val previous = processedBitmap
            val (result, maskSrc, newFullMask) = withContext(Dispatchers.Default) {
                val maskFull = if (preferTfliteMask && segmenter.isReady) {
                    segmenter.buildMaskForBitmap(src)
                } else {
                    null
                }
                
                val maskSrc = if (maskFull != null) {
                    NailMaskSource.SegmentationModel
                } else {
                    NailMaskSource.Landmarks
                }

                // If we are in isolated mode and have an isolated mask, use it.
                // Otherwise use the full mask (either from TFLite or Landmarks).
                val activeMask = if (isolateSingleNailMode && isolatedMaskBitmap != null) {
                    isolatedMaskBitmap!!
                } else {
                    maskFull ?: run {
                        val maxSide = maxOf(src.width, src.height)
                        val scale = if (maxSide > LANDMARK_MASK_MAX_SIDE) {
                            LANDMARK_MASK_MAX_SIDE.toFloat() / maxSide
                        } else {
                            1f
                        }
                        val mw = (src.width * scale).toInt().coerceAtLeast(1)
                        val mh = (src.height * scale).toInt().coerceAtLeast(1)
                        val small = LandmarkNailMask.buildSoftMask(mw, mh, lm)
                        if (scale >= 0.999f) {
                            small
                        } else {
                            Bitmap.createScaledBitmap(small, src.width, src.height, true).also {
                                if (it !== small) small.recycle()
                            }
                        }
                    }
                }

                val tinted = blendNailPolish(
                    src,
                    activeMask,
                    selectedColor.toPolishArgb(),
                    nailOpacity,
                )
                val designed = applyDesignToBitmap(
                    tinted,
                    selectedDesign,
                    selectedColor.toPolishArgb(),
                    nailOpacity,
                )
                if (tinted !== designed && tinted !== src) tinted.recycle()
                Triple(designed, maskSrc, maskFull)
            }
            lastMaskSource = maskSrc
            processedBitmap = result
            fullMaskBitmap = newFullMask
            
            if (previous != null && previous !== src && previous !== result) {
                previous.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusMessage = e.message
            processedBitmap = src
        } finally {
            processing = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            processing = true
            statusMessage = null
            val full = withContext(Dispatchers.IO) {
                decodeBitmapMaxSide(context, uri, maxSide = 2048)
            }
            if (full == null) {
                statusMessage = context.getString(R.string.error_load_image)
                processing = false
                return@launch
            }
            val forDetect = if (maxOf(full.width, full.height) > DETECT_MAX_SIDE) {
                full.scaleToMaxSide(DETECT_MAX_SIDE)
            } else {
                full
            }
            val result = withContext(Dispatchers.Default) {
                landmarker.detect(forDetect)
            }
            if (forDetect !== full) forDetect.recycle()
            val lm = extractPrimaryHandLandmarks(result)
            sourceBitmap = full
            landmarks = lm
            if (lm == null) {
                statusMessage = context.getString(R.string.error_no_hand)
                processedBitmap = full
            }
            processing = false
        }
    }

    val captureUri = remember {
        val file = File(context.cacheDir, "nail_capture.jpg")
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (!success) return@rememberLauncherForActivityResult
        scope.launch {
            processing = true
            statusMessage = null
            val full = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(captureUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
            if (full == null) {
                statusMessage = context.getString(R.string.error_load_image)
                processing = false
                return@launch
            }
            val forDetect = if (maxOf(full.width, full.height) > DETECT_MAX_SIDE) {
                full.scaleToMaxSide(DETECT_MAX_SIDE)
            } else {
                full
            }
            val result = withContext(Dispatchers.Default) {
                landmarker.detect(forDetect)
            }
            if (forDetect !== full) forDetect.recycle()
            val lm = extractPrimaryHandLandmarks(result)
            sourceBitmap = full
            landmarks = lm
            if (lm == null) {
                statusMessage = context.getString(R.string.error_no_hand)
                processedBitmap = full
            }
            processing = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            when {
                processedBitmap != null -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = processedBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(processedBitmap, isolateSingleNailMode, fullMaskBitmap) {
                                    detectTapGestures { offset ->
                                        if (isolateSingleNailMode) {
                                            val maskToUse = fullMaskBitmap
                                            if (maskToUse != null) {
                                                val point = NailSelector.translateCoordinates(
                                                    offset.x, offset.y,
                                                    constraints.maxWidth.toFloat(),
                                                    constraints.maxHeight.toFloat(),
                                                    maskToUse.width,
                                                    maskToUse.height
                                                )
                                                if (point != null) {
                                                    val isolated = NailSelector.isolateSingleNail(
                                                        maskToUse, point.x, point.y
                                                    )
                                                    isolatedMaskBitmap = isolated
                                                }
                                            }
                                        }
                                    }
                                },
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.empty_state_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            if (processing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.pick_photo))
                }

                IconButton(
                    onClick = {
                        if (!cameraPermissionGranted) {
                            onRequestCameraPermission()
                        } else {
                            takePictureLauncher.launch(captureUri)
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        Icons.Default.AddAPhoto,
                        contentDescription = stringResource(R.string.take_photo),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }

                if (sourceBitmap != null) {
                    IconButton(
                        onClick = {
                            sourceBitmap = null
                            processedBitmap = null
                            landmarks = null
                            statusMessage = null
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.clear_photo))
                    }
                }
            }
        }

        ControlsPanel(
            selectedColor = selectedColor,
            onColorSelected = { selectedColor = it },
            selectedDesign = selectedDesign,
            onDesignSelected = { selectedDesign = it },
            opacity = nailOpacity,
            onOpacityChange = { nailOpacity = it },
            tfliteAvailable = segmenter.isReady,
            preferTfliteMask = preferTfliteMask,
            onPreferTfliteMaskChange = { preferTfliteMask = it },
            maskSource = lastMaskSource,
            isolateSingleNailMode = isolateSingleNailMode,
            onIsolateSingleNailModeChange = { 
                isolateSingleNailMode = it
                if (!it) isolatedMaskBitmap = null
            },
            canClearSelection = isolatedMaskBitmap != null,
            onClearSelection = { isolatedMaskBitmap = null }
        )
    }
}

private fun decodeBitmapMaxSide(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } ?: return null
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / sample > maxSide) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsPanel(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    selectedDesign: NailDesign,
    onDesignSelected: (NailDesign) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    tfliteAvailable: Boolean,
    preferTfliteMask: Boolean,
    onPreferTfliteMaskChange: (Boolean) -> Unit,
    maskSource: NailMaskSource,
    isolateSingleNailMode: Boolean,
    onIsolateSingleNailModeChange: (Boolean) -> Unit,
    canClearSelection: Boolean,
    onClearSelection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val maskLabel = when (maskSource) {
            NailMaskSource.SegmentationModel -> stringResource(R.string.mask_source_tflite)
            NailMaskSource.Landmarks -> stringResource(R.string.mask_source_landmarks)
            NailMaskSource.None -> stringResource(R.string.mask_source_none)
        }
        Text(
            text = maskLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (tfliteAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.use_tflite_mask), style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = preferTfliteMask,
                    onCheckedChange = onPreferTfliteMaskChange,
                )
            }
            
            if (preferTfliteMask) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.select_single_nail), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canClearSelection) {
                            Text(
                                text = stringResource(R.string.clear_selection),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onClearSelection() }
                                    .padding(end = 12.dp)
                            )
                        }
                        Switch(
                            checked = isolateSingleNailMode,
                            onCheckedChange = onIsolateSingleNailModeChange,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Text(
                stringResource(R.string.landmark_mask_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(stringResource(R.string.colors), style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PaletteColors.forEach { c ->
                val selected = c == selectedColor
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.4f)
                            },
                            shape = CircleShape,
                        )
                        .clickable { onColorSelected(c) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.designs), style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NailDesign.entries.forEach { design ->
                FilterChip(
                    selected = design == selectedDesign,
                    onClick = { onDesignSelected(design) },
                    label = {
                        Text(
                            design.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.opacity), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = 0.35f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
