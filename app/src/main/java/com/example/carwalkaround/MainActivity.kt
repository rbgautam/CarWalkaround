package com.example.carwalkaround

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Single-activity host. Compose handles all navigation via simple state:
 *   1. Gate on the CAMERA runtime permission (README calls this out as omitted
 *      from the capture "sketch" — it belongs at the app entry point).
 *   2. Once granted, show [OrbitCaptureScreen].
 *   3. When a session finishes, swap to [ReviewScreen] with the result.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CarWalkaroundTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // The finished session (null until the user completes an orbit). Presence of
    // a value is what flips the UI from capture -> review.
    var finishedSession by remember { mutableStateOf<OrbitSession?>(null) }

    val permissionLauncher = rememberCameraPermissionLauncher { granted ->
        hasCameraPermission = granted
    }

    when {
        !hasCameraPermission -> PermissionGate(
            onRequest = { permissionLauncher() }
        )

        finishedSession == null -> OrbitCaptureScreen(
            onSessionFinished = { session -> finishedSession = session }
        )

        else -> ReviewScreen(
            session = finishedSession!!,
            onStartOver = { finishedSession = null }
        )
    }
}

/** Wraps the ActivityResult permission contract in a simple invoke-to-request lambda. */
@Composable
private fun rememberCameraPermissionLauncher(
    onResult: (Boolean) -> Unit
): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onResult
    )
    return { launcher.launch(Manifest.permission.CAMERA) }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Camera access is needed to record the walkaround video.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Grant camera permission")
        }
    }
}
