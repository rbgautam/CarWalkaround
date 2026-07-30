# Car Walkaround App — Plan

## 1. Scope for this phase (exterior only)

The 8 "orbit" shots below form one continuous walkaround video, captured in order as the user walks around the car:

| # | Label |
|---|---|
| 1 | Front |
| 2 | Passenger Side Front Corner |
| 3 | Passenger Side View |
| 4 | Passenger Side Rear Corner |
| 5 | Rear |
| 6 | Driver Side Rear Corner |
| 7 | Driver Side View |
| 8 | Driver Side Front Corner |

The following are captured as separate short clips/photos, since they need a different camera distance/angle than the orbit walk:

| # | Label | Notes |
|---|---|---|
| 9 | Roof | |
| 10 | Passenger Side Mirror (Glass) | |
| 11 | Driver Side Mirror (Glass) | |
| 12 | Engine | Requires hood-open capture |
| 13 | Package Decals | Assumed exterior window sticker, not interior — confirm |
| 14 | Tire Information | Sidewall DOT/size — exterior close-up |
| 15 | Vin Sticker | Assuming driver door jamb VIN plate (exterior-adjacent) — confirm |
| 16 | Trunk/Cargo Area/Truck Bed | Only if captured open/exterior; if it's the interior cargo liner, defer to phase 2 |

All interior labels (Odometer, Dash lights, seats, consoles, headliner, steering wheel, etc.) are explicitly **deferred** to a later phase.

**Open questions to confirm before/while building:**
- Is "Package Decals" the dealer window sticker, or something else specific to the use case?
- Is "Trunk/Cargo Area" meant to be captured open (exterior, trunk lid up) or is it really an interior shot?

---

## 2. App flow (guided capture wizard)

**Step 0 — Vehicle setup**: enter VIN or plate (optional), pick vehicle type (sedan/SUV/truck) — affects camera height guide and whether "Truck Bed" applies.

**Step 1 — Orbit walkaround video** (the 8 side/corner/front/rear shots)
- Full-screen camera preview with an overlay guide (see §3).
- Real-time **pace guidance**: tell the user to speed up/slow down so progress around the car advances at a steady rate.
- Real-time **height guidance**: keep phone within a target vertical band.
- App gives a checkpoint (tap or auto-advance) at each of the 8 target angles, with haptic/audio feedback, so the user knows each has been captured — no need to stop recording.

**Step 2 — Detail shots** (Roof, Mirrors, Engine, Tire Info, VIN Sticker, Package Decals, Trunk/Bed)
- One-by-one guided still/short-clip capture, each with its own on-screen framing guide and instructions (e.g. "Open the hood," "Get within 12 inches of the tire sidewall").

**Step 3 — Review & confirm**
- Show extracted frames grouped by label, let the user retake any that are blurry/misclassified before upload.

---

## 3. Consistent pace & height guidance

### 3a. Height consistency
- **ARCore** (if available): gives real-world phone pose relative to a fixed start anchor; display a horizontal guide band that turns green/red as height drifts.
- **Fallback** (no ARCore / permission declined): device orientation only (pitch from `SensorManager`) — guides "keep phone level," weaker than true height tracking.
- A static horizontal line + car-silhouette overlay (sized per vehicle class) does most of the practical work in both cases — the user aligns the car against a fixed on-screen outline. ARCore is an enhancement, not a hard dependency.

### 3b. Pace consistency
- **Sensor/pose-based**: ARCore camera pose (or step-counter + compass heading) to estimate angular progress; show a progress ring or speed-up/slow-down prompt.
- **Vehicle-relative (recommended primary signal)**: lightweight on-device detection (e.g. ML Kit Object Detection) tracking the car's bounding box each frame, inferring angular position from how the silhouette shifts — more robust than absolute phone pose, which drifts.
- UI: curved progress track (clock-face style) with a moving dot, plus a simple "slow down / speed up" indicator, similar to fitness-app pacing UIs.

### 3c. Static framing guide
- Semi-transparent car silhouette overlay (front/side/rear templates depending on orbit segment) keeps the car consistently framed and similarly sized on-screen throughout — also aids frame extraction/labeling.

---

## 4. Frame extraction & labeling pipeline

**Chosen approach for Phases 1–3: pure checkpoint-timestamp-based labeling — no ML classification.**

- Each of the 8 orbit labels is assigned by tracking `(label, timestamp)` at the moment the user reaches that point in the walk — via manual checkpoint tap (Phase 2) or a time/heading heuristic auto-advance (Phase 3), not by analyzing frame content.
- Frames are extracted from the recorded video at each checkpoint's timestamp (`MediaMetadataRetriever.getFrameAtTime`, `OPTION_CLOSEST`), and the label is a direct lookup — not a prediction.
- Detail shots (Engine, Roof, Mirrors, VIN, Tire Info, Package Decals, Trunk) are captured one at a time inside the wizard, so the label is just whatever step the wizard was on when the photo was taken — trivial, no timestamp logic needed.
- Optional: extract a small burst of frames around each checkpoint (e.g. ±300ms) so the user can pick the sharpest one in the review screen if the exact tap-moment frame is blurry.

**ML-based classification (TFLite/backend model) is deferred to Phase 4+** as an accuracy enhancement (e.g. improving auto-advance or catching mislabeled frames), not a Phase 1–3 dependency. This removes the need for a labeled training dataset before shipping a working v1.

---

## 5. Data model

```kotlin
data class CaptureSession(
    val vehicleId: String,
    val orbitVideoUri: String?,
    val checkpoints: List<Checkpoint>,   // from orbit video
    val detailShots: List<DetailShot>    // from wizard steps
)

data class Checkpoint(
    val label: String,        // e.g. "Passenger Side Front Corner"
    val timestampMs: Long,    // position in orbitVideoUri
    val frameUri: String?,    // extracted still, populated after processing
    val source: CheckpointSource // MANUAL_TAP, AUTO_TIMER, AUTO_HEADING
)

data class DetailShot(
    val label: String,        // e.g. "Vin Sticker"
    val mediaUri: String,
    val capturedAt: Long
)
```

No confidence scores, no model version tracking, no inference pipeline for Phases 1–3 — just a straightforward capture log.

---

## 6. Suggested tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Standard modern Android |
| UI | Jetpack Compose | Easier to build the dynamic overlay/guide UI |
| Camera | CameraX | Simplifies preview + video capture + frame analysis pipeline |
| Pose/AR | ARCore (optional enhancement) | Real-world height/position tracking |
| On-device car detection (Phase 3) | ML Kit Object Detection or custom TFLite | Pace tracking via car bounding box |
| Frame extraction | MediaMetadataRetriever / FFmpeg-kit | Pull frames at target timestamps |
| On-device classification (Phase 4+) | TFLite (MobileNetV3/EfficientNet-Lite) | Fast, offline first-pass labeling |
| Backend | Firebase for MVP; custom Node/Python service for scale | Storage + optional stronger re-classification model |
| Storage | Cloud object storage (S3/GCS/Firebase Storage) | Video + frame assets |

---

## 7. Phased roadmap

**Phase 1 (MVP)**
- Detail-shot wizard only (Engine, Roof, Mirrors, VIN Sticker, Tire Info, Package Decals, Trunk).
- Each step = one photo/clip, auto-labeled by step name.
- Local storage + simple review/retake screen.
- No video, no pace tracking, no ML.

**Phase 2**
- Add orbit video capture with static silhouette overlay (visual framing guide only, no sensor tracking yet).
- Manual checkpoint tap UI: user taps through the 8 labels as they walk.
- Frame extraction at each tap timestamp (MediaMetadataRetriever).
- Review screen shows all 16 exterior labels with their extracted frame, lets user retake/reassign.

**Phase 3**
- Add pace guidance (target seconds-per-checkpoint, speed up/slow down prompt) and height guidance (ARCore or gyroscope-based band).
- Auto-advance checkpoints using a heuristic (elapsed time at target pace, or car bounding-box heading heuristic) instead of requiring manual taps — always allow manual override/tap-to-correct, both live and in the review screen.
- Still zero image classification — auto-advance driven by time/pace/pose heuristics only.

**Phase 4**
- Introduce on-device (TFLite) and/or backend classification as an accuracy layer on top of checkpoint labeling — requires a labeled image dataset across the 16 exterior classes.

**Phase 5**
- Expand to interior labels, reusing the same wizard/checkpoint infrastructure.

---

## 8. Implementation sketch (Phases 1–3, no-ML labeling)

A code sketch for the orbit checkpoint-tap UI and frame extraction has been built out separately:
- `Checkpoint.kt` — data models (`OrbitLabel`, `Checkpoint`, `OrbitSession`)
- `OrbitCaptureViewModel.kt` — recording clock, pace hint, checkpoint logging state machine
- `OrbitCaptureScreen.kt` — Compose UI: CameraX preview, static framing overlay, checkpoint progress strip, tap button
- `FrameExtractor.kt` — post-recording frame extraction via `MediaMetadataRetriever`, keyed off checkpoint timestamps

See the accompanying README/code files for wiring details, Gradle dependencies, and manifest permissions.
