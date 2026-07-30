package com.example.carwalkaround

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest edge, in pixels, that a review thumbnail is decoded at. */
private const val THUMBNAIL_MAX_EDGE = 1024

/**
 * Phase 1 review/retake screen: every detail label with its captured photo, or a
 * "not captured" placeholder. Retaking sends the user back to that one wizard
 * step rather than restarting the whole run.
 *
 * @param onRetake hands a label back to [DetailShotViewModel.retake]; the wizard
 *        returns here as soon as that single shot is re-taken.
 * @param onDone the session is finished with — used to return to the home screen.
 */
@Composable
fun DetailReviewScreen(
    session: DetailSession,
    onRetake: (DetailLabel) -> Unit,
    onDone: () -> Unit
) {
    val missing = session.missingLabels

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Detail shots review",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "${session.shots.size} of ${DetailLabel.ORDERED.size} captured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (missing.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Still missing: " + missing.joinToString { it.displayName },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE67E22)
                    )
                }
            }
        }

        // Driven off ORDERED, not off session.shots, so skipped labels stay
        // visible as gaps. Listing only what was captured would quietly hide
        // exactly the thing the user came here to check.
        items(DetailLabel.ORDERED) { label ->
            DetailShotCard(
                label = label,
                shot = session.shotFor(label),
                onRetake = { onRetake(label) }
            )
        }

        item {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (missing.isEmpty()) "Done" else "Finish anyway")
            }
        }
    }
}

@Composable
private fun DetailShotCard(
    label: DetailLabel,
    shot: DetailShot?,
    onRetake: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetake) {
                Text(if (shot == null) "Capture" else "Retake")
            }
        }

        if (shot == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Not captured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        val thumbnail = rememberThumbnail(shot.mediaPath)

        if (thumbnail == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = label.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

/**
 * Decodes [path] down to a thumbnail off the main thread.
 *
 * Full-resolution capture output is easily 12MP, which is ~48MB once expanded to
 * ARGB_8888 — decoding eight of those at full size to fill a scrolling list is a
 * dependable OutOfMemoryError. Keying on the path is safe here because a retake
 * always writes a *new* timestamped file (see
 * [DetailShotViewModel.outputFileFor]), so a replaced photo cannot reuse a
 * cached key.
 */
@Composable
private fun rememberThumbnail(path: String): Bitmap? {
    return produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, THUMBNAIL_MAX_EDGE) }
    }.value
}

/**
 * Two-pass decode: the first pass reads only the JPEG header for its dimensions
 * (inJustDecodeBounds allocates no pixels), the second decodes at a power-of-two
 * reduction just large enough to cover [maxEdge].
 */
private fun decodeSampled(path: String, maxEdge: Int): Bitmap? {
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