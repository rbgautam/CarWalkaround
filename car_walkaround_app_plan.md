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

**Step 0 — VIN capture (built)**: photograph the VIN plate. On-device OCR reads it and pre-fills a VIN field the user can correct, replace, or leave blank. Accepting produces a `VehicleTag` that every subsequent photo and video is filed under — see §5a.

This is deliberately the *first* screen and not skippable: the tag decides where captured files are written, so there is nowhere to put a photo taken before it exists.

**Still open from the original Step 0**: picking a vehicle type (sedan/SUV/truck) is *not* built. It was to affect the camera height guide and whether "Truck Bed" applies. Currently every label is shown for every vehicle and the user skips what does not apply, which is adequate for Phases 1–2 but should be revisited when the silhouette overlay becomes vehicle-specific in Phase 3.

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

**VIN OCR is not an exception to the above.** ML Kit text recognition ships in Phase 1 to read the VIN plate (§2 Step 0), but it is a different kind of problem from what this section defers:

| Aspect | VIN OCR (built) | Frame classification (Phase 4+) |
|---|---|---|
| Task | read characters off a plate | decide which angle a frame shows |
| Training data | none — pre-trained, general-purpose | a labelled dataset across 16 classes |
| Verifiable? | yes — ISO 3779 check digit grades the result | no — only a human can confirm a label |
| Failure mode | wrong VIN, caught by checksum or by the user reading the same plate | silently mislabeled frame |

The check digit is what makes OCR shippable now: the app can tell a confident read from a doubtful one and say so, instead of asking the user to trust it. Classification has no equivalent self-check, which is exactly why it waits for a dataset. Labeling remains a pure `(checkpoint → label)` lookup — no frame content is analyzed anywhere.

---

## 5. Data model

**As built.** The original sketch had a single `CaptureSession` holding both the orbit video and the detail shots, keyed by a bare `vehicleId: String`. That did not survive contact with the flows: the two passes are started independently from the home screen, finish independently, and have their own review screens, so one combined session was always half-empty. They are now two sessions sharing a vehicle tag.

```kotlin
data class VehicleTag(
    val tagId: String,          // "vehicle_<millis>" — namespaces media on disk
    val vinPhotoPath: String,   // the authoritative record
    val capturedAt: Long,
    val vin: String? = null     // OCR'd or typed; may be absent
)

data class DetailSession(
    val vehicleTag: VehicleTag,          // required, not nullable
    val shots: List<DetailShot> = emptyList()
)

data class DetailShot(
    val label: DetailLabel,     // enum, not String — it's the wizard step
    val mediaPath: String,
    val capturedAt: Long
)

data class OrbitSession(
    val vehicleTag: VehicleTag,          // required, not nullable
    val videoFilePath: String,
    val checkpoints: List<Checkpoint> = emptyList()
)

data class Checkpoint(
    val label: OrbitLabel,      // enum, not String
    val timestampMs: Long,      // position in videoFilePath
    val source: CheckpointSource, // MANUAL_TAP, AUTO_TIMER, AUTO_HEADING
    val frameUri: String? = null  // extracted still, populated after processing
)
```

Three changes worth noting against the sketch above:

- **`vehicleTag` is non-null on both sessions.** A session that does not know its vehicle cannot say which car its media documents. Making that unrepresentable costs one thing — the detail wizard's UI state has to be nullable until a tag is supplied — and buys the guarantee that no untagged file can reach disk and fail later at review or upload, when the capture is over and the car has gone.
- **Labels are enums, not strings.** They are a closed set defined by the app, and the enum carries the framing instruction shown over the preview.
- **No confidence scores or model version tracking**, as originally planned. The one ML result in the app (VIN OCR) is resolved to a plain `String?` at the point of capture, with its confidence consumed by the UI and then discarded — nothing downstream branches on it.

### 5a. On-disk layout

The tag is also expressed as directory structure, because the in-memory tag dies on cold start and a folder does not:

```
<externalFilesDir>/captures/vehicle_<millis>/
    vin_<millis>.jpg
    vehicle_tag.json          <- tagId, VIN, capture time
    details/<LABEL>_<millis>.jpg
    orbit_<millis>.mp4
    frames/<sessionId>/<LABEL>.jpg
```

`tagId` derives from capture time rather than the VIN: the VIN is not known when the folder is claimed, and a typo'd VIN must not collide two cars into one directory.

There is still **no persistence of sessions themselves** — only media and the sidecar reach disk. The sidecars hold enough to reconstruct a session if a resume flow is added.

---

## 6. Suggested tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Standard modern Android |
| UI | Jetpack Compose | Easier to build the dynamic overlay/guide UI |
| Camera | CameraX | Simplifies preview + video capture + frame analysis pipeline |
| VIN OCR (built) | ML Kit text recognition, **bundled** model | Reads the VIN plate offline. Bundled rather than Play-Services-delivered: a walkaround happens on a lot or in a garage, where a first-run model download fails exactly when the app is needed |
| Pose/AR | ARCore (optional enhancement) | Real-world height/position tracking |
| On-device car detection (Phase 3) | ML Kit Object Detection or custom TFLite | Pace tracking via car bounding box |
| Frame extraction | MediaMetadataRetriever / FFmpeg-kit | Pull frames at target timestamps |
| On-device classification (Phase 4+) | TFLite (MobileNetV3/EfficientNet-Lite) | Fast, offline first-pass labeling |
| Backend | Firebase for MVP; custom Node/Python service for scale | Storage + optional stronger re-classification model |
| Storage | Cloud object storage (S3/GCS/Firebase Storage) | Video + frame assets |

---

## 7. Phased roadmap

**Phase 1 (MVP) — built**
- VIN capture as the entry screen, with ML Kit OCR pre-filling a user-editable VIN field (§2 Step 0). Produces the `VehicleTag` everything else is filed under.
- Detail-shot wizard (Roof, Mirrors, Engine, Package Decals, Tire Info, VIN Sticker, Trunk), each step one photo, auto-labeled by step name, skippable.
- Local storage under the vehicle's folder + review/retake screen reporting missing labels.
- No video, no pace tracking, no frame classification.

*Deviation from plan:* VIN OCR was not in the original Phase 1 scope — the plan had "no ML" until Phase 4. See §4 for why text recognition is a separable problem from frame classification, and why the check digit is what makes it shippable this early.

**Phase 2 — built**
- Add orbit video capture with static silhouette overlay (visual framing guide only, no sensor tracking yet).
- Manual checkpoint tap UI: user taps through the 8 labels as they walk.
- Frame extraction at each tap timestamp (MediaMetadataRetriever).
- Review screen shows the extracted frame per checkpoint.

*Deviation from plan:* the plan called for one review screen covering all 16 exterior labels. As built there are two — `DetailReviewScreen` (8 detail labels) and `ReviewScreen` (8 orbit checkpoints) — because the two passes are started and finished independently, so a combined screen would always be half-empty. `reassignCheckpoint` exists on the orbit ViewModel but is not yet wired to any UI; retake is implemented for detail shots only. A combined pre-upload summary across all 16 is still worth building when upload lands.

**Phase 3**
- Add pace guidance (target seconds-per-checkpoint, speed up/slow down prompt) and height guidance (ARCore or gyroscope-based band).
- Auto-advance checkpoints using a heuristic (elapsed time at target pace, or car bounding-box heading heuristic) instead of requiring manual taps — always allow manual override/tap-to-correct, both live and in the review screen.
- Still zero image classification — auto-advance driven by time/pace/pose heuristics only.

**Phase 4**
- Introduce on-device (TFLite) and/or backend classification as an accuracy layer on top of checkpoint labeling — requires a labeled image dataset across the 16 exterior classes.

**Phase 5**
- Expand to interior labels, reusing the same wizard/checkpoint infrastructure.

---

## 8. Implementation (Phases 1–2, as built)

**Vehicle identity — runs before everything else**
- `VehicleTag.kt` — `VehicleTag`, VIN normalization, ISO 3779 check-digit validation, `VehicleStorage` (on-disk layout of §5a)
- `VinCaptureScreen.kt` — entry screen: VIN photo, scan status, manual VIN entry; also the shared `VehicleTagHeader`
- `VinOcr.kt` — ML Kit text recognition and VIN candidate selection

**Phase 1 — detail shots**
- `DetailShot.kt` — `DetailLabel` (labels + framing instructions), `DetailShot`, `DetailSession`
- `DetailShotViewModel.kt` — wizard state machine (step, skip, back, retake)
- `DetailShotWizardScreen.kt` — one label per step, one photo each
- `DetailReviewScreen.kt` — per-label grid with retake routing

**Phase 2 — orbit video**
- `Checkpoint.kt` — `OrbitLabel`, `Checkpoint`, `OrbitSession`
- `OrbitCaptureViewModel.kt` — recording clock, pace hint, checkpoint logging state machine
- `OrbitCaptureScreen.kt` — CameraX preview, static framing overlay, checkpoint progress strip, tap button
- `FrameExtractor.kt` — post-recording frame extraction via `MediaMetadataRetriever`, keyed off checkpoint timestamps
- `ReviewScreen.kt` — extracted frame per checkpoint

**Shared**
- `MainActivity.kt` — camera permission gate + `Screen` navigation state
- `PhotoCapture.kt` — CameraX still-photo binding, shutter, sampled thumbnail decoding

See the README for wiring details, Gradle dependencies, manifest permissions, and the 16 KB page-size verification.
