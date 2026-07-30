# Orbit Capture Sketch — Wiring Notes

## Files
- `Checkpoint.kt` — data models (`OrbitLabel`, `Checkpoint`, `OrbitSession`)
- `OrbitCaptureViewModel.kt` — state machine: recording clock, pace hint, checkpoint logging
- `OrbitCaptureScreen.kt` — Compose UI: CameraX preview, framing overlay, checkpoint strip, tap button
- `FrameExtractor.kt` — post-recording: pulls a JPEG per checkpoint via `MediaMetadataRetriever`

## Flow
1. `OrbitCaptureScreen` starts the CameraX `VideoCapture` use case and, once
   `VideoRecordEvent.Start` fires, calls `viewModel.onRecordingStarted(path)`.
2. User taps the checkpoint button once per label as they walk around the car.
   Each tap calls `viewModel.onCheckpointTapped()`, which logs
   `(label, elapsedMs)` against the *elapsed recording time*, not wall-clock
   time — this keeps the timestamp valid regardless of any UI lag.
3. Once all 8 labels are checkpointed, the "Finish & Extract Frames" button
   stops the `Recording` and calls `viewModel.onRecordingStopped()`, which
   returns the completed `OrbitSession`.
4. Hand that `OrbitSession` off to `FrameExtractor.extractCheckpointFrames()`
   (call from a coroutine, e.g. in the screen that hosts the review UI) to
   pull one JPEG per checkpoint out of the saved `.mp4`.
5. Show the 8 extracted JPEGs + labels on a review screen; use
   `FrameExtractor.extractBurst()` if you want to offer "pick a sharper frame"
   for any checkpoint before final upload.

## Gradle dependencies (module build.gradle.kts)

```kotlin
dependencies {
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

## Manifest permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" /> <!-- only if you enable audio narration -->
<uses-feature android:name="android.hardware.camera.any" />
```
Request `CAMERA` (and `RECORD_AUDIO` if used) at runtime before showing `OrbitCaptureScreen` — not included in this sketch, use `ActivityResultContracts.RequestPermission()`.

## What's deliberately left out of this sketch (per the phase plan)
- No ARCore / sensor-based height tracking — the `FramingOverlay` is a static
  visual guide only (Phase 3 adds real pose tracking as an enhancement).
- No auto-advance heuristic for checkpoints — every checkpoint is a manual tap
  (`CheckpointSource.MANUAL_TAP`); `CheckpointSource.AUTO_TIMER` /
  `AUTO_HEADING` are defined in the enum so Phase 3 can slot in without a data
  model change.
- No ML classification anywhere — labeling is a pure `(elapsedMs → label)`
  lookup captured at tap-time.
- Detail-shot wizard (Engine, Roof, Mirrors, VIN, Tire Info, Package Decals,
  Trunk) is a separate simple photo-capture flow, not shown here — each step
  just tags its own photo with `DetailShot(label = currentStep.label, ...)`
  and needs no video/timestamp logic at all.
