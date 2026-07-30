package com.example.carwalkaround

import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/**
 * The app's first screen: photograph the VIN, and everything captured afterwards
 * is tagged to it (see [VehicleTag]).
 *
 * This runs before the flow chooser rather than as one of the detail-wizard
 * steps, even though the wizard also has a VIN step. The two are not redundant:
 * this one establishes *which car* is being inspected and must therefore happen
 * before any file is written, since the tag decides where those files go. The
 * wizard's VIN step remains the inspection photo of the plate.
 *
 * @param onTagged fires once the user accepts the photo. The VIN string may be
 *        blank — the photo is the record, the typed VIN is a convenience.
 */
@Composable
fun VinCaptureScreen(onTagged: (VehicleTag) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The tag directory is claimed once per visit to this screen, so retaking
    // the photo reuses the same folder instead of leaving an orphan behind.
    val tagId = remember { VehicleStorage.newTagId(System.currentTimeMillis()) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Non-null once a photo is on disk, which is what flips this screen from
    // shooting to confirming. Holding the pair (path, time) rather than a
    // half-built VehicleTag keeps "no tag exists yet" unrepresentable.
    var pending by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var vinText by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf<VinScanResult?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    // Once the user types, OCR stops writing to the field. A scan that lands
    // late must never overwrite what a human deliberately entered — they are
    // looking at the same plate the camera was, and they win.
    var vinEditedByUser by remember { mutableStateOf(false) }

    // Keyed on the photo path, so a retake re-scans and a cancelled scan is
    // dropped rather than applied to the replacement photo.
    LaunchedEffect(pending?.first) {
        val path = pending?.first
        scan = null
        if (path == null) {
            isScanning = false
            return@LaunchedEffect
        }
        isScanning = true
        val result = VinOcr.scan(context, path)
        scan = result
        isScanning = false
        if (!vinEditedByUser && result.vin != null) vinText = result.vin
    }

    val capture = pending
    if (capture != null) {
        VinConfirmStep(
            photoPath = capture.first,
            vinText = vinText,
            scan = scan,
            isScanning = isScanning,
            onVinChanged = {
                vinText = normalizeVin(it)
                vinEditedByUser = true
            },
            onRetake = {
                // Drop the rejected photo; only the accepted one belongs in the
                // vehicle folder, which an upload job will later walk wholesale.
                runCatching { File(capture.first).delete() }
                pending = null
                vinText = ""
                vinEditedByUser = false
            },
            onAccept = {
                val tag = VehicleTag(
                    tagId = tagId,
                    vinPhotoPath = capture.first,
                    capturedAt = capture.second,
                    vin = vinText.takeIf { it.isNotBlank() }
                )
                VehicleStorage.writeSidecar(context, tag)
                onTagged(tag)
            }
        )
        return
    }

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

        VinFramingOverlay(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Start with the VIN",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Frame the VIN plate inside the box — driver door " +
                            "jamb, or through the base of the windshield.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Every photo and video after this is tagged to it.",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFF1C40F),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            cameraError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE74C3C),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            ShutterButton(
                enabled = imageCapture != null && !isCapturing,
                isCapturing = isCapturing,
                onClick = {
                    val ic = imageCapture ?: return@ShutterButton
                    val capturedAt = System.currentTimeMillis()
                    val outputFile = VehicleStorage.vinPhotoFile(context, tagId, capturedAt)
                    isCapturing = true
                    cameraError = null
                    takePhoto(
                        context = context,
                        imageCapture = ic,
                        outputFile = outputFile,
                        onSaved = {
                            isCapturing = false
                            pending = outputFile.absolutePath to capturedAt
                        },
                        onError = { message ->
                            isCapturing = false
                            cameraError = message
                        }
                    )
                }
            )
        }
    }
}

/**
 * Post-shutter step: ML Kit reads the plate and pre-fills the VIN, which the
 * user confirms, corrects, or types from scratch.
 *
 * The field is editable at every stage, including while the scan is still
 * running. OCR here is a labour-saver, not an authority — a VIN has no
 * redundancy for a wrong character to be caught by downstream, so the person
 * holding the phone has to stay able to overrule the model at any moment.
 */
@Composable
private fun VinConfirmStep(
    photoPath: String,
    vinText: String,
    scan: VinScanResult?,
    isScanning: Boolean,
    onVinChanged: (String) -> Unit,
    onRetake: () -> Unit,
    onAccept: () -> Unit
) {
    val thumbnail = rememberThumbnail(photoPath)
    val vinLooksOff = vinText.isNotBlank() && !looksLikeVin(vinText)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Is the VIN readable?",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail == null) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "Captured VIN plate",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ScanStatus(scan = scan, isScanning = isScanning)

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = vinText,
            onValueChange = onVinChanged,
            label = { Text(if (isScanning) "VIN (scanning…)" else "VIN") },
            supportingText = {
                Text(
                    if (vinLooksOff) {
                        "${vinText.length} of 17 characters — check it, but you " +
                            "can continue either way."
                    } else {
                        "Edit freely; typing overrides the scan. The photo is " +
                            "kept regardless."
                    }
                )
            },
            isError = vinLooksOff,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Use this vehicle")
        }
        TextButton(
            onClick = onRetake,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retake VIN photo")
        }
    }
}

/**
 * Reports what the scan found, in the terms that change what the user should do
 * next.
 *
 * The distinction that earns its keep here is CHECKSUM_VALID vs UNVERIFIED. A
 * VIN whose check digit agrees is very unlikely to be misread, so the user can
 * glance and move on; one that does not agree needs all 17 characters
 * proof-read. Collapsing both into "scanned" would spend the user's attention
 * evenly across cases that deserve wildly different amounts of it — and would
 * quietly train them to trust the unverified ones.
 */
@Composable
private fun ScanStatus(scan: VinScanResult?, isScanning: Boolean) {
    if (isScanning) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = "Reading the plate…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        return
    }

    val result = scan ?: return
    val (message, color) = when {
        result.error != null -> result.error to Color(0xFFE74C3C)

        result.confidence == VinConfidence.CHECKSUM_VALID ->
            "Scanned and the check digit matches — confirm it looks right." to
                Color(0xFF2ECC71)

        result.confidence == VinConfidence.UNVERIFIED ->
            "Scanned, but the check digit doesn't match. Compare every " +
                "character against the photo before continuing." to Color(0xFFE67E22)

        else ->
            "No VIN found in the photo. Type it from the plate, or retake the " +
                "shot closer." to Color(0xFFE67E22)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(10.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

/**
 * A wide, short reticle matched to the shape of a VIN plate, unlike the detail
 * wizard's generic centre box. A VIN is a single line of small characters, and
 * the framing error that ruins the shot is standing too far back — a box the
 * plate is meant to fill makes that error visible before the shutter.
 */
@Composable
private fun VinFramingOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val boxWidth = size.width * 0.86f
        val boxHeight = size.height * 0.13f
        drawRoundRect(
            color = Color(0xFFF1C40F).copy(alpha = 0.8f),
            topLeft = Offset((size.width - boxWidth) / 2, (size.height - boxHeight) / 2),
            size = Size(boxWidth, boxHeight),
            cornerRadius = CornerRadius(16f, 16f),
            style = Stroke(width = 5f)
        )
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

/**
 * Compact "everything here belongs to this vehicle" banner. Shown on the capture
 * and review screens so the tag is visible at the moment it is being applied,
 * not just at the moment it was created — the failure this prevents is
 * walkaround #2 being shot while #1's tag is still active.
 */
@Composable
fun VehicleTagHeader(
    tag: VehicleTag,
    modifier: Modifier = Modifier,
    onChangeVehicle: (() -> Unit)? = null
) {
    val thumbnail = rememberThumbnail(tag.vinPhotoPath, maxEdge = 256)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "VIN photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = "Tagged to",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = tag.displayVin,
                style = MaterialTheme.typography.titleSmall
            )
        }

        onChangeVehicle?.let {
            TextButton(onClick = it) { Text("Change") }
        }
    }
}