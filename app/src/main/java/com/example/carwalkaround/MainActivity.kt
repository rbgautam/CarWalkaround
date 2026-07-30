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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
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
 *   2. Once granted, [VinCaptureScreen] establishes the [VehicleTag]. Nothing
 *      else is reachable until it does, because every capture downstream is
 *      filed under that tag and there is nowhere to put an untagged photo.
 *   3. Then the [Screen.Home] chooser.
 *   4. Phase 1 — [DetailShotWizardScreen] -> [DetailReviewScreen].
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

/**
 * Destinations in the capture flow.
 *
 * Note there is no untagged variant of [Home] or of either capture flow: the
 * tag is carried by the app state that gates them, so "which vehicle" is never
 * a question a downstream screen has to ask.
 */
private sealed interface Screen {
    /** Entry point — photograph the VIN and tag everything that follows. */
    data object VinCapture : Screen

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

    var screen by remember { mutableStateOf<Screen>(Screen.VinCapture) }

    // The active vehicle. Null only before the first VIN photo; every screen
    // past the VIN step reads it, which is why it is hoisted here rather than
    // threaded through as a navigation argument on each destination.
    var vehicleTag by remember { mutableStateOf<VehicleTag?>(null) }

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

    // Combining the two conditions makes "past the VIN step but untagged"
    // unrepresentable, rather than something the destinations below have to
    // defend against individually.
    val tag = vehicleTag
    if (tag == null || screen == Screen.VinCapture) {
        VinCaptureScreen(
            onTagged = { newTag ->
                vehicleTag = newTag
                screen = Screen.Home
            }
        )
        return
    }

    when (val current = screen) {
        Screen.VinCapture -> Unit // handled above

        Screen.Home -> HomeScreen(
            vehicleTag = tag,
            onStartDetailShots = {
                detailViewModel.startNewSession(tag)
                screen = Screen.DetailWizard
            },
            onStartOrbit = { screen = Screen.OrbitCapture },
            onChangeVehicle = {
                // Clearing the tag is what sends the user back to the VIN step;
                // the guard above does the routing. Leaving the previous
                // vehicle's files on disk is intentional — they are a finished
                // capture log under their own folder, not scratch data.
                vehicleTag = null
                screen = Screen.VinCapture
            }
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
            vehicleTag = tag,
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
    vehicleTag: VehicleTag,
    onStartDetailShots: () -> Unit,
    onStartOrbit: () -> Unit,
    onChangeVehicle: () -> Unit
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
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )

        // The tag is offered for change only here. Mid-flow it is display-only:
        // swapping vehicles with half a session captured would leave shots
        // filed under a car they are not of.
        VehicleTagHeader(tag = vehicleTag, onChangeVehicle = onChangeVehicle)

        Spacer(modifier = Modifier.height(24.dp))

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
