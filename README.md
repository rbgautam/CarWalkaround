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
    // 1.4.2 or newer is required: CameraX 1.3.4 shipped
    // libimage_processing_util_jni.so with 4 KB ELF segment alignment, which
    // fails the 16 KB page size requirement (see "16 KB page sizes" below).
    val cameraxVersion = "1.4.2"
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

## 16 KB page sizes

Android 15+ devices may use 16 KB memory pages, and Play requires apps
targeting Android 15 to support them. Compliance is two separate properties:

1. **ELF segment alignment** — every bundled `.so` must have its `LOAD`
   segments aligned to 16 KB (`p_align = 0x4000`). This is fixed at link time,
   so for transitive libraries the only lever is the dependency version.
2. **APK packaging** — `.so` entries must be `Stored` (uncompressed) and 16 KB
   aligned within the zip, so the loader can map them straight out of the APK.

This app has no NDK code of its own, but it does bundle native libraries
transitively: `libimage_processing_util_jni.so` and `libsurface_util_jni.so`
(CameraX), and `libandroidx.graphics.path.so` (Compose UI). Property (2) is
handled by AGP 8.5.1+ with `useLegacyPackaging = false`. Property (1) is why
CameraX is pinned to 1.4.2+.

To verify after a dependency change — both checks, not just one:

```bash
# (1) ELF alignment: every LOAD segment must report 0x4000
unzip -o app/build/outputs/apk/debug/app-debug.apk -d /tmp/apk
for so in $(find /tmp/apk/lib -name '*.so'); do
  echo "$so"; "$ANDROID_NDK/toolchains/llvm/prebuilt/<host>/bin/llvm-readelf" -l "$so" | awk '$1=="LOAD"{print $NF}' | sort -u
done

# (2) zip alignment: must exit 0
"$ANDROID_HOME/build-tools/<ver>/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
```

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
