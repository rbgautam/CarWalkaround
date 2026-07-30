package com.example.carwalkaround

import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Phase 1 guided detail-shot wizard: one label per step, one photo each, label
 * assigned by whichever step was open (plan §4 — no classification).
 *
 * @param onWizardFinished fires when there is nothing left to shoot, either
 *        because every step is done or because the user finished early. The
 *        session it hands over may have missing labels; review reports them.
 */
@Composable
fun DetailShotWizardScreen(
    viewModel: DetailShotViewModel,
    onWizardFinished: (DetailSession) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Null only before startNewSession has supplied a vehicle tag; the host
    // always calls it before routing here, so this is a guard, not a state the
    // user can see.
    val uiState = viewModel.uiState.collectAsState().value ?: return

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // Guards the window between shutter press and the JPEG landing on disk. The
    // capture callback is asynchronous, so without this a double-tap queues two
    // captures for the same label and the second silently orphans the first.
    var isCapturing by remember { mutableStateOf(false) }

    val currentLabel = uiState.currentLabel

    // currentLabel goes null the moment the wizard has nothing left to shoot.
    // Handing off from a LaunchedEffect (rather than inline in the shutter
    // callback) keeps the transition in one place for all three exits: last
    // step captured, last step skipped, and "Finish early".
    LaunchedEffect(currentLabel) {
        if (currentLabel == null) onWizardFinished(uiState.session)
    }
    if (currentLabel == null) return

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                startPhotoCamera(
                    context = ctx,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    onImageCaptureReady = { ic -> imageCapture = ic },
                    onError = { message -> cameraError = message }
                )
                previewView
            }
        )

        DetailFramingOverlay(modifier = Modifier.fillMaxSize())

        // --- Top: step counter + label + framing instruction ---
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Which car these shots are being filed under, visible while they
            // are taken rather than only afterwards in review.
            VehicleTagHeader(tag = uiState.vehicleTag)

            Spacer(modifier = Modifier.height(12.dp))

            StepStrip(
                allLabels = DetailLabel.ORDERED,
                capturedLabels = uiState.session.capturedLabels,
                currentLabel = currentLabel
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Step ${uiState.stepNumber} of ${uiState.totalSteps}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = currentLabel.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = currentLabel.instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                    // Shooting a closed hood looks fine on a 6" preview and is
                    // only obvious once it reaches review, so the prerequisite
                    // gets its own line rather than being buried in the prose.
                    if (currentLabel.needsPreparation) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠  Open it first — this shot is useless closed.",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFF1C40F),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // --- Bottom: shutter + navigation ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            (cameraError ?: uiState.errorMessage)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE74C3C),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (uiState.session.shotFor(currentLabel) != null) {
                Text(
                    text = "Already captured — shooting again replaces it.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            ShutterButton(
                enabled = imageCapture != null && !isCapturing,
                isCapturing = isCapturing,
                onClick = {
                    val capture = imageCapture ?: return@ShutterButton
                    val capturedAt = System.currentTimeMillis()
                    // Written under the tagged vehicle's folder, so the photo is
                    // attributed by its location as well as by the in-memory
                    // session — the latter does not survive a cold start.
                    val outputFile = VehicleStorage.detailShotFile(
                        context = context,
                        tagId = uiState.vehicleTag.tagId,
                        label = currentLabel,
                        capturedAt = capturedAt
                    )
                    isCapturing = true
                    takePhoto(
                        context = context,
                        imageCapture = capture,
                        outputFile = outputFile,
                        onSaved = {
                            isCapturing = false
                            viewModel.onShotCaptured(
                                label = currentLabel,
                                mediaPath = outputFile.absolutePath,
                                capturedAt = capturedAt
                            )
                        },
                        onError = { message ->
                            isCapturing = false
                            viewModel.onCaptureFailed(message)
                        }
                    )
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(
                    onClick = { viewModel.goToPreviousStep() },
                    enabled = !uiState.isRetaking &&
                        DetailLabel.ORDERED.indexOf(currentLabel) > 0
                ) {
                    Text("Back", color = Color.White)
                }
                TextButton(onClick = { viewModel.skipCurrentStep() }) {
                    Text(
                        text = if (uiState.isRetaking) "Cancel retake" else "Skip",
                        color = Color.White
                    )
                }
                TextButton(onClick = { viewModel.finishWizard() }) {
                    Text("Review", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, isCapturing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .background(
                color = if (enabled) Color.White else Color.Gray.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isCapturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = Color.Black,
                strokeWidth = 3.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(
                        color = if (enabled) Color(0xFF2ECC71) else Color.DarkGray,
                        shape = CircleShape
                    )
            )
        }
    }
}

/** Dots for the 8 detail steps: green captured, yellow current, grey pending. */
@Composable
private fun StepStrip(
    allLabels: List<DetailLabel>,
    capturedLabels: Set<DetailLabel>,
    currentLabel: DetailLabel
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        allLabels.forEach { label ->
            val color = when {
                label == currentLabel -> Color(0xFFF1C40F)
                label in capturedLabels -> Color(0xFF2ECC71)
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

/**
 * A single centred reticle. Deliberately generic rather than per-label: these
 * eight shots have wildly different subjects (a roof panel vs a DOT code), so a
 * shaped silhouette would mislead on most steps. The written instruction does
 * the real guidance work here — the reticle just marks centre-frame.
 */
@Composable
private fun DetailFramingOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val boxWidth = size.width * 0.72f
        val boxHeight = size.height * 0.34f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.35f),
            topLeft = Offset((size.width - boxWidth) / 2, (size.height - boxHeight) / 2),
            size = Size(boxWidth, boxHeight),
            cornerRadius = CornerRadius(24f, 24f),
            style = Stroke(width = 4f)
        )
    }
}

// Camera binding and shutter live in PhotoCapture.kt — shared with
// VinCaptureScreen so the two cannot drift on capture mode or output handling.