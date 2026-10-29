package com.gascanner.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.appContainer
import com.gascanner.app.scan.OCR_MAX_SIDE
import com.gascanner.app.scan.ScanMode
import com.gascanner.app.scan.UPLOAD_MAX_SIDE
import com.gascanner.app.scan.loadUploadJpeg
import com.gascanner.app.scan.toUploadJpeg
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@Composable
fun ScanScreen(
    onOpenCard: (slug: String, editionUuid: String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = appContainer()
    val vm: ScanViewModel = viewModel { ScanViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val needsKey by vm.needsApiKey.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val maxSide = if (mode == ScanMode.ON_DEVICE) OCR_MAX_SIDE else UPLOAD_MAX_SIDE
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { loadUploadJpeg(context, uri, maxSide) }.getOrNull() }
            if (bytes != null) vm.identify(bytes) else vm.fail("Could not open that image.")
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val worker = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }

    fun capture() {
        imageCapture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = try {
                    runCatching { image.toUploadJpeg(maxSide) }.getOrNull()
                } finally {
                    image.close()
                }
                scope.launch {
                    if (bytes != null) vm.identify(bytes) else vm.fail("Could not process the photo.")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                scope.launch { vm.fail(exception.message ?: "Camera error") }
            }
        })
    }

    ScreenScaffold(title = if (mode == ScanMode.ON_DEVICE) "Scan a card" else "Scan a card · Claude", snackbarHostState = snackbar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (needsKey) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Claude scanning needs an Anthropic API key. Add one, or switch to free on-device scanning.")
                        TextButton(onClick = onOpenSettings) { Text("Open settings") }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (granted) {
                    CameraPreview(imageCapture, Modifier.fillMaxSize())
                    // Card-shaped guide frame.
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(0.68f)
                            .aspectRatio(0.716f)
                            .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    )
                } else {
                    CenteredMessage("Camera permission is needed to scan. You can still pick a photo from the gallery.") {
                        TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                }
                if (state is ScanUiState.Working) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.size(8.dp))
                            Text("Identifying card…", color = Color.White)
                        }
                    }
                }
            }

            when (val s = state) {
                is ScanUiState.Result -> ResultsPanel(
                    s,
                    onOpen = { onOpenCard(it.card.slug, it.edition?.uuid) },
                    onQuickAdd = { vm.quickAdd(it, s.guess.foil == true) },
                    onScanAgain = vm::reset,
                )
                is ScanUiState.Error -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::reset) { Text("Try again") }
                }
                else -> Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = state !is ScanUiState.Working,
                    ) { Icon(Icons.Default.PhotoLibrary, "Pick from gallery") }
                    FilledIconButton(
                        onClick = { capture() },
                        enabled = granted && state !is ScanUiState.Working,
                        modifier = Modifier.size(72.dp),
                    ) { Icon(Icons.Default.CameraAlt, "Scan", Modifier.size(32.dp)) }
                    Spacer(Modifier.size(40.dp))
                }
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val g = result.guess
        Text(
            "Read: ${g.name.ifBlank { "?" }}" +
                listOfNotNull(g.setPrefix, g.collectorNumber).joinToString(" ").let { if (it.isBlank()) "" else " · $it" } +
                if (g.foil == true) " · foil" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.candidates.isEmpty()) {
            Text("No matching card in the database. Try again, or search by name.", Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(result.candidates, key = { it.card.uuid + (it.edition?.uuid ?: "") }) { cand ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(cand) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardThumb(cand.edition?.imageUrl, width = 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cand.card.name, fontWeight = if (cand.exact) FontWeight.SemiBold else FontWeight.Normal)
                        Text(
                            listOfNotNull(cand.edition?.label, cand.edition?.set?.name, if (cand.exact) "exact match" else null)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalIconButton(onClick = { onQuickAdd(cand) }) {
                        Icon(Icons.Default.Add, "Add to collection")
                    }
                }
                HorizontalDivider()
            }
        }
        Button(onClick = onScanAgain, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Scan next card") }
    }
}

@Composable
private fun CameraPreview(imageCapture: ImageCapture, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
