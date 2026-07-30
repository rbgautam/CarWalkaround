package com.example.carwalkaround

import java.io.File

/**
 * The 8 exterior labels captured during the continuous orbit walkaround video,
 * in the order the user will naturally walk around the car.
 */
enum class OrbitLabel(val displayName: String) {
    FRONT("Front"),
    PASSENGER_FRONT_CORNER("Passenger Side Front Corner"),
    PASSENGER_VIEW("Passenger Side View"),
    PASSENGER_REAR_CORNER("Passenger Side Rear Corner"),
    REAR("Rear"),
    DRIVER_REAR_CORNER("Driver Side Rear Corner"),
    DRIVER_VIEW("Driver Side View"),
    DRIVER_FRONT_CORNER("Driver Side Front Corner");

    companion object {
        val ORDERED: List<OrbitLabel> = entries
    }
}

enum class CheckpointSource { MANUAL_TAP, AUTO_TIMER, AUTO_HEADING }

/**
 * One captured checkpoint: which label, at what point in the recording (ms from
 * the start of the video file), and how it was triggered. frameUri is filled in
 * after extraction (§ FrameExtractor).
 *
 * Immutable by design: checkpoints are held in Compose-observed state, and an
 * in-place `var` write is invisible to recomposition. Update via [copy] and
 * replace the containing list instead.
 */
data class Checkpoint(
    val label: OrbitLabel,
    val timestampMs: Long,
    val source: CheckpointSource,
    val frameUri: String? = null
)

/**
 * The first label not yet captured in [checkpoints], or null once all 8 are
 * done. Shared by [OrbitSession] and the capture ViewModel so the "what's next"
 * rule lives in exactly one place.
 */
fun nextLabelAfter(checkpoints: List<Checkpoint>): OrbitLabel? {
    val doneLabels = checkpoints.mapTo(mutableSetOf()) { it.label }
    return OrbitLabel.ORDERED.firstOrNull { it !in doneLabels }
}

/**
 * Immutable snapshot of one completed orbit recording pass, handed to the
 * review screen once the video file is finalized.
 */
data class OrbitSession(
    val videoFilePath: String,
    val checkpoints: List<Checkpoint> = emptyList()
) {
    /**
     * Stable per-session identifier, derived from the timestamped video file
     * name (`orbit_<millis>.mp4` -> `orbit_<millis>`). Extracted frames are
     * namespaced under this so a second walkaround cannot overwrite the stills
     * from the first — the label alone is not unique across sessions.
     */
    val sessionId: String
        get() = File(videoFilePath).nameWithoutExtension

    val nextLabel: OrbitLabel?
        get() = nextLabelAfter(checkpoints)

    val isComplete: Boolean
        get() = checkpoints.size >= OrbitLabel.ORDERED.size
}
