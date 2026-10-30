package com.gascanner.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.appContainer
import com.gascanner.app.data.settings.AddTarget
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.scan.CARD_ASPECT
import com.gascanner.app.scan.Candidate
import com.gascanner.app.scan.GUIDE_WIDTH_FRACTION
import com.gascanner.app.scan.MULTI_MAX_SIDE
import com.gascanner.app.scan.OCR_MAX_SIDE
import com.gascanner.app.scan.ScanMode
import com.gascanner.app.scan.UPLOAD_MAX_SIDE
import com.gascanner.app.scan.loadCapture
import com.gascanner.app.scan.toCapture
import com.gascanner.app.scan.toUprightBitmap
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onOpenCard: (slug: String, editionUuid: String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: ScanViewModel = viewModel { ScanViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val captureMode by vm.captureMode.collectAsStateWithLifecycle()
    val scanMode by vm.scanMode.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val autoAdd by vm.autoAdd.collectAsStateWithLifecycle()
    val needsKey by vm.needsApiKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val maxSide = when {
        captureMode == CaptureMode.MULTI -> MULTI_MAX_SIDE
        scanMode == ScanMode.ON_DEVICE -> OCR_MAX_SIDE
        else -> UPLOAD_MAX_SIDE
    }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(live.lockCount) { if (live.lockCount > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (captureMode == CaptureMode.LIVE) vm.setCaptureMode(CaptureMode.PHOTO)
            val capture = withContext(Dispatchers.IO) { runCatching { loadCapture(context, uri, maxSide) }.getOrNull() }
            if (capture != null) vm.identify(capture) else vm.fail(s.imageOpenFailed)
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }
    val worker = remember { Executors.newSingleThreadExecutor() }
    val analyzerThread = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(vm) {
        analysis.setAnalyzer(analyzerThread) { proxy ->
            try {
                if (vm.wantsFrame()) vm.processFrame(proxy.toUprightBitmap())
            } catch (_: Exception) {
            } finally {
                proxy.close()
            }
        }
        onDispose {
            analysis.clearAnalyzer()
            worker.shutdown()
            analyzerThread.shutdown()
        }
    }

    fun capture() {
        imageCapture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = try {
                    runCatching { image.toCapture(maxSide) }.getOrNull()
                } finally {
                    image.close()
                }
                scope.launch { if (result != null) vm.identify(result) else vm.fail(s.photoFailed) }
            }

            override fun onError(exception: ImageCaptureException) {
                scope.launch { vm.fail(exception.message ?: s.photoFailed) }
            }
        })
    }

    var torch by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = s.scanTitle,
        snackbarHostState = snackbar,
        actions = {
            if (granted && hasFlash) {
                IconButton(onClick = { torch = !torch }) {
                    Icon(
                        if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = if (torch) s.flashlightOff else s.flashlightOn,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (needsKey && captureMode != CaptureMode.LIVE) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.claudeKeyNeeded)
                        TextButton(onClick = onOpenSettings) { Text(s.openSettings) }
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val modes = listOf(CaptureMode.LIVE to s.modeLive, CaptureMode.PHOTO to s.modePhoto, CaptureMode.MULTI to s.modeMulti)
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = captureMode == mode,
                        onClick = { vm.setCaptureMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (granted) {
                    CameraPreview(imageCapture, analysis, torch, onHasFlash = { hasFlash = it }, modifier = Modifier.fillMaxSize())
                    if (captureMode != CaptureMode.MULTI) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(GUIDE_WIDTH_FRACTION)
                                .aspectRatio(CARD_ASPECT)
                                .border(
                                    2.dp,
                                    if (captureMode == CaptureMode.LIVE && live.hit != null && live.seeingText) MaterialTheme.colorScheme.primary
                                    else Color.White.copy(alpha = 0.8f),
                                    RoundedCornerShape(12.dp),
                                )
                        )
                    }
                    if (captureMode == CaptureMode.LIVE) {
                        LiveOverlay(
                            live = live,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            onOpen = { onOpenCard(it.card.slug, it.edition?.uuid) },
                            onAddAgain = vm::liveAddAgain,
                        )
                    } else if (captureMode == CaptureMode.MULTI && state is ScanUiState.Idle) {
                        Surface(
                            Modifier.align(Alignment.TopCenter).padding(12.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            shape = RoundedCornerShape(8.dp),
                        ) { Text(s.multiHint, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                } else {
                    CenteredMessage(s.cameraNeeded) {
                        TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text(s.allowCamera) }
                    }
                }
                if (state is ScanUiState.Working) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.size(8.dp))
                            Text(s.identifying, color = Color.White)
                        }
                    }
                }
            }

            TargetRow(
                target = target,
                onTarget = { vm.setTarget(it) },
                showAutoAdd = captureMode == CaptureMode.LIVE,
                autoAdd = autoAdd,
                onAutoAdd = { vm.setAutoAdd(it) },
            )

            when (val st = state) {
                is ScanUiState.Result -> ResultsPanel(
                    st,
                    onOpen = { onOpenCard(it.card.slug, it.edition?.uuid) },
                    onQuickAdd = { vm.quickAdd(it, st.guess.foil == true) },
                    onScanAgain = vm::reset,
                )
                is ScanUiState.Multi -> MultiPanel(
                    st,
                    onToggle = vm::toggleSelected,
                    onOpen = { onOpenCard(it.card.slug, it.edition?.uuid) },
                    onAdd = vm::addSelected,
                    onScanAgain = vm::reset,
                )
                is ScanUiState.Error -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(st.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::reset) { Text(s.tryAgain) }
                }
                else -> if (captureMode != CaptureMode.LIVE) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(
                            onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = state !is ScanUiState.Working,
                        ) { Icon(Icons.Default.PhotoLibrary, s.pickGallery) }
                        FilledIconButton(
                            onClick = { capture() },
                            enabled = granted && state !is ScanUiState.Working,
                            modifier = Modifier.size(72.dp),
                        ) { Icon(Icons.Default.CameraAlt, s.scanButton, Modifier.size(32.dp)) }
                        Spacer(Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(
    target: AddTarget,
    onTarget: (AddTarget) -> Unit,
    showAutoAdd: Boolean,
    autoAdd: Boolean,
    onAutoAdd: (Boolean) -> Unit,
) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(s.addTo, style = MaterialTheme.typography.labelLarge)
        listOf(AddTarget.COLLECTION to s.targetCollection, AddTarget.SELL to s.targetSell, AddTarget.BUY to s.targetBuy).forEach { (t, label) ->
            FilterChip(selected = target == t, onClick = { onTarget(t) }, label = { Text(label) })
        }
        if (showAutoAdd) {
            Spacer(Modifier.width(6.dp))
            Text(s.autoAdd, style = MaterialTheme.typography.labelLarge)
            Switch(checked = autoAdd, onCheckedChange = onAutoAdd)
        }
    }
}

@Composable
private fun LiveOverlay(
    live: LiveState,
    modifier: Modifier,
    onOpen: (Candidate) -> Unit,
    onAddAgain: () -> Unit,
) {
    val s = LocalStrings.current
    val hit = live.hit
    Surface(
        modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.7f),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        if (hit == null) {
            Text(s.liveHint, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.clickable { onOpen(hit) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CardThumb(hit.edition?.imageUrl, width = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(hit.card.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            hit.edition?.label,
                            if (hit.exact) s.exactMatch else if (hit.artMatched) s.artMatch else null,
                            if (live.justAdded) "✓ " + s.added("") else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!live.seeingText) Text(s.liveLooking, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
                FilledTonalIconButton(onClick = onAddAgain) { Icon(Icons.Default.Add, s.addAgain) }
            }
        }
    }
}

@Composable
private fun ResultsPanel(
    result: ScanUiState.Result,
    onOpen: (Candidate) -> Unit,
    onQuickAdd: (Candidate) -> Unit,
    onScanAgain: () -> Unit,
) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val g = result.guess
        SmallNote(
            s.read(
                g.name.ifBlank { "?" } +
                    listOfNotNull(g.setPrefix, g.collectorNumber).joinToString(" ").let { if (it.isBlank()) "" else " · $it" } +
                    if (g.foil == true) " · foil" else ""
            )
        )
        if (result.candidates.isEmpty()) Text(s.noMatch, Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            itemsIndexed(result.candidates, key = { _, it -> it.card.uuid + (it.edition?.uuid ?: "") }) { _, cand ->
                CandidateRow(cand, onOpen) {
                    FilledTonalIconButton(onClick = { onQuickAdd(cand) }) { Icon(Icons.Default.Add, s.add) }
                }
                HorizontalDivider()
            }
        }
        Button(onClick = onScanAgain, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(s.scanNext) }
    }
}

@Composable
private fun MultiPanel(
    result: ScanUiState.Multi,
    onToggle: (Int) -> Unit,
    onOpen: (Candidate) -> Unit,
    onAdd: () -> Unit,
    onScanAgain: () -> Unit,
) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        SmallNote(s.multiFound(result.candidates.size))
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            itemsIndexed(result.candidates, key = { i, it -> "$i-" + it.card.uuid }) { i, cand ->
                CandidateRow(cand, onOpen) {
                    Checkbox(checked = i in result.selected, onCheckedChange = { onToggle(i) })
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onScanAgain) { Text(s.tryAgain) }
            Button(onClick = onAdd, enabled = result.selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text(s.addSelected(result.selected.size))
            }
        }
    }
}

@Composable
private fun CandidateRow(cand: Candidate, onOpen: (Candidate) -> Unit, trailing: @Composable () -> Unit) {
    val s = LocalStrings.current
    Row(Modifier.fillMaxWidth().clickable { onOpen(cand) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CardThumb(cand.edition?.imageUrl, width = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(cand.card.name, fontWeight = if (cand.exact) FontWeight.SemiBold else FontWeight.Normal)
            SmallNote(
                listOfNotNull(
                    cand.edition?.label,
                    cand.edition?.set?.name,
                    if (cand.exact) s.exactMatch else if (cand.artMatched) s.artMatch else null,
                ).joinToString(" · ")
            )
        }
        trailing()
    }
}

@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    analysis: ImageAnalysis,
    torch: Boolean,
    onHasFlash: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    LaunchedEffect(camera, torch) {
        camera?.let { cam -> if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torch) }
    }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            // Wait for layout so the viewport (what the user sees) is known; captures are then
            // cropped to it, which lines the on-screen card guide up with the photo.
            previewView.post {
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .addUseCase(analysis)
                    .apply { previewView.viewPort?.let { setViewPort(it) } }
                    .build()
                val bound = runCatching {
                    provider?.unbindAll()
                    provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                }.getOrElse {
                    // Some older phones can't run all three at once: drop live analysis.
                    runCatching {
                        provider?.unbindAll()
                        provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    }.getOrNull()
                }
                camera = bound
                onHasFlash(bound?.cameraInfo?.hasFlashUnit() == true)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            camera = null
            provider?.unbindAll()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
