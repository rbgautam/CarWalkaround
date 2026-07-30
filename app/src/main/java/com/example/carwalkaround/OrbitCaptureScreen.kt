package com.example.carwalkaround

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun OrbitCaptureScreen(
    onSessionFinished: (OrbitSession) -> Unit,
    viewModel: OrbitCaptureViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {

        // --- Camera preview (CameraX PreviewView wrapped for Compose) ---
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                startCamera(ctx, lifecycleOwner, previewView) { vc -> videoCapture = vc }
                previewView
            }
        )

        // --- Static framing guide: horizontal height band + car silhouette ---
        FramingOverlay(modifier = Modifier.fillMaxSize())

        // --- Top: pace hint banner ---
        PaceBanner(
            paceHint = uiState.paceHint,
            isRecording = uiState.isRecording,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        )

        // --- Bottom: checkpoint progress strip + tap button ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CheckpointStrip(
                allLabels = OrbitLabel.ORDERED,
                completedLabels = uiState.completedLabels,
                currentLabel = uiState.currentLabel
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.isRecording) {
                Button(onClick = {
                    val outputFile = File(
                        context.getExternalFilesDir(null),
                        "orbit_${System.currentTimeMillis()}.mp4"
                    )
                    startRecording(context, videoCapture, outputFile) {
                        viewModel.onRecordingStarted(outputFile.absolutePath)
                    }.let { activeRecording = it }
                }) {
                    Text("Start Orbit Recording")
                }
            } else {
                Text(
                    text = uiState.currentLabel?.displayName ?: "Done!",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                CheckpointTapButton(
                    enabled = !uiState.isComplete,
                    onTap = { viewModel.onCheckpointTapped(CheckpointSource.MANUAL_TAP) }
                )
                if (uiState.isComplete) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        activeRecording?.stop()
                        val finished = viewModel.onRecordingStopped()
                        finished?.let(onSessionFinished)
                    }) {
                        Text("Finish & Extract Frames")
                    }
                }
            }
        }
    }
}

/** Big circular tap target — the user hits this each time they reach a checkpoint. */
@Composable
private fun CheckpointTapButton(enabled: Boolean, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(
                color = if (enabled) Color(0xFF2ECC71) else Color.Gray,
                shape = CircleShape
            )
    ) {
        IconButtonSurrogate(enabled = enabled, onTap = onTap)
    }
}

// Kept as a plain clickable box rather than IconButton to avoid pulling in
// extra Material icon deps in this sketch.
@Composable
private fun IconButtonSurrogate(enabled: Boolean, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onTap, enabled = enabled) {
            Text(if (enabled) "TAP" else "✓", color = Color.White)
        }
    }
}

@Composable
private fun CheckpointStrip(
    allLabels: List<OrbitLabel>,
    completedLabels: List<OrbitLabel>,
    currentLabel: OrbitLabel?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        allLabels.forEach { label ->
            val color = when {
                label in completedLabels -> Color(0xFF2ECC71)
                label == currentLabel -> Color(0xFFF1C40F)
                else -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
private fun PaceBanner(paceHint: PaceHint, isRecording: Boolean, modifier: Modifier = Modifier) {
    if (!isRecording) return
    val (text, bg) = when (paceHint) {
        PaceHint.SPEED_UP -> "Speed up" to Color(0xFFE67E22)
        PaceHint.SLOW_DOWN -> "Slow down" to Color(0xFFE67E22)
        PaceHint.ON_PACE -> "Good pace" to Color(0xFF2ECC71)
    }
    Box(
        modifier = modifier
            .background(bg.copy(alpha = 0.85f), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Draws a fixed horizontal band (target camera height) and a faint car
 * silhouette rectangle, so the user frames the car consistently. This is a
 * *static* visual aid only — no sensor fusion in Phase 1/2.
 */
@Composable
private fun FramingOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val bandY = size.height * 0.45f
        val bandHeight = size.height * 0.12f

        // Height guide band
        drawRect(
            color = Color.White.copy(alpha = 0.15f),
            topLeft = Offset(0f, bandY),
            size = androidx.compose.ui.geometry.Size(size.width, bandHeight)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = Offset(0f, bandY + bandHeight / 2),
            end = Offset(size.width, bandY + bandHeight / 2),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )

        // Faint silhouette box centered in frame (swap per-segment in Phase 2
        // for front/side/rear-specific outlines)
        val silhouetteWidth = size.width * 0.7f
        val silhouetteHeight = size.height * 0.3f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.4f),
            topLeft = Offset(
                (size.width - silhouetteWidth) / 2,
                bandY - silhouetteHeight * 0.3f
            ),
            size = androidx.compose.ui.geometry.Size(silhouetteWidth, silhouetteHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f),
            style = Stroke(width = 4f)
        )
    }
}

// --- CameraX wiring ---

private fun startCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit
) {
    val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = androidx.camera.core.Preview.Builder().build().also {
            // CameraX 1.3.4 exposes this as a setter method (the assignable
            // `surfaceProvider` property arrives in 1.4.0+).
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(
                androidx.camera.video.QualitySelector.from(androidx.camera.video.Quality.FHD)
            )
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            videoCapture
        )
        onVideoCaptureReady(videoCapture)
    }, ContextCompat.getMainExecutor(context))
}

private fun startRecording(
    context: Context,
    videoCapture: VideoCapture<Recorder>?,
    outputFile: File,
    onStarted: () -> Unit
): Recording? {
    val vc = videoCapture ?: return null
    val outputOptions = FileOutputOptions.Builder(outputFile).build()

    return vc.output
        .prepareRecording(context, outputOptions)
        .apply {
            // Audio omitted for this sketch — enable withAudioEnabled() if you
            // want the user to be able to narrate defects while walking.
        }
        .start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> onStarted()
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        // surface error to UI / logging in a real build
                    }
                }
                else -> Unit
            }
        }
}
