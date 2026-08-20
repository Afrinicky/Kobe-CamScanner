package com.kobe.camscanner.feature.camera

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.Grid3x3
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kobe.camscanner.R
import com.kobe.camscanner.camera.DocumentAnalyzer
import com.kobe.camscanner.core.permissions.rememberCameraPermissionState
import com.kobe.camscanner.core.ui.components.KobeChip
import com.kobe.camscanner.core.ui.components.KobeIconButton
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.EmptyState
import com.kobe.camscanner.core.ui.theme.KobeCanvasTheme
import com.kobe.camscanner.core.ui.theme.KobeMotion
import com.kobe.camscanner.core.ui.theme.KobePalette
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.domain.model.ScanMode
import com.kobe.camscanner.scanner.DocumentDetector
import com.kobe.camscanner.scanner.StabilityTracker
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The viewfinder.
 *
 * The screen is intentionally the darkest surface in the app and carries no chrome beyond what a
 * scan needs. Every control sits within thumb reach of the shutter, and the only thing above the
 * preview is the small row of toggles — SDS 3 asks for "open the application and begin scanning
 * immediately", and anything else on this screen works against that.
 */
@Composable
fun CameraScreen(
    onFinished: () -> Unit,
    onClose: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberCameraPermissionState()

    LaunchedEffect(Unit) {
        viewModel.startSessionIfNeeded()
        if (!permission.granted) permission.request()
    }

    KobeCanvasTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KobePalette.Viewfinder),
        ) {
            if (permission.granted) {
                ScannerViewfinder(
                    state = state,
                    viewModel = viewModel,
                    onFinished = onFinished,
                    onClose = onClose,
                )
            } else {
                CameraPermissionPrompt(
                    onGrant = permission.request,
                    onClose = onClose,
                    context = context,
                )
            }
        }
    }
}

@Composable
private fun ScannerViewfinder(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onFinished: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val extra = KobeTheme.extra

    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val tracker = remember { StabilityTracker() }
    val detector = remember { DocumentDetector() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT_AT_ONCE),
    ) { uris -> viewModel.importImages(uris) { onFinished() } }

    // Auto-capture: the tracker decides, the screen just obeys. Guarded by isProcessing so a slow
    // page cannot queue a second shot behind the first.
    val shouldAutoCapture = state.autoCaptureOn &&
        state.detection.shouldCapture &&
        !state.isProcessing

    LaunchedEffect(shouldAutoCapture) {
        if (shouldAutoCapture) {
            imageCapture?.let { capture ->
                takePicture(capture, executor, viewModel, state.detection.quad)
                tracker.reset()
            }
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Binding is expensive and tears down the preview surface, so it happens exactly once per
    // lifecycle owner. Torch and zoom are driven separately through the camera control below.
    LaunchedEffect(previewView, lifecycleOwner) {
        bindCamera(
            context = context,
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            executor = executor,
            tracker = tracker,
            detector = detector,
            onAnalysis = viewModel::onAnalysis,
            onBound = { capture, control ->
                imageCapture = capture
                cameraControl = control
            },
            onUnavailable = viewModel::onCaptureFailed,
        )
    }

    LaunchedEffect(state.torchOn, cameraControl) {
        cameraControl?.enableTorch(state.torchOn)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Pinch to zoom, the gesture every camera app has trained users to expect.
                    detectTransformGestures { _, _, zoom, _ ->
                        val next = (viewModel.uiState.value.zoomRatio * zoom)
                            .coerceIn(1f, CameraViewModel.MAX_ZOOM)
                        viewModel.setZoom(next)
                        cameraControl?.setZoomRatio(next)
                    }
                },
            factory = { previewView },
        )

        if (state.gridOn) {
            FramingGrid(modifier = Modifier.fillMaxSize())
        }

        DetectionOverlay(
            state = state.detection,
            sourceAspect = state.sourceAspect,
            modifier = Modifier.fillMaxSize(),
            accent = Color.White,
            detectColor = extra.detect,
        )

        // A brief white wash confirms the capture without the shutter sound most users mute.
        AnimatedVisibility(
            visible = state.shutterFlash,
            enter = fadeIn(KobeMotion.quick()),
            exit = fadeOut(KobeMotion.normal()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.55f)),
            )
        }

        TopControls(
            state = state,
            onClose = onClose,
            onToggleTorch = viewModel::toggleTorch,
            onToggleGrid = viewModel::toggleGrid,
            onToggleAuto = viewModel::toggleAutoCapture,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        CoachingLabel(
            phase = state.detection.phase,
            autoCaptureOn = state.autoCaptureOn,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 108.dp),
        )

        BottomControls(
            state = state,
            onModeChange = viewModel::setMode,
            onShutter = {
                imageCapture?.let { capture ->
                    takePicture(capture, executor, viewModel, state.detection.quad)
                    tracker.reset()
                }
            },
            onImport = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onFinish = onFinished,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TopControls(
    state: CameraUiState,
    onClose: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KobeIconButton(
            icon = Icons.Rounded.Close,
            contentDescription = "Close scanner",
            onClick = onClose,
            container = Color.White.copy(alpha = 0.12f),
            tint = Color.White,
        )
        Spacer(Modifier.weight(1f))
        KobeIconButton(
            icon = if (state.torchOn) Icons.Rounded.Bolt else Icons.Rounded.FlashOff,
            contentDescription = stringResource(R.string.cd_flash),
            onClick = onToggleTorch,
            container = if (state.torchOn) Color.White else Color.White.copy(alpha = 0.12f),
            tint = if (state.torchOn) Color.Black else Color.White,
        )
        KobeIconButton(
            icon = Icons.Rounded.Grid3x3,
            contentDescription = stringResource(R.string.cd_grid),
            onClick = onToggleGrid,
            container = if (state.gridOn) Color.White else Color.White.copy(alpha = 0.12f),
            tint = if (state.gridOn) Color.Black else Color.White,
        )
        AutoCaptureToggle(enabled = state.autoCaptureOn, onClick = onToggleAuto)
    }
}

/**
 * Auto-capture reads as a word rather than an icon. It changes what the shutter does, and a glyph
 * would not tell a first-time user that (SDS 8: "Automatic capture must be configurable").
 */
@Composable
private fun AutoCaptureToggle(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .semantics { contentDescription = if (enabled) "Automatic capture on" else "Automatic capture off" }
            .clip(KobeRadius.chip)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "AUTO",
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Color.Black else Color.White,
        )
    }
}

/** The one line of coaching text from SDS 8. */
@Composable
private fun CoachingLabel(
    phase: StabilityTracker.Phase,
    autoCaptureOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = when (phase) {
        StabilityTracker.Phase.SEARCHING -> "Point at a document"
        StabilityTracker.Phase.DETECTED ->
            if (autoCaptureOn) "Document detected" else "Document detected — tap to capture"
        StabilityTracker.Phase.HOLD_STEADY -> "Hold steady"
        StabilityTracker.Phase.CAPTURE -> "Capturing"
    }
    val detected = phase != StabilityTracker.Phase.SEARCHING

    Box(
        modifier = modifier
            .clip(KobeRadius.chip)
            .background(
                if (detected) KobePalette.DetectGreen.copy(alpha = 0.92f)
                else Color.Black.copy(alpha = 0.55f),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (detected) Color.Black else Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BottomControls(
    state: CameraUiState,
    onModeChange: (ScanMode) -> Unit,
    onShutter: () -> Unit,
    onImport: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                ),
            )
            .navigationBarsPadding()
            .padding(bottom = 20.dp, top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        ) {
            items(ScanMode.entries.toList()) { mode ->
                KobeChip(
                    label = mode.label,
                    selected = state.mode == mode,
                    onClick = { onModeChange(mode) },
                    accent = Color.White,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            KobeIconButton(
                icon = Icons.Rounded.PhotoLibrary,
                contentDescription = stringResource(R.string.cd_import),
                onClick = onImport,
                container = Color.White.copy(alpha = 0.14f),
                tint = Color.White,
                boxSize = 52.dp,
                shape = CircleShape,
            )

            ShutterButton(enabled = !state.isProcessing, onClick = onShutter)

            PageStack(
                count = state.capturedCount,
                thumbnail = state.lastThumbnail,
                onClick = onFinish,
            )
        }

        if (state.canFinish) {
            Spacer(Modifier.height(18.dp))
            KobePrimaryButton(
                text = "Review ${state.capturedCount} " + if (state.capturedCount == 1) "page" else "pages",
                icon = Icons.Rounded.Check,
                onClick = onFinish,
                modifier = Modifier.padding(horizontal = 28.dp).fillMaxWidth(),
            )
        }
    }
}

/** Classic camera shutter: a white disc inside a ring, scaling on press. */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = KobeMotion.snappySpring(),
        label = "shutterScale",
    )
    Box(
        modifier = Modifier
            .size(78.dp)
            .semantics { contentDescription = "Capture page" }
            .clickable(enabled = enabled) {
                pressed = true
                onClick()
                pressed = false
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        )
        Box(
            modifier = Modifier
                .scale(scale)
                .size(62.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f)),
        )
    }
}

/** The growing stack of captured pages, which doubles as the way into the review screen. */
@Composable
private fun PageStack(count: Int, thumbnail: File?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .semantics { contentDescription = "$count captured pages. Opens review." },
        contentAlignment = Alignment.Center,
    ) {
        if (count == 0) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(KobeRadius.tile)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), KobeRadius.tile),
            )
        } else {
            // Two offset cards behind the thumbnail read as depth without a shadow, which would be
            // invisible against the near-black viewfinder anyway.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .padding(start = 6.dp, top = 6.dp)
                    .clip(KobeRadius.tile)
                    .background(Color.White.copy(alpha = 0.30f)),
            )
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(KobeRadius.tile)
                    .border(2.dp, Color.White, KobeRadius.tile)
                    .clickable(onClick = onClick),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    onGrant: () -> Unit,
    onClose: () -> Unit,
    context: Context,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = "Camera access needed",
            message = stringResource(R.string.error_camera_unavailable),
            action = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KobePrimaryButton(text = "Allow camera", onClick = onGrant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Open app settings",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clickable {
                                context.startActivity(
                                    com.kobe.camscanner.core.permissions.KobePermissions
                                        .appSettingsIntent(context),
                                )
                            }
                            .padding(12.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Not now",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.clickable(onClick = onClose).padding(12.dp),
                    )
                }
            },
        )
    }
}

// ------------------------------------------------------------------ CameraX plumbing

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    executor: ExecutorService,
    tracker: StabilityTracker,
    detector: DocumentDetector,
    onAnalysis: (com.kobe.camscanner.camera.AnalysisResult) -> Unit,
    onBound: (ImageCapture, androidx.camera.core.CameraControl) -> Unit,
    onUnavailable: () -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        try {
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Capture at the highest quality the sensor offers; the pipeline downsamples once,
            // after the fact, rather than throwing away detail the enhancer could have used.
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()

            // Analysis runs on a small frame on purpose — see DocumentDetector.WORK_EDGE.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .apply {
                    setAnalyzer(
                        executor,
                        DocumentAnalyzer(detector, tracker, onAnalysis),
                    )
                }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
                analysis,
            )
            onBound(capture, camera.cameraControl)
        } catch (t: Throwable) {
            onUnavailable()
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun takePicture(
    capture: ImageCapture,
    executor: ExecutorService,
    viewModel: CameraViewModel,
    quad: com.kobe.camscanner.domain.model.Quad?,
) {
    val target: File = viewModel.nextCaptureTarget()
    target.parentFile?.mkdirs()
    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(target).build(),
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                viewModel.onCaptured(target, quad)
            }

            override fun onError(exception: ImageCaptureException) {
                viewModel.onCaptureFailed()
            }
        },
    )
}

/** 4:3 at roughly 640x480 — enough for edge detection, cheap enough for every frame. */
private const val ANALYSIS_WIDTH = 640
private const val ANALYSIS_HEIGHT = 480

/** Matches what the page editor can comfortably handle in one go. */
private const val MAX_IMPORT_AT_ONCE = 30
