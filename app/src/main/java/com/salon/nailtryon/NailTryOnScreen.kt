package com.salon.nailtryon

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix as AndroidMatrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
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
import androidx.compose.ui.graphics.toArgb
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

    NailTryOnContent(
        cameraPermissionGranted = cameraPermission.status.isGranted,
        onRequestCameraPermission = { cameraPermission.launchPermissionRequest() },
    )
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

    var selectedColor by remember { mutableStateOf(DefaultBrandColors.first().color) }
    var selectedDesign by remember { mutableStateOf(NailDesign.SOLID) }
    var selectedShape by remember { mutableStateOf(NailShape.NATURAL) }
    var nailOpacity by remember { mutableFloatStateOf(0.78f) }
    var preferTfliteMask by remember { mutableStateOf(segmenter.isReady) }
    var lastMaskSource by remember { mutableStateOf(NailMaskSource.None) }
    
    // Custom Colors state
    var customColors by remember { mutableStateOf(listOf<BrandColor>()) }
    var colorPickerActive by remember { mutableStateOf(false) }
    var colorToSave by remember { mutableStateOf<Color?>(null) }

    // New states for single nail isolation
    var fullMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isolatedMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isolateSingleNailMode by remember { mutableStateOf(false) }

    LaunchedEffect(sourceBitmap, landmarks, selectedColor, selectedDesign, selectedShape, nailOpacity, preferTfliteMask, isolatedMaskBitmap, isolateSingleNailMode) {
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
                // Step 1: Always build a NATURAL mask first for the base color
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

                // Determine the natural nail mask (the "Detailer")
                val naturalMask = if (isolateSingleNailMode && isolatedMaskBitmap != null) {
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

                val tipMask = if (selectedDesign == NailDesign.FRENCH) {
                    LandmarkNailMask.buildFrenchTipMask(src.width, src.height, lm, selectedShape)
                } else {
                    null
                }

                // Apply polish to the natural nail bed
                val designed = blendNailPolish(
                    src,
                    naturalMask,
                    selectedColor.toPolishArgb(),
                    nailOpacity,
                    selectedDesign,
                    tipMask
                )

                // Step 2: Draw the Extension (The "Surveyor + Detailer" Matrix)
                if (selectedShape != NailShape.NATURAL) {
                    val fingerTips = listOf(4, 8, 12, 16, 20)
                    val fingerDips = listOf(3, 7, 11, 15, 19)
                    
                    val renderer = NailShapeRenderer(context)
                    val canvas = Canvas(designed)
                    val hexColor = selectedColor.toHexString()
                    val resId = when(selectedShape) {
                        NailShape.COFFIN -> R.drawable.coffin_vector
                        NailShape.STILETTO -> R.drawable.stiletto_vector
                        NailShape.SQUARE -> R.drawable.square_vector
                        else -> 0
                    }
                    
                    if (resId != 0) {
                        for (idx in fingerTips.indices) {
                            val tipIdx = fingerTips[idx]
                            val dipIdx = fingerDips[idx]
                            
                            val tip = lm[tipIdx]
                            val dip = lm[dipIdx]
                            
                            // Surveyor: Precise rotation angle
                            val angle = MaskAnalyzer.calculateAngle(dip, tip)
                            
                            // Detailer: Isolate this specific nail from the NATURAL mask
                            val mx = (tip.first * naturalMask.width).toInt().coerceIn(0, naturalMask.width - 1)
                            val my = (tip.second * naturalMask.height).toInt().coerceIn(0, naturalMask.height - 1)
                            
                            val isolatedNail = NailSelector.isolateSingleNail(naturalMask, mx, my)
                            val geometry = MaskAnalyzer.analyzeNailMask(isolatedNail, angle)
                            
                            if (geometry != null) {
                                // For the thumb (idx 0), we might want a different length multiplier
                                val lengthMult = if (idx == 0) 1.2f else 1.45f
                                
                                val designToDraw = if (selectedDesign == NailDesign.FRENCH) NailDesign.SOLID else selectedDesign
                                renderer.drawExtension(
                                    canvas = canvas,
                                    geometry = geometry,
                                    shapeDrawableId = resId,
                                    brandHexColor = hexColor,
                                    lengthMultiplier = lengthMult,
                                    widthMultiplier = 1.25f, // Stretch to cover lateral folds
                                    design = designToDraw
                                )
                            }
                            if (isolatedNail !== naturalMask) isolatedNail.recycle()
                        }
                    }
                }
                
                tipMask?.recycle()
                
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
                                .pointerInput(processedBitmap, isolateSingleNailMode, fullMaskBitmap, colorPickerActive) {
                                    detectTapGestures { offset ->
                                        val point = NailSelector.translateCoordinates(
                                            offset.x, offset.y,
                                            constraints.maxWidth.toFloat(),
                                            constraints.maxHeight.toFloat(),
                                            processedBitmap!!.width,
                                            processedBitmap!!.height
                                        )

                                        if (colorPickerActive) {
                                            if (point != null) {
                                                // Pick from source bitmap for original color
                                                val src = sourceBitmap
                                                if (src != null) {
                                                    // Ensure coordinates are within source bitmap if sizes differ
                                                    val sx = (point.x.toFloat() / processedBitmap!!.width * src.width).toInt().coerceIn(0, src.width - 1)
                                                    val sy = (point.y.toFloat() / processedBitmap!!.height * src.height).toInt().coerceIn(0, src.height - 1)
                                                    val pixel = src.getPixel(sx, sy)
                                                    colorToSave = Color(pixel)
                                                    colorPickerActive = false
                                                }
                                            }
                                        } else if (isolateSingleNailMode) {
                                            val maskToUse = fullMaskBitmap
                                            if (maskToUse != null && point != null) {
                                                // Mask coordinates match processedBitmap coordinates usually, 
                                                // but let's re-translate if mask size differs
                                                val mp = if (maskToUse.width == processedBitmap!!.width) point else {
                                                    val mx = (point.x.toFloat() / processedBitmap!!.width * maskToUse.width).toInt()
                                                    val my = (point.y.toFloat() / processedBitmap!!.height * maskToUse.height).toInt()
                                                    android.graphics.Point(mx, my)
                                                }
                                                val isolated = NailSelector.isolateSingleNail(
                                                    maskToUse, mp.x, mp.y
                                                )
                                                isolatedMaskBitmap = isolated
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

            if (colorPickerActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp)
                        .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.extract_color_hint),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
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

                    processedBitmap?.let { bitmap ->
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val success = saveBitmapToGallery(context, bitmap)
                                    val msg = if (success) {
                                        context.getString(R.string.image_saved)
                                    } else {
                                        context.getString(R.string.error_save_image)
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        ) {
                            Icon(
                                Icons.Default.SaveAlt,
                                contentDescription = stringResource(R.string.save_to_device),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
        ) {
            ControlsPanel(
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it },
                customColors = customColors,
                onAddCustomColorClicked = { colorPickerActive = true },
                selectedDesign = selectedDesign,
                onDesignSelected = { selectedDesign = it },
                selectedShape = selectedShape,
                onShapeSelected = { selectedShape = it },
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

    if (colorToSave != null) {
        CustomColorSaveDialog(
            color = colorToSave!!,
            onDismiss = { colorToSave = null },
            onSave = { name, brand ->
                customColors = customColors + BrandColor(brand, name, colorToSave!!, isCustom = true)
                selectedColor = colorToSave!!
                colorToSave = null
            }
        )
    }
}

@Composable
private fun CustomColorSaveDialog(
    color: Color,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_custom_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(color)
                        .align(Alignment.CenterHorizontally)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.color_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text(stringResource(R.string.brand_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, brand) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.save_color))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private suspend fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
    val filename = "NailTryOn_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NailTryOn")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@withContext false

    try {
        resolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
        } ?: return@withContext false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
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
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null

    // Handle EXIF rotation
    return try {
        val exif = resolver.openInputStream(uri)?.use { stream ->
            androidx.exifinterface.media.ExifInterface(stream)
        }
        val orientation = exif?.getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED
        )
        val matrix = AndroidMatrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    } catch (e: Exception) {
        bitmap
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsPanel(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    customColors: List<BrandColor>,
    onAddCustomColorClicked: () -> Unit,
    selectedDesign: NailDesign,
    onDesignSelected: (NailDesign) -> Unit,
    selectedShape: NailShape,
    onShapeSelected: (NailShape) -> Unit,
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
            .verticalScroll(rememberScrollState())
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Add Custom Color Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onAddCustomColorClicked() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Colorize,
                    contentDescription = stringResource(R.string.add_custom_color),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Custom Colors
            customColors.forEach { bc ->
                ColorSwatch(
                    color = bc.color,
                    isSelected = bc.color == selectedColor,
                    onClick = { onColorSelected(bc.color) }
                )
            }

            // Default Colors
            DefaultBrandColors.forEach { bc ->
                ColorSwatch(
                    color = bc.color,
                    isSelected = bc.color == selectedColor,
                    onClick = { onColorSelected(bc.color) }
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
                        val label = when(design) {
                            NailDesign.SOLID -> "Solid"
                            NailDesign.FRENCH -> "French"
                            NailDesign.GLITTER -> "Glitter"
                            NailDesign.MATTE -> "Matte"
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.shapes), style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NailShape.entries.forEach { shape ->
                FilterChip(
                    selected = shape == selectedShape,
                    onClick = { onShapeSelected(shape) },
                    label = {
                        val label = when(shape) {
                            NailShape.NATURAL -> "Natural"
                            NailShape.COFFIN -> "Coffin"
                            NailShape.STILETTO -> "Stiletto"
                            NailShape.SQUARE -> "Square"
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium)
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

@Composable
private fun ColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.4f)
                },
                shape = CircleShape,
            )
            .clickable { onClick() },
    )
}

private fun Color.toHexString(): String = String.format("#%06X", (this.toArgb() and 0xFFFFFF))
