package com.salon.nailtryon

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

private val PaletteColors = listOf(
    Color(0xFFE91E63),
    Color(0xFFFF4081),
    Color(0xFFFF9800),
    Color(0xFFFFEB3B),
    Color(0xFF8BC34A),
    Color(0xFF00BCD4),
    Color(0xFF3F51B5),
    Color(0xFF673AB7),
    Color(0xFF212121),
    Color(0xFFFCE4EC),
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NailTryOnScreen() {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

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
        when {
            cameraPermission.status.isGranted -> {
                NailTryOnContent(modifier = Modifier.padding(innerPadding))
            }

            else -> {
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(24.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_rationale),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            }
        }
    }
}

@Composable
private fun NailTryOnContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    var selectedColor by remember { mutableStateOf(PaletteColors.first()) }
    var selectedDesign by remember { mutableStateOf(NailDesign.SOLID) }
    var nailOpacity by remember { mutableFloatStateOf(0.78f) }

    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frozenLandmarks by remember { mutableStateOf<List<Pair<Float, Float>>?>(null) }

    val landmarker = remember {
        HandLandmarkerHelper(context.applicationContext)
    }

    DisposableEffect(Unit) {
        onDispose { landmarker.close() }
    }

    var overlayState by remember { mutableStateOf<OverlayState?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            } ?: return@launch
            val result = withContext(Dispatchers.Default) {
                landmarker.detect(bmp)
            }
            val lm = extractPrimaryHandLandmarks(result)
            frozenBitmap = bmp
            frozenLandmarks = lm
            overlayState = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            if (frozenBitmap != null && frozenLandmarks != null) {
                StaticTryOnView(
                    bitmap = frozenBitmap!!,
                    landmarks = frozenLandmarks!!,
                    color = selectedColor,
                    design = selectedDesign,
                    opacity = nailOpacity,
                    mirrorHorizontal = false,
                )
            } else {
                CameraTryOnLayer(
                    lifecycleOwner = lifecycleOwner,
                    landmarker = landmarker,
                    cameraExecutor = cameraExecutor,
                    mainExecutor = mainExecutor,
                    onOverlayUpdate = { overlayState = it },
                    onImageCaptureReady = { imageCapture = it },
                    modifier = Modifier.fillMaxSize(),
                )

                overlayState?.let { state ->
                    OverlayCanvas(
                        overlayState = state,
                        color = selectedColor,
                        design = selectedDesign,
                        opacity = nailOpacity,
                        mirrorHorizontal = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (frozenBitmap == null) {
                    IconButton(
                        onClick = {
                            val capture = imageCapture ?: return@IconButton
                            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(System.currentTimeMillis())
                            val relativePath = "${Environment.DIRECTORY_DCIM}/NailTryOn"
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "NAIL_$name.jpg")
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                                }
                            }
                            val output = ImageCapture.OutputFileOptions.Builder(
                                context.contentResolver,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues,
                            ).build()

                            capture.takePicture(
                                output,
                                mainExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        val uri = outputFileResults.savedUri ?: return
                                        scope.launch {
                                            val bmp = withContext(Dispatchers.IO) {
                                                context.contentResolver.openInputStream(uri)
                                                    ?.use(BitmapFactory::decodeStream)
                                            } ?: return@launch
                                            val result = withContext(Dispatchers.Default) {
                                                landmarker.detect(bmp)
                                            }
                                            frozenBitmap = bmp
                                            frozenLandmarks = extractPrimaryHandLandmarks(result)
                                            overlayState = null
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                    }
                                },
                            )
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.capture_photo),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.pick_photo))
                }

                if (frozenBitmap != null) {
                    IconButton(
                        onClick = {
                            frozenBitmap = null
                            frozenLandmarks = null
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
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
        )
    }
}

private data class OverlayState(
    val landmarks: List<Pair<Float, Float>>,
    val imageWidth: Int,
    val imageHeight: Int,
)

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraTryOnLayer(
    lifecycleOwner: LifecycleOwner,
    landmarker: HandLandmarkerHelper,
    cameraExecutor: ExecutorService,
    mainExecutor: java.util.concurrent.Executor,
    onOverlayUpdate: (OverlayState?) -> Unit,
    onImageCaptureReady: (ImageCapture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val runnable = Runnable {
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val bitmap = imageProxy.toBitmapRgba()
                    val result = landmarker.detect(bitmap)
                    val lm = extractPrimaryHandLandmarks(result)
                    val w = bitmap.width
                    val h = bitmap.height
                    mainExecutor.execute {
                        if (lm != null) {
                            onOverlayUpdate(
                                OverlayState(
                                    landmarks = lm,
                                    imageWidth = w,
                                    imageHeight = h,
                                ),
                            )
                        } else {
                            onOverlayUpdate(null)
                        }
                    }
                } catch (_: Throwable) {
                    mainExecutor.execute { onOverlayUpdate(null) }
                } finally {
                    imageProxy.close()
                }
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            onImageCaptureReady(capture)

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    analysis,
                    capture,
                )
            } catch (_: Exception) {
                onOverlayUpdate(null)
            }
        }
        providerFuture.addListener(runnable, ContextCompat.getMainExecutor(context))

        onDispose {
            providerFuture.addListener({
                try {
                    providerFuture.get().unbindAll()
                } catch (_: Exception) {
                }
            }, mainExecutor)
            onOverlayUpdate(null)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@Composable
private fun OverlayCanvas(
    overlayState: OverlayState,
    color: Color,
    design: NailDesign,
    opacity: Float,
    mirrorHorizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cw = size.width
        val ch = size.height
        val iw = overlayState.imageWidth.toFloat()
        val ih = overlayState.imageHeight.toFloat()

        val scale = min(cw / iw, ch / ih)
        val ox = (cw - iw * scale) / 2f
        val oy = (ch - ih * scale) / 2f

        val mappedLandmarks = overlayState.landmarks.map { (nx, ny) ->
            val xNorm = if (mirrorHorizontal) 1f - nx else nx
            Pair(ox + xNorm * iw * scale, oy + ny * ih * scale)
        }

        NailOverlayPainter.drawNailsPixels(
            scope = this,
            landmarksPx = mappedLandmarks,
            baseColor = color,
            opacity = opacity,
            design = design,
        )
    }
}

@Composable
private fun StaticTryOnView(
    bitmap: Bitmap,
    landmarks: List<Pair<Float, Float>>,
    color: Color,
    design: NailDesign,
    opacity: Float,
    mirrorHorizontal: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cw = size.width
            val ch = size.height
            val iw = bitmap.width.toFloat()
            val ih = bitmap.height.toFloat()
            val scale = min(cw / iw, ch / ih)
            val ox = (cw - iw * scale) / 2f
            val oy = (ch - ih * scale) / 2f

            val mappedLandmarks = landmarks.map { (nx, ny) ->
                val xNorm = if (mirrorHorizontal) 1f - nx else nx
                Pair(ox + xNorm * iw * scale, oy + ny * ih * scale)
            }

            NailOverlayPainter.drawNailsPixels(
                scope = this,
                landmarksPx = mappedLandmarks,
                baseColor = color,
                opacity = opacity,
                design = design,
            )
        }
    }
}

@Composable
private fun ControlsPanel(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    selectedDesign: NailDesign,
    onDesignSelected: (NailDesign) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
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
