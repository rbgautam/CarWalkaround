package com.example.carwalkaround

/**
 * Phase 1 (MVP): the exterior "detail" labels captured one-at-a-time in a guided
 * wizard, rather than pulled out of the orbit video. Each needs a different
 * camera distance/angle than the walkaround, which is exactly why they are their
 * own step instead of an orbit checkpoint.
 *
 * Order follows the capture order that minimises walking and hood/trunk
 * open-close cycles: roof and mirrors from where the user already stands, then
 * the hood-open shot, then the close-ups, then the trunk.
 *
 * @param displayName label written to the capture log — this *is* the label, no
 *        classification involved (see plan §4).
 * @param instruction shown over the preview; tells the user where to stand and
 *        what must be open/visible before the shutter is any use.
 * @param needsPreparation steps requiring the user to physically open something
 *        first. Surfaced as an extra confirm prompt so they don't shoot a closed
 *        hood and only discover it in review.
 */
enum class DetailLabel(
    val displayName: String,
    val instruction: String,
    val needsPreparation: Boolean = false
) {
    ROOF(
        displayName = "Roof",
        instruction = "Stand at the front corner and raise the phone above " +
            "shoulder height. Fit the whole roof panel in frame."
    ),
    PASSENGER_MIRROR(
        displayName = "Passenger Side Mirror (Glass)",
        instruction = "Face the passenger mirror head-on, about arm's length " +
            "away. Fill the frame with the mirror glass."
    ),
    DRIVER_MIRROR(
        displayName = "Driver Side Mirror (Glass)",
        instruction = "Face the driver mirror head-on, about arm's length " +
            "away. Fill the frame with the mirror glass."
    ),
    ENGINE(
        displayName = "Engine",
        instruction = "Open the hood and prop it. Shoot straight down into the " +
            "engine bay so the whole bay is in frame.",
        needsPreparation = true
    ),
    PACKAGE_DECALS(
        displayName = "Package Decals",
        // Plan §1 open question: assumed to be the dealer window sticker. If it
        // turns out to mean body/trim decals, only this instruction changes.
        instruction = "Frame the window sticker flat-on. Keep the whole sticker " +
            "in frame and the text readable."
    ),
    TIRE_INFO(
        displayName = "Tire Information",
        instruction = "Get within about 12 inches of the tire sidewall. The " +
            "DOT code and size markings must be sharp."
    ),
    VIN_STICKER(
        displayName = "VIN Sticker",
        instruction = "Open the driver door and frame the VIN plate on the door " +
            "jamb. Fill the frame with the plate.",
        needsPreparation = true
    ),
    TRUNK(
        displayName = "Trunk / Cargo Area / Truck Bed",
        // Plan §1 open question: captured open, from outside. If this is really
        // an interior cargo-liner shot it moves to the phase 5 interior wizard.
        instruction = "Open the trunk or tailgate and shoot the cargo area from " +
            "directly behind the vehicle.",
        needsPreparation = true
    );

    companion object {
        val ORDERED: List<DetailLabel> = entries
    }
}

/**
 * One captured detail photo. The label is not predicted — it is whichever wizard
 * step was on screen when the shutter fired (plan §4).
 *
 * Immutable for the same reason as [Checkpoint]: these live in Compose-observed
 * state, where an in-place write is invisible to recomposition.
 */
data class DetailShot(
    val label: DetailLabel,
    val mediaPath: String,
    val capturedAt: Long
)

/**
 * Immutable snapshot of a detail-shot wizard pass.
 *
 * @param sessionId namespaces this pass's photos on disk. Two walkarounds of two
 *        different cars both produce an "ENGINE" shot, so the label alone is not
 *        a unique file name — same reasoning as [OrbitSession.sessionId].
 */
data class DetailSession(
    val sessionId: String,
    val shots: List<DetailShot> = emptyList()
) {
    fun shotFor(label: DetailLabel): DetailShot? = shots.firstOrNull { it.label == label }

    /**
     * Replaces any existing shot for the same label rather than appending, so a
     * retake cannot leave two photos claiming to be the same label. Returns a new
     * session; see the immutability note above.
     */
    fun withShot(shot: DetailShot): DetailSession =
        copy(shots = shots.filterNot { it.label == shot.label } + shot)

    val capturedLabels: Set<DetailLabel>
        get() = shots.mapTo(mutableSetOf()) { it.label }

    /** Labels the user has not photographed yet, in wizard order. */
    val missingLabels: List<DetailLabel>
        get() = DetailLabel.ORDERED.filterNot { it in capturedLabels }

    val isComplete: Boolean
        get() = missingLabels.isEmpty()
}

/** The first label not yet captured, or null once every step is done. */
fun nextDetailLabelAfter(shots: List<DetailShot>): DetailLabel? {
    val done = shots.mapTo(mutableSetOf()) { it.label }
    return DetailLabel.ORDERED.firstOrNull { it !in done }
}