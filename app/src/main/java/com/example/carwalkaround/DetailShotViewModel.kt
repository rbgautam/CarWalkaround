package com.example.carwalkaround

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DetailUiState(
    val session: DetailSession,
    /**
     * The step on screen, or null once the wizard has nothing left to shoot —
     * which is the signal to move to review.
     *
     * Held explicitly rather than derived from [DetailSession.missingLabels],
     * because a retake puts an *already captured* label back on screen. A
     * derived "first missing label" would immediately bounce off it.
     */
    val currentLabel: DetailLabel? = DetailLabel.ORDERED.first(),
    /** True while re-shooting an existing label; capture then returns to review. */
    val isRetaking: Boolean = false,
    val errorMessage: String? = null
) {
    val vehicleTag: VehicleTag
        get() = session.vehicleTag

    val stepNumber: Int
        get() = currentLabel?.let { DetailLabel.ORDERED.indexOf(it) + 1 } ?: DetailLabel.ORDERED.size

    val totalSteps: Int
        get() = DetailLabel.ORDERED.size
}

/**
 * Phase 1 wizard state machine: which detail label is on screen, what has been
 * shot, and where to go after each shutter press.
 *
 * There is no timestamp logic here (unlike [OrbitCaptureViewModel]) because a
 * detail shot's label is simply the step that was open when it was taken —
 * plan §4 calls this out as the trivial case.
 *
 * The state is null until [startNewSession] supplies a [VehicleTag]. That is a
 * consequence of tagging being mandatory: a [DetailSession] now requires a
 * vehicle, so there is no honest value to construct at field-initialization
 * time. The alternative — a placeholder tag — would let untagged photos reach
 * disk and only fail later, at review or upload, when the capture is over and
 * the car has driven away.
 */
class DetailShotViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<DetailUiState?>(null)
    val uiState: StateFlow<DetailUiState?> = _uiState.asStateFlow()

    /**
     * Begins a pass against [vehicleTag], discarding any previous one. The
     * ViewModel is activity-scoped and outlives a single walkaround, so without
     * this a second car would inherit the first car's photos and its
     * already-complete state. Files from the previous session are left on disk —
     * they belong to a finished capture log, under that vehicle's own folder.
     */
    fun startNewSession(vehicleTag: VehicleTag) {
        _uiState.value = DetailUiState(session = DetailSession(vehicleTag = vehicleTag))
    }

    /** Call once CameraX reports the JPEG is written to disk. */
    fun onShotCaptured(label: DetailLabel, mediaPath: String, capturedAt: Long) {
        val state = _uiState.value ?: return

        // A retake supersedes the previous file; drop it so a session directory
        // does not accumulate every discarded attempt.
        state.session.shotFor(label)
            ?.takeIf { it.mediaPath != mediaPath }
            ?.let { runCatching { File(it.mediaPath).delete() } }

        val session = state.session.withShot(
            DetailShot(label = label, mediaPath = mediaPath, capturedAt = capturedAt)
        )

        _uiState.value = state.copy(
            session = session,
            // Retakes are launched from review, so hand control straight back
            // there instead of resuming the walk through the remaining steps.
            currentLabel = if (state.isRetaking) null else nextDetailLabelAfter(session.shots),
            isRetaking = false,
            errorMessage = null
        )
    }

    fun onCaptureFailed(message: String) {
        _uiState.value = _uiState.value?.copy(errorMessage = message)
    }

    /**
     * Leaves the current step unshot and moves on. Skipping is allowed because
     * not every label applies to every vehicle (a sedan has no truck bed, a
     * loaner may have no window sticker) — the review screen reports what is
     * still missing rather than blocking the user here.
     */
    fun skipCurrentStep() {
        val state = _uiState.value ?: return
        val current = state.currentLabel ?: return
        val remaining = state.session.missingLabels.filterNot { it == current }
        _uiState.value = state.copy(
            currentLabel = if (state.isRetaking) null else remaining.firstOrNull(),
            isRetaking = false,
            errorMessage = null
        )
    }

    fun goToPreviousStep() {
        val state = _uiState.value ?: return
        val current = state.currentLabel ?: return
        val index = DetailLabel.ORDERED.indexOf(current)
        if (index <= 0) return
        _uiState.value = state.copy(
            currentLabel = DetailLabel.ORDERED[index - 1],
            errorMessage = null
        )
    }

    /** Jump back to one specific label from the review screen. */
    fun retake(label: DetailLabel) {
        _uiState.value = _uiState.value?.copy(
            currentLabel = label,
            isRetaking = true,
            errorMessage = null
        )
    }

    /** Finish the wizard early — remaining labels stay in [DetailSession.missingLabels]. */
    fun finishWizard() {
        _uiState.value = _uiState.value?.copy(
            currentLabel = null,
            isRetaking = false,
            errorMessage = null
        )
    }
}