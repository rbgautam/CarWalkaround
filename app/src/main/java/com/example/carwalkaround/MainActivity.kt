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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Single-activity host. Compose handles all navigation via simple state:
 *   1. Gate on the CAMERA runtime permission (README calls this out as omitted
 *      from the capture "sketch" — it belongs at the app entry point).
 *   2. Once granted, show the [Screen.Home] chooser.
 *   3. Phase 1 — [DetailShotWizardScreen] -> [DetailReviewScreen].
 *      Phase 2 — [OrbitCaptureScreen] -> [ReviewScreen].
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

/** Destinations in the capture flow. */
private sealed interface Screen {
    data object Home : Screen

    /** Phase 1: one-photo-per-label detail wizard. */
    data object DetailWizard : Screen
    data class DetailReview(val session: DetailSession) : Screen

    /** Phase 2: continuous orbit video with checkpoint taps. */
    data object OrbitCapture : Screen
    data class OrbitReview(val session: OrbitSession) : Screen
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

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Hoisted to the host, not owned by the wizard screen: a retake navigates
    // away to review and back again, and a screen-scoped ViewModel would be
    // torn down and take the session's captured shots with it.
    val detailViewModel: DetailShotViewModel = viewModel()

    val permissionLauncher = rememberCameraPermissionLauncher { granted ->
        hasCameraPermission = granted
    }

    if (!hasCameraPermission) {
        PermissionGate(onRequest = { permissionLauncher() })
        return
    }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            onStartDetailShots = {
                detailViewModel.startNewSession()
                screen = Screen.DetailWizard
            },
            onStartOrbit = { screen = Screen.OrbitCapture }
        )

        Screen.DetailWizard -> DetailShotWizardScreen(
            viewModel = detailViewModel,
            onWizardFinished = { session -> screen = Screen.DetailReview(session) }
        )

        is Screen.DetailReview -> DetailReviewScreen(
            session = current.session,
            onRetake = { label ->
                // The ViewModel decides which step is open; the wizard reads
                // that back, so routing is just "show the wizard again".
                detailViewModel.retake(label)
                screen = Screen.DetailWizard
            },
            onDone = { screen = Screen.Home }
        )

        Screen.OrbitCapture -> OrbitCaptureScreen(
            onSessionFinished = { session -> screen = Screen.OrbitReview(session) }
        )

        is Screen.OrbitReview -> ReviewScreen(
            session = current.session,
            onStartOver = { screen = Screen.Home }
        )
    }
}

@Composable
private fun HomeScreen(
    onStartDetailShots: () -> Unit,
    onStartOrbit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Vehicle walkaround",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Capture the exterior in two passes.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )

        Button(
            onClick = onStartDetailShots,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Detail shots  •  ${DetailLabel.ORDERED.size} guided photos")
        }
        Text(
            text = "Roof, mirrors, engine, VIN, tire info, decals, trunk — one photo each.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        Button(
            onClick = onStartOrbit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Orbit walkaround  •  video")
        }
        Text(
            text = "One continuous video; tap through the ${OrbitLabel.ORDERED.size} " +
                "angles as you walk around the car.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
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
