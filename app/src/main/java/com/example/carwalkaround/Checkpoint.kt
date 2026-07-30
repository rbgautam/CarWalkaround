package com.example.carwalkaround

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
 */
data class Checkpoint(
    val label: OrbitLabel,
    val timestampMs: Long,
    val source: CheckpointSource,
    var frameUri: String? = null
)

/**
 * Full state for one orbit recording pass.
 */
data class OrbitSession(
    val videoFilePath: String,
    val checkpoints: MutableList<Checkpoint> = mutableListOf()
) {
    val nextLabel: OrbitLabel?
        get() {
            val doneLabels = checkpoints.map { it.label }.toSet()
            return OrbitLabel.ORDERED.firstOrNull { it !in doneLabels }
        }

    val isComplete: Boolean
        get() = checkpoints.size >= OrbitLabel.ORDERED.size
}
