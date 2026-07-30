package com.example.carwalkaround

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Runs [FrameExtractor.extractCheckpointFrames] against the finished session and
 * shows one labeled still per checkpoint. Extraction is I/O + codec work, so it
 * runs off the main thread (FrameExtractor already switches to Dispatchers.IO);
 * we drive it from a [LaunchedEffect] tied to the session's video path.
 */
@Composable
fun ReviewScreen(
    session: OrbitSession,
    onStartOver: () -> Unit
) {
    val context = LocalContext.current
    var extracted by remember { mutableStateOf<OrbitSession?>(null) }

    LaunchedEffect(session.videoFilePath) {
        // Extracted stills land inside the tagged vehicle's folder, so a frame
        // is attributable to a car by its path alone.
        val outputDir = VehicleStorage.framesDir(
            context = context,
            tagId = session.vehicleTag.tagId,
            sessionId = session.sessionId
        )
        extracted = FrameExtractor.extractCheckpointFrames(session, outputDir)
    }

    val done = extracted
    if (done == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Text(
                text = "Extracting frames…",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Walkaround review",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                VehicleTagHeader(tag = done.vehicleTag)
            }
        }
        items(done.checkpoints) { checkpoint ->
            CheckpointCard(checkpoint)
        }
        item {
            Button(
                onClick = onStartOver,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Record another walkaround")
            }
        }
    }
}

@Composable
private fun CheckpointCard(checkpoint: Checkpoint) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${checkpoint.label.displayName}  •  ${checkpoint.timestampMs} ms",
            style = MaterialTheme.typography.titleMedium
        )

        val path = checkpoint.frameUri
        val bitmap = remember(path) {
            path?.let { BitmapFactory.decodeFile(it) }
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = checkpoint.label.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(top = 8.dp)
            )
        } else {
            Text(
                text = "No frame extracted for this checkpoint.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
