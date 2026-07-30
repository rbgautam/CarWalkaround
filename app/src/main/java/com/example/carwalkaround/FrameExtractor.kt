package com.example.carwalkaround

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Pulls one still frame per checkpoint out of the finished orbit video and
 * writes it to disk as a labeled JPEG. Pure lookup — no classification.
 */
object FrameExtractor {

    /**
     * @param session the finished OrbitSession, with checkpoints already logged
     *                (label + timestampMs) during recording. The video file must
     *                already be finalized — see VideoRecordEvent.Finalize.
     * @param outputDir directory to write extracted frame JPEGs into. Must be
     *                  session-scoped (see [OrbitSession.sessionId]); frame file
     *                  names are label-derived and collide across sessions.
     * @return a *new* session whose checkpoints carry their frameUri. Returning
     *         a new instance (rather than mutating the one passed in) is what
     *         lets Compose see the change — an identical reference would be
     *         skipped by snapshot equality even though its contents differ.
     */
    suspend fun extractCheckpointFrames(
        session: OrbitSession,
        outputDir: File
    ): OrbitSession = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val retriever = MediaMetadataRetriever()
        val extracted = try {
            retriever.setDataSource(session.videoFilePath)

            session.checkpoints.map { checkpoint ->
                checkpoint.copy(
                    frameUri = extractSingleFrame(
                        retriever = retriever,
                        timestampMs = checkpoint.timestampMs,
                        label = checkpoint.label,
                        outputDir = outputDir
                    )
                )
            }
        } finally {
            retriever.release()
        }

        session.copy(checkpoints = extracted)
    }

    /**
     * Extracts the frame closest to [timestampMs]. Uses OPTION_CLOSEST (rather
     * than OPTION_CLOSEST_SYNC, which snaps to the nearest keyframe) so the
     * frame actually matches the moment the user tapped, not the nearest
     * I-frame — important since checkpoints can land anywhere in the GOP.
     */
    private fun extractSingleFrame(
        retriever: MediaMetadataRetriever,
        timestampMs: Long,
        label: OrbitLabel,
        outputDir: File
    ): String? {
        val timestampUs = timestampMs * 1000L

        val bitmap: Bitmap? = retriever.getFrameAtTime(
            timestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST
        )

        if (bitmap == null) return null

        val fileName = "${label.name}.jpg"
        val outFile = File(outputDir, fileName)

        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bitmap.recycle()

        return outFile.absolutePath
    }

    /**
     * Optional helper for the review screen: extract a small burst of frames
     * around a checkpoint (e.g. +/- 300ms) so the user can pick the sharpest
     * one if the tap-moment frame is blurry/motion-affected.
     */
    suspend fun extractBurst(
        context: Context,
        videoFilePath: String,
        centerTimestampMs: Long,
        windowMs: Long = 300L,
        stepMs: Long = 100L,
        outputDir: File
    ): List<String> = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) outputDir.mkdirs()
        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<String>()
        try {
            retriever.setDataSource(videoFilePath)
            var t = centerTimestampMs - windowMs
            while (t <= centerTimestampMs + windowMs) {
                val bitmap = retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) {
                    val outFile = File(outputDir, "burst_${t}.jpg")
                    FileOutputStream(outFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    bitmap.recycle()
                    results.add(outFile.absolutePath)
                }
                t += stepMs
            }
        } finally {
            retriever.release()
        }
        results
    }
}
