package cn.devcxl.photosync.album

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class ExifField {
    FOCAL_LENGTH, LENS_MODEL, ISO, APERTURE, SHUTTER, CAMERA, CAPTURE_TIME, EXPOSURE_BIAS, EQUIVALENT_FOCAL_LENGTH;

    companion object {
        val defaults = setOf(FOCAL_LENGTH, LENS_MODEL, ISO, APERTURE, SHUTTER)
    }
}

internal fun exifNumber(value: Double, decimals: Int = 3): String? {
    if (!value.isFinite()) return null
    return String.format(Locale.ROOT, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
}

internal fun exifShutter(seconds: Double): String? {
    if (!seconds.isFinite() || seconds <= 0) return null
    if (seconds < 1) {
        val reciprocal = 1 / seconds
        if (reciprocal <= Int.MAX_VALUE) {
            val denominator = reciprocal.roundToInt()
            if (abs(reciprocal - denominator) / reciprocal < 0.01) return "1/$denominator s"
        }
    }
    return exifNumber(seconds)?.let { "$it s" }
}
