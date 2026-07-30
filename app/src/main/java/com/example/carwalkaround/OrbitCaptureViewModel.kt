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

enum class PaceHint { ON_PACE, SPEED_UP, SLOW_DOWN }

data class OrbitUiState(
    val isRecording: Boolean = false,
    val recordingStartMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val currentLabel: OrbitLabel? = OrbitLabel.ORDERED.first(),
    val completedLabels: List<OrbitLabel> = emptyList(),
    val paceHint: PaceHint = PaceHint.ON_PACE,
    val isComplete: Boolean = false
)

class OrbitCaptureViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OrbitUiState())
    val uiState: StateFlow<OrbitUiState> = _uiState.asStateFlow()

    private var session: OrbitSession? = null
    private var lastCheckpointElapsedMs: Long = 0L

    /** Call once CameraX has started writing to [videoFilePath]. */
    fun onRecordingStarted(videoFilePath: String) {
        session = OrbitSession(videoFilePath = videoFilePath)
        lastCheckpointElapsedMs = 0L
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
                val sinceLastCheckpoint = elapsed - lastCheckpointElapsedMs
                val hint = when {
                    sinceLastCheckpoint > (TARGET_SECONDS_PER_CHECKPOINT * 1.4f * 1000) -> PaceHint.SPEED_UP
                    sinceLastCheckpoint < (TARGET_SECONDS_PER_CHECKPOINT * 0.6f * 1000) -> PaceHint.SLOW_DOWN
                    else -> PaceHint.ON_PACE
                }
                _uiState.value = state.copy(elapsedMs = elapsed, paceHint = hint)
                delay(100)
            }
        }
    }

    /**
     * Called when the user taps the checkpoint button (or, in Phase 3, when an
     * auto-advance heuristic fires). Logs the current video timestamp against
     * the current expected label and advances to the next one.
     */
    fun onCheckpointTapped(source: CheckpointSource = CheckpointSource.MANUAL_TAP) {
        val current = session ?: return
        val label = current.nextLabel ?: return
        val elapsed = SystemClock.elapsedRealtime() - _uiState.value.recordingStartMs

        current.checkpoints.add(
            Checkpoint(label = label, timestampMs = elapsed, source = source)
        )
        lastCheckpointElapsedMs = elapsed

        _uiState.value = _uiState.value.copy(
            currentLabel = current.nextLabel,
            completedLabels = current.checkpoints.map { it.label },
            paceHint = PaceHint.ON_PACE,
            isComplete = current.isComplete
        )
    }

    /** Allows the review screen to fix a mislabeled/mistimed checkpoint. */
    fun reassignCheckpoint(oldLabel: OrbitLabel, newTimestampMs: Long) {
        val checkpoint = session?.checkpoints?.find { it.label == oldLabel } ?: return
        val index = session!!.checkpoints.indexOf(checkpoint)
        session!!.checkpoints[index] = checkpoint.copy(timestampMs = newTimestampMs)
    }

    /** Stops the clock and returns the finished session for frame extraction. */
    fun onRecordingStopped(): OrbitSession? {
        _uiState.value = _uiState.value.copy(isRecording = false)
        return session
    }
}
