package com.example.carwalkaround

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How confident the app is in a scanned VIN, which is what decides whether the
 * UI says "confirm this" or "type it yourself".
 */
enum class VinConfidence {
    /** 17 valid characters *and* the ISO 3779 check digit agrees. */
    CHECKSUM_VALID,

    /** Shaped like a VIN, but the check digit does not agree. Needs a human. */
    UNVERIFIED,

    /** Nothing VIN-shaped in the image. */
    NOT_FOUND
}

/**
 * @param vin the best candidate, or null when [confidence] is NOT_FOUND.
 * @param error set when recognition itself failed (unreadable file, ML Kit
 *        error). Distinct from NOT_FOUND, which means OCR worked fine and the
 *        image simply had no VIN in it — the two want different UI copy.
 */
data class VinScanResult(
    val vin: String?,
    val confidence: VinConfidence,
    val error: String? = null
)

/**
 * Reads a VIN off the captured plate photo with on-device ML Kit text
 * recognition.
 *
 * This is an *assist*, not an authority. The result pre-fills the VIN field and
 * the user is always free to correct or replace it, for a reason specific to
 * this domain: a VIN is a high-consequence identifier with no redundancy in the
 * surrounding text to recover from, so a plausible-but-wrong scan that the user
 * waves through mislabels the entire capture. Everything below is therefore
 * built to make a doubtful read *look* doubtful rather than to maximise the
 * number of fields auto-filled.
 *
 * Note this is unrelated to the plan's Phase 4 ML work: that is image
 * *classification* of walkaround frames, needing a labelled dataset. Reading
 * characters off a plate is a solved, self-contained problem with a checksum to
 * grade it, which is why it can land now.
 */
object VinOcr {

    /**
     * Recognizes text in [photoPath] and returns the best VIN candidate.
     *
     * Runs against the full-resolution capture rather than a downscaled copy —
     * VIN characters are small, and the sampling that makes a thumbnail cheap
     * is exactly what destroys the strokes that distinguish 8 from B.
     */
    suspend fun scan(context: Context, photoPath: String): VinScanResult =
        withContext(Dispatchers.IO) {
            val file = File(photoPath)
            if (!file.exists()) {
                return@withContext VinScanResult(
                    vin = null,
                    confidence = VinConfidence.NOT_FOUND,
                    error = "Photo could not be read."
                )
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                // fromFilePath honours the JPEG's EXIF orientation; decoding the
                // file by hand and passing a bitmap would hand ML Kit a
                // sideways plate on most phones.
                val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                val text = recognizer.process(image).await()

                bestCandidate(text.textBlocks.flatMap { block -> block.lines.map { it.text } })
            } catch (e: Exception) {
                VinScanResult(
                    vin = null,
                    confidence = VinConfidence.NOT_FOUND,
                    error = "Could not scan the VIN: ${e.message ?: e::class.java.simpleName}"
                )
            } finally {
                // The recognizer holds a native detector; leaking one per photo
                // would accumulate across retakes.
                recognizer.close()
            }
        }

    /**
     * Picks the most trustworthy VIN-shaped run out of the recognized lines.
     *
     * Plates carry more than the VIN — GVWR figures, paint codes, barcodes
     * rendered as digits — so this looks at every 17-character window of every
     * line and lets the checksum arbitrate, rather than assuming the longest or
     * topmost run is the VIN. A checksum-valid candidate always wins; only if
     * none exists does the first merely-VIN-shaped run get returned, flagged
     * UNVERIFIED so the UI can ask the user to check it.
     */
    internal fun bestCandidate(lines: List<String>): VinScanResult {
        var fallback: String? = null

        for (line in lines) {
            // A VIN is often printed with spaces or a barcode caption running
            // into it, so punctuation is stripped before windowing rather than
            // the line being rejected for containing it.
            val normalized = normalizeVin(line).let(::repairAmbiguousCharacters)
            if (normalized.length < VIN_LENGTH) continue

            for (start in 0..(normalized.length - VIN_LENGTH)) {
                val candidate = normalized.substring(start, start + VIN_LENGTH)
                if (!looksLikeVin(candidate)) continue

                if (vinCheckDigitValid(candidate)) {
                    return VinScanResult(candidate, VinConfidence.CHECKSUM_VALID)
                }
                if (fallback == null) fallback = candidate
            }
        }

        return if (fallback != null) {
            VinScanResult(fallback, VinConfidence.UNVERIFIED)
        } else {
            VinScanResult(null, VinConfidence.NOT_FOUND)
        }
    }

    /**
     * Rewrites the three characters a VIN cannot contain to the digits they are
     * always a misread of.
     *
     * This is safe in a way that the other classic OCR confusions (8/B, 5/S,
     * 2/Z) are not: those are ambiguous *both ways* in a real VIN, so
     * "correcting" them would invent characters. I, O and Q are excluded from
     * the VIN alphabet by design, precisely so that seeing one means the reader
     * erred — which makes the substitution a decoding step, not a guess.
     */
    private fun repairAmbiguousCharacters(text: String): String =
        text.map { char ->
            when (char) {
                'I' -> '1'
                'O', 'Q' -> '0'
                else -> char
            }
        }.joinToString("")

    private const val VIN_LENGTH = 17
}
