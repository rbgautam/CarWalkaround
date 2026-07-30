package com.example.carwalkaround

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Still-photo plumbing shared by the VIN screen and the detail wizard. Both bind
 * the same Preview + ImageCapture pair and decode the same kind of thumbnail, so
 * this lives in one place rather than being copied per screen — the two would
 * otherwise drift on capture mode and sampling, which is exactly the pair of
 * settings that decides whether a VIN plate stays legible.
 */

/** Longest edge, in pixels, that a review thumbnail is decoded at. */
const val THUMBNAIL_MAX_EDGE = 1024

/**
 * Binds Preview + ImageCapture for the lifetime of a screen. Callers bind once
 * and keep the handle; rebinding blanks the preview and re-runs camera init.
 *
 * @param onError camera configuration failures are surfaced to the UI rather
 *        than thrown out of a main-executor callback, which would take the
 *        process down. Hardware varies, so binding is best-effort.
 */
fun startPhotoCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onError: (String) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // VIN plates, DOT codes and window-sticker text all have to stay
            // legible in review, so this trades shutter latency for quality.
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            onImageCaptureReady(imageCapture)
        } catch (e: Exception) {
            onError("Camera unavailable: ${e.message ?: e::class.java.simpleName}")
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * @param onSaved fires once the JPEG is fully written — only then is
 *        [outputFile] decodable by a review screen.
 * Both callbacks arrive on the main executor.
 */
fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    outputFile: File,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved()
            }

            override fun onError(exception: ImageCaptureException) {
                onError(
                    exception.message
                        ?: "Photo failed to save (error ${exception.imageCaptureError})."
                )
            }
        }
    )
}

/**
 * Decodes [path] down to a thumbnail off the main thread, returning null until
 * the decode finishes.
 *
 * Full-resolution capture output is easily 12MP, which is ~48MB once expanded to
 * ARGB_8888 — decoding several of those at full size to fill a scrolling list is
 * a dependable OutOfMemoryError. Keying on the path is safe because a retake
 * always writes a *new* timestamped file (see [VehicleStorage.detailShotFile]),
 * so a replaced photo cannot reuse a cached key.
 */
@Composable
fun rememberThumbnail(path: String, maxEdge: Int = THUMBNAIL_MAX_EDGE): Bitmap? =
    produceState<Bitmap?>(initialValue = null, key1 = path, key2 = maxEdge) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, maxEdge) }
    }.value

/**
 * Two-pass decode: the first pass reads only the JPEG header for its dimensions
 * (inJustDecodeBounds allocates no pixels), the second decodes at a power-of-two
 * reduction just large enough to cover [maxEdge].
 */
fun decodeSampled(path: String, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= maxEdge ||
        bounds.outHeight / (sampleSize * 2) >= maxEdge
    ) {
        sampleSize *= 2
    }

    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}