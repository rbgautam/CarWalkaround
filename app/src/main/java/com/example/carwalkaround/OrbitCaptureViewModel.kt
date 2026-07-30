package com.example.carwalkaround

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Target seconds per checkpoint at a "correct" pace. Used only to drive the
 * pace hint (speed up / slow down) — it does NOT gate the manual tap, so the
 * user is never blocked; it's advisory only in Phase 2/3.
 */
private const val TARGET_SECONDS_PER_CHECKPOINT = 4f
private const val PACE_TOLERANCE_LOW = 0.6f
private const val PACE_TOLERANCE_HIGH = 1.4f

private const val PACE_LOWER_BOUND_MS =
    (TARGET_SECONDS_PER_CHECKPOINT * PACE_TOLERANCE_LOW * 1000).toLong()
private const val PACE_UPPER_BOUND_MS =
    (TARGET_SECONDS_PER_CHECKPOINT * PACE_TOLERANCE_HIGH * 1000).toLong()

/** How long a "slow down" hint stays up after a too-fast checkpoint. */
private const val SLOW_DOWN_HINT_DURATION_MS = 1_500L

enum class PaceHint { ON_PACE, SPEED_UP, SLOW_DOWN }

data class OrbitUiState(
    val isRecording: Boolean = false,
    val recordingStartMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val currentLabel: OrbitLabel? = OrbitLabel.ORDERED.first(),
    val checkpoints: List<Checkpoint> = emptyList(),
    val paceHint: PaceHint = PaceHint.ON_PACE,
    val isComplete: Boolean = false
) {
    /** Derived from [checkpoints] so the two can never disagree. */
    val completedLabels: List<OrbitLabel>
        get() = checkpoints.map { it.label }
}

class OrbitCaptureViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OrbitUiState())
    val uiState: StateFlow<OrbitUiState> = _uiState.asStateFlow()

    private var videoFilePath: String? = null
    private var lastCheckpointElapsedMs: Long = 0L

    /** Elapsed-ms deadline until which a SLOW_DOWN hint stays on screen. */
    private var slowDownHintUntilMs: Long = 0L

    /** Call once CameraX has started writing to [videoFilePath]. */
    fun onRecordingStarted(videoFilePath: String) {
        this.videoFilePath = videoFilePath
        lastCheckpointElapsedMs = 0L
        slowDownHintUntilMs = 0L
        _uiState.value = OrbitUiState(
            isRecording = true,
            recordingStartMs = SystemClock.elapsedRealtime(),
            currentLabel = OrbitLabel.ORDERED.first()
        )
        tickClock()
    }

    /** Simple 10Hz clock to update elapsed time + pace hint while recording. */
    private fun tickClock() {
        viewModelScope.launch {
            while (_uiState.value.isRecording) {
                val state = _uiState.value
                val elapsed = SystemClock.elapsedRealtime() - state.recordingStartMs
                _uiState.value = state.copy(
                    elapsedMs = elapsed,
                    paceHint = paceHintAt(elapsed)
                )
                delay(100)
            }
        }
    }

    /**
     * Only SPEED_UP is derivable from an interval still in progress: the longer
     * the user goes without a checkpoint, the more certain it is they're too
     * slow. "Too fast" is the opposite — a short interval is indistinguishable
     * from an interval that simply hasn't finished yet, so it can only be judged
     * once the user actually taps ([onCheckpointTapped] sets the deadline this
     * reads). Testing it continuously is what made the banner read "Slow down"
     * from the instant recording began.
     */
    private fun paceHintAt(elapsedMs: Long): PaceHint = when {
        elapsedMs - lastCheckpointElapsedMs > PACE_UPPER_BOUND_MS -> PaceHint.SPEED_UP
        elapsedMs < slowDownHintUntilMs -> PaceHint.SLOW_DOWN
        else -> PaceHint.ON_PACE
    }

    /**
     * Called when the user taps the checkpoint button (or, in Phase 3, when an
     * auto-advance heuristic fires). Logs the current video timestamp against
     * the current expected label and advances to the next one.
     */
    fun onCheckpointTapped(source: CheckpointSource = CheckpointSource.MANUAL_TAP) {
        val state = _uiState.value
        if (!state.isRecording) return
        val label = nextLabelAfter(state.checkpoints) ?: return

        val elapsed = SystemClock.elapsedRealtime() - state.recordingStartMs
        val interval = elapsed - lastCheckpointElapsedMs

        // The first checkpoint's "interval" is the time from hitting record to
        // tapping Front, not a walking interval — the user is already standing
        // there, so tapping immediately is correct and must not be scolded.
        if (state.checkpoints.isNotEmpty() && interval < PACE_LOWER_BOUND_MS) {
            slowDownHintUntilMs = elapsed + SLOW_DOWN_HINT_DURATION_MS
        }
        lastCheckpointElapsedMs = elapsed

        val checkpoints = state.checkpoints +
            Checkpoint(label = label, timestampMs = elapsed, source = source)

        _uiState.value = state.copy(
            checkpoints = checkpoints,
            currentLabel = nextLabelAfter(checkpoints),
            paceHint = paceHintAt(elapsed),
            isComplete = checkpoints.size >= OrbitLabel.ORDERED.size
        )
    }

    /**
     * Allows the review screen to fix a mislabeled/mistimed checkpoint.
     * Replaces the list rather than mutating in place, so observers recompose.
     */
    fun reassignCheckpoint(label: OrbitLabel, newTimestampMs: Long) {
        val state = _uiState.value
        val index = state.checkpoints.indexOfFirst { it.label == label }
        if (index < 0) return

        val updated = state.checkpoints.toMutableList()
        updated[index] = updated[index].copy(timestampMs = newTimestampMs)
        _uiState.value = state.copy(checkpoints = updated)
    }

    /**
     * Stops the clock and returns an immutable snapshot of the finished session
     * for frame extraction. Safe to call only once the video file exists.
     */
    fun onRecordingStopped(): OrbitSession? {
        val path = videoFilePath ?: return null
        val state = _uiState.value
        _uiState.value = state.copy(isRecording = false)
        return OrbitSession(videoFilePath = path, checkpoints = state.checkpoints)
    }
}
