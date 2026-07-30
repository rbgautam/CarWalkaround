package com.example.carwalkaround

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Identity for one vehicle's capture run, established by the VIN photo taken on
 * the very first screen. Every later capture — detail shots and the orbit video
 * alike — carries this, which is what makes a pile of JPEGs and an MP4 an
 * inspection *of a specific car* rather than a pile of anonymous media.
 *
 * The tag is created at the moment the VIN photo is written, not when the user
 * finishes typing: [vin] is filled in afterwards (and may stay null, since the
 * photo is the record of truth and the typed string is a convenience for
 * humans reading a file listing). That ordering is why [tagId] is derived from
 * [capturedAt] rather than from the VIN itself — the VIN is not known yet, and
 * a typo'd VIN must not be able to collide two different cars into one folder.
 *
 * @param tagId namespaces this vehicle's media on disk. See [VehicleStorage].
 * @param vinPhotoPath the VIN plate photo — the authoritative record.
 * @param vin optionally typed by the user to make the tag human-readable.
 */
data class VehicleTag(
    val tagId: String,
    val vinPhotoPath: String,
    val capturedAt: Long,
    val vin: String? = null
) {
    /** What the capture screens show in their "tagged to" header. */
    val displayVin: String
        get() = vin?.takeIf { it.isNotBlank() } ?: "VIN photo only"

    /** Short form for tight spaces — the last 8 characters are the serial part. */
    val shortVin: String
        get() = vin?.takeIf { it.length >= 8 }?.takeLast(8) ?: displayVin
}

/**
 * Normalizes a hand-typed VIN: VIN characters are uppercase alphanumerics, and
 * users type them off a plate in groups with stray spaces or dashes.
 */
fun normalizeVin(raw: String): String =
    raw.uppercase().filter { it.isLetterOrDigit() }

/**
 * True if [vin] looks like a real VIN. Advisory only — the UI warns but never
 * blocks, because a rejected VIN would strand the user on the first screen with
 * a perfectly good photo they cannot proceed past. Real plates are damaged,
 * misread, and occasionally non-standard on older or imported vehicles.
 *
 * I, O and Q are excluded from the VIN alphabet precisely so they cannot be
 * confused with 1 and 0 — so their presence means a misread, not a rare VIN.
 */
fun looksLikeVin(vin: String): Boolean =
    vin.length == 17 && vin.none { it in VIN_EXCLUDED_LETTERS }

/** Letters a VIN never contains; their presence is a misread of 1 or 0. */
const val VIN_EXCLUDED_LETTERS = "IOQ"

/**
 * Transliteration values used by the ISO 3779 check digit. Note the deliberate
 * absence of I, O and Q, and that letters wrap (J restarts at 1) rather than
 * continuing A=1..Z=26.
 */
private val VIN_TRANSLITERATION: Map<Char, Int> = buildMap {
    "ABCDEFGH".forEachIndexed { i, c -> put(c, i + 1) }
    "JKLMN".forEachIndexed { i, c -> put(c, i + 1) }
    put('P', 7); put('R', 9)
    "STUVWXYZ".forEachIndexed { i, c -> put(c, i + 2) }
    ('0'..'9').forEach { put(it, it - '0') }
}

/** Positional weights; index 8 is the check digit itself and so weighs 0. */
private val VIN_WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

/**
 * Validates the 9th-character check digit (ISO 3779).
 *
 * This is the single most useful signal available for grading an OCR result: a
 * misread character almost always breaks the checksum, so a VIN that passes is
 * very unlikely to be wrong, and the app can tell the user which of those two
 * situations they are in instead of asking them to proof-read 17 characters.
 *
 * A false result is *not* proof of a bad read. The check digit is mandatory in
 * North America but optional elsewhere, so plenty of legitimately-transcribed
 * imported vehicles fail it. Treated as confidence, never as a gate.
 */
fun vinCheckDigitValid(vin: String): Boolean {
    if (!looksLikeVin(vin)) return false

    var sum = 0
    vin.forEachIndexed { index, char ->
        val value = VIN_TRANSLITERATION[char] ?: return false
        sum += value * VIN_WEIGHTS[index]
    }

    val expected = when (val remainder = sum % 11) {
        10 -> 'X'
        else -> '0' + remainder
    }
    return vin[8] == expected
}

/**
 * Where every captured file for a vehicle lives.
 *
 * The layout is the tag, expressed on disk:
 *
 *     captures/<tagId>/
 *         vin.jpg                     <- the tag's own photo
 *         vehicle_tag.json            <- tagId, VIN, capture time
 *         details/<LABEL>_<millis>.jpg
 *         orbit_<millis>.mp4
 *         frames/<sessionId>/<LABEL>.jpg
 *
 * Grouping by directory rather than only in memory means the association
 * survives the process. The in-memory [VehicleTag] is lost on cold start; a
 * folder containing a VIN photo and the media shot against it is not.
 */
object VehicleStorage {

    /**
     * App-private external storage, falling back to internal when no external
     * volume is mounted — `getExternalFilesDir` is nullable for exactly that
     * case, and returning null here would mean losing a capture rather than
     * writing it somewhere less convenient.
     */
    fun captureRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "captures")

    fun newTagId(capturedAt: Long): String = "vehicle_$capturedAt"

    fun tagDir(context: Context, tagId: String): File =
        File(captureRoot(context), tagId).also { if (!it.exists()) it.mkdirs() }

    /**
     * VIN photo destination. Timestamped for the same reason detail shots are:
     * retaking the VIN before confirming must produce a new path, or the
     * confirm screen keeps showing the thumbnail it already decoded for the
     * old one. The tag directory itself stays put across retakes, so a
     * re-shoot does not strand an orphaned folder.
     */
    fun vinPhotoFile(context: Context, tagId: String, capturedAt: Long): File =
        File(tagDir(context, tagId), "vin_$capturedAt.jpg")

    /**
     * Detail-shot destination. The [capturedAt] suffix means a retake writes a
     * *new* file rather than overwriting the one it replaces: the review screen
     * keys its decoded thumbnail on the path, so a reused path would leave the
     * stale image on screen. The superseded file is deleted by
     * [DetailShotViewModel.onShotCaptured].
     */
    fun detailShotFile(
        context: Context,
        tagId: String,
        label: DetailLabel,
        capturedAt: Long
    ): File {
        val dir = File(tagDir(context, tagId), "details").also {
            if (!it.exists()) it.mkdirs()
        }
        return File(dir, "${label.name}_$capturedAt.jpg")
    }

    fun orbitVideoFile(context: Context, tagId: String, startedAt: Long): File =
        File(tagDir(context, tagId), "orbit_$startedAt.mp4")

    /** Extracted orbit stills, namespaced per recording within the vehicle. */
    fun framesDir(context: Context, tagId: String, sessionId: String): File =
        File(File(tagDir(context, tagId), "frames"), sessionId)

    /**
     * Writes the tag beside the media it labels, so the folder is
     * self-describing to anything that later picks it up — an upload job, a
     * desktop file browser, or a future import path. Best-effort: a sidecar
     * that fails to write must not abort a capture the user just completed,
     * since the photo and the directory grouping already carry the association.
     */
    fun writeSidecar(context: Context, tag: VehicleTag) {
        runCatching {
            val json = JSONObject()
                .put("tagId", tag.tagId)
                .put("vin", tag.vin ?: JSONObject.NULL)
                .put("capturedAt", tag.capturedAt)
                .put("vinPhoto", File(tag.vinPhotoPath).name)
            File(tagDir(context, tag.tagId), "vehicle_tag.json")
                .writeText(json.toString(2))
        }
    }
}