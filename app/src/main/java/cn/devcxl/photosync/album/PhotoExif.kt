package cn.devcxl.photosync.album

import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Reads metadata from the already-downloaded JPEG; never modifies it or issues another camera command. */
internal object PhotoExif {
    fun read(file: File): Map<ExifField, String> {
        val exif = ExifInterface(file)
        val values = mutableMapOf<ExifField, String>()
        fun text(field: ExifField, tag: String) {
            exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { values[field] = it }
        }
        fun number(field: ExifField, tag: String, suffix: String = "", prefix: String = "") {
            val value = exif.getAttributeDouble(tag, Double.NaN)
            if (value > 0 && value.isFinite()) exifNumber(value)?.let { values[field] = "$prefix$it$suffix" }
        }
        number(ExifField.FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH, " mm")
        text(ExifField.LENS_MODEL, ExifInterface.TAG_LENS_MODEL)
        text(ExifField.ISO, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        number(ExifField.APERTURE, ExifInterface.TAG_F_NUMBER, prefix = "f/")
        exifShutter(exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN))
            ?.let { values[ExifField.SHUTTER] = it }
        text(ExifField.CAMERA, ExifInterface.TAG_MODEL)
        text(ExifField.CAPTURE_TIME, ExifInterface.TAG_DATETIME_ORIGINAL)
        val bias = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)
        exifNumber(bias, 2)?.let { values[ExifField.EXPOSURE_BIAS] = (if (bias > 0) "+" else "") + it + " EV" }
        number(ExifField.EQUIVALENT_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, " mm")
        return values
    }
}
