# Car Walkaround — Wiring Notes

Guided exterior vehicle capture: photograph the VIN, then work through the
detail shots and/or a continuous orbit walkaround video. Every piece of media is
tagged to the VIN capture that opened the session.

See `car_walkaround_app_plan.md` for the phase plan this implements. Phases 1
and 2 are built; Phase 3 (pace/height sensor guidance) and Phase 4 (frame
classification) are not.

## Files

**Vehicle identity — runs before everything else**
- `VehicleTag.kt` — `VehicleTag` model, VIN normalization, ISO 3779 check-digit
  validation, and `VehicleStorage` (the on-disk layout)
- `VinCaptureScreen.kt` — first screen: VIN photo, OCR result, manual VIN entry;
  also hosts the shared `VehicleTagHeader` banner
- `VinOcr.kt` — ML Kit text recognition + VIN candidate extraction

**Phase 1 — detail shots**
- `DetailShot.kt` — `DetailLabel` (8 labels with framing instructions),
  `DetailShot`, `DetailSession`
- `DetailShotViewModel.kt` — wizard state machine (step, skip, back, retake)
- `DetailShotWizardScreen.kt` — one label per step, one photo each
- `DetailReviewScreen.kt` — per-label grid with retake routing

**Phase 2 — orbit video**
- `Checkpoint.kt` — `OrbitLabel`, `Checkpoint`, `OrbitSession`
- `OrbitCaptureViewModel.kt` — recording clock, pace hint, checkpoint logging
- `OrbitCaptureScreen.kt` — CameraX preview, framing overlay, checkpoint strip
- `FrameExtractor.kt` — pulls a JPEG per checkpoint via `MediaMetadataRetriever`
- `ReviewScreen.kt` — extracted frames per checkpoint

**Shared**
- `MainActivity.kt` — permission gate + `Screen` navigation state
- `PhotoCapture.kt` — CameraX still-photo binding, shutter, sampled thumbnail
  decoding (shared by the VIN screen and the detail wizard)

## Flow

1. **Camera permission** is requested at the app entry point (`MainActivity`).
2. **VIN capture** (`VinCaptureScreen`). The user photographs the VIN plate.
   ML Kit reads it and pre-fills the VIN field; the user can correct or replace
   it, or leave it blank. Accepting produces a `VehicleTag`.
3. **Home** offers the two capture flows. The tag is displayed here with a
   "Change" action — the only place a vehicle swap is offered, since swapping
   mid-session would file shots under a car they are not of.
4. **Detail wizard** — 8 steps, one photo each. The label is whichever step was
   open when the shutter fired; no classification involved. Steps are skippable
   (not every label applies to every vehicle) and the review screen reports
   what is missing.
5. **Orbit capture** — one continuous video; the user taps a checkpoint at each
   of the 8 angles. Each tap logs `(label, elapsedMs)` against *elapsed
   recording time*, not wall-clock, so the timestamp survives UI lag. On finish,
   `FrameExtractor` pulls one JPEG per checkpoint out of the finalized `.mp4`.

Both review screens show the `VehicleTagHeader` so the tag is visible at the
moment it is being applied, not only when it was created.

## VIN tagging

`VehicleTag` is what makes a pile of JPEGs and an MP4 an inspection *of a
specific car*. It is carried two ways, because each covers the other's gap:

- **In memory** — `DetailSession.vehicleTag` and `OrbitSession.vehicleTag` are
  non-null and required at construction. A session that does not know its
  vehicle is unrepresentable rather than validated after the fact.
- **On disk** — everything for a vehicle is written under one folder. The
  in-memory tag dies on cold start; the folder does not.

```
<externalFilesDir>/captures/vehicle_<millis>/
    vin_<millis>.jpg          <- the tag's own photo
    vehicle_tag.json          <- tagId, VIN, capture time, VIN photo name
    details/<LABEL>_<millis>.jpg
    orbit_<millis>.mp4
    frames/<sessionId>/<LABEL>.jpg
```

`tagId` is derived from capture time, not from the VIN: the VIN is not known
when the folder is claimed, and a typo'd VIN must not be able to collide two
different cars into one directory.

Retakes (both VIN and detail shots) write a *new* timestamped file and delete
the superseded one, rather than overwriting in place — the review screens key
their decoded thumbnails on the file path, so a reused path would leave the
stale image on screen.

## VIN OCR

On-device ML Kit text recognition, run against the full-resolution capture
(thumbnail sampling destroys the strokes that distinguish `8` from `B`).

The result is an **assist, not an authority**. The VIN field is editable at
every stage, including while the scan is running, and once the user types,
a late-landing scan will not overwrite them. A VIN has no redundancy for a wrong
character to be caught by downstream, so a plausible-but-wrong scan that gets
waved through mislabels the entire capture.

Candidate selection: every 17-character window of every recognized line is
tested, and the **ISO 3779 check digit** arbitrates. Plates carry more than the
VIN (GVWR figures, paint codes, barcode captions), so assuming the longest or
topmost run is the VIN would be wrong often enough to matter.

Three outcomes drive different UI copy, because they call for different amounts
of user attention:

| Confidence | Meaning | UI asks for |
|---|---|---|
| `CHECKSUM_VALID` | 17 valid chars, check digit agrees | a glance |
| `UNVERIFIED` | VIN-shaped, check digit disagrees | proof-read all 17 |
| `NOT_FOUND` | no VIN-shaped run in the image | type it, or retake closer |

A failing check digit is **not** proof of a bad read — the check digit is
mandatory in North America but optional elsewhere, so legitimately-transcribed
imported vehicles fail it. It is treated as confidence, never as a gate. Same
for `looksLikeVin`: the UI warns but never blocks, since a rejected VIN would
strand the user on the first screen with a perfectly good photo.

`I`, `O` and `Q` are rewritten to `1`, `0`, `0` before matching. That is safe in
a way the other classic confusions (`8`/`B`, `5`/`S`, `2`/`Z`) are not: those are
ambiguous both ways in a real VIN, whereas I/O/Q are excluded from the VIN
alphabet by design, so seeing one means the *reader* erred.

This is unrelated to the plan's Phase 4 ML work. That is image *classification*
of walkaround frames and needs a labelled dataset; reading characters off a
plate is self-contained and has a checksum to grade it, which is why it lands
now.

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

    // VIN OCR. The *bundled* Latin model, not the Play-Services-delivered
    // variant: a walkaround happens on a lot or in a garage, where a first-run
    // model download would fail exactly when the app is needed.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Bridges ML Kit's Task API to suspend functions.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
```

## Manifest permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" /> <!-- only if you enable audio narration -->
<uses-feature android:name="android.hardware.camera.any" />
```

`CAMERA` is requested at runtime in `MainActivity` via
`ActivityResultContracts.RequestPermission()` before any capture screen is
reachable. `RECORD_AUDIO` is only needed if you enable `withAudioEnabled()` on
the orbit recording (currently off).

## 16 KB page sizes

Android 15+ devices may use 16 KB memory pages, and Play requires apps
targeting Android 15 to support them. Compliance is two separate properties:

1. **ELF segment alignment** — every bundled `.so` must have its `LOAD`
   segments aligned to 16 KB (`p_align = 0x4000`). This is fixed at link time,
   so for transitive libraries the only lever is the dependency version.
2. **APK packaging** — `.so` entries must be `Stored` (uncompressed) and 16 KB
   aligned within the zip, so the loader can map them straight out of the APK.

This app has no NDK code of its own, but it bundles native libraries
transitively: `libimage_processing_util_jni.so` and `libsurface_util_jni.so`
(CameraX), `libandroidx.graphics.path.so` (Compose UI), and the OCR pipeline
libraries from ML Kit. Property (2) is handled by AGP 8.5.1+ with
`useLegacyPackaging = false`. Property (1) is why CameraX is pinned to 1.4.2+.

**Verified after adding ML Kit** (debug APK, NDK 27.1 `llvm-readelf` +
build-tools 35.0.0 `zipalign`): compliant. Both checks pass on both 64-bit ABIs,
and zip alignment passes on all 16 `.so` entries.

| ABI | `libmlkit_google_ocr_pipeline.so` |
|---|---|
| `arm64-v8a` (64-bit) | `0x4000` ✅ |
| `x86_64` (64-bit) | `0x4000` ✅ |
| `armeabi-v7a` (32-bit) | `0x1000` — does not apply |
| `x86` (32-bit) | `0x1000` — does not apply |

ML Kit aligned only its 64-bit variants, which is correct and not a defect: 16 KB
pages are a 64-bit-only Android feature, so a 32-bit `.so` never gets loaded on
a device with 16 KB pages. **Read this table before concluding a `FAIL` line
matters — check the ABI first.** The CameraX and Compose libraries report
`0x4000` on all four ABIs.

To re-verify after a dependency change — both checks, not just one:

```bash
# (1) ELF alignment: every LOAD segment must report 0x4000
unzip -o app/build/outputs/apk/debug/app-debug.apk -d /tmp/apk
for so in $(find /tmp/apk/lib -name '*.so'); do
  echo "$so"; "$ANDROID_NDK/toolchains/llvm/prebuilt/<host>/bin/llvm-readelf" -l "$so" | awk '$1=="LOAD"{print $NF}' | sort -u
done

# (2) zip alignment: must exit 0
"$ANDROID_HOME/build-tools/<ver>/zipalign" -c -P 16 4 app/build/outputs/apk/debug/app-debug.apk
```

## What's deliberately left out (per the phase plan)

- **No persistence across app restarts.** Sessions live in memory; only the
  media and `vehicle_tag.json` sidecars reach disk. The sidecars hold enough to
  rebuild a session if a resume flow is added later.
- **No upload/backend.** Capture is local-only.
- **No ARCore / sensor-based height tracking** — `FramingOverlay` is a static
  visual guide (Phase 3 adds real pose tracking).
- **No auto-advance for checkpoints** — every checkpoint is a manual tap
  (`CheckpointSource.MANUAL_TAP`); `AUTO_TIMER` / `AUTO_HEADING` exist in the
  enum so Phase 3 slots in without a data model change.
- **No image classification** — orbit labeling is a pure `(elapsedMs → label)`
  lookup captured at tap time, and detail labels are just the open wizard step.
  The only ML in the app is VIN text recognition, which is a different problem
  (see above).
- **No interior labels** — deferred to Phase 5.

## Known rough edges

- The detail wizard still has its own `VIN_STICKER` step, so the VIN is
  photographed twice: once to establish *which car*, once as the inspection
  photo of the plate. Drop `VIN_STICKER` from `DetailLabel` if that is
  redundant for your use case.
- `ReviewScreen`'s `CheckpointCard` decodes extracted frames at full resolution
  (`BitmapFactory.decodeFile`), unlike `DetailReviewScreen`, which samples down
  via `rememberThumbnail`. Long orbit sessions could pressure memory here.
