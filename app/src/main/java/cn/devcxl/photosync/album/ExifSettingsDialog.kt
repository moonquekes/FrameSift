package cn.devcxl.photosync.album

import androidx.activity.ComponentActivity
import cn.devcxl.photosync.R

internal class ExifSettingsDialog(private val activity: ComponentActivity, private val preferences: AlbumPreferences) {
    fun show(changed: () -> Unit = {}) {
        AlbumSettingsMenu(activity, preferences).show(initialPage = 1, exifChanged = changed)
    }
}

internal fun exifLabel(field: ExifField): Int = when (field) {
    ExifField.FOCAL_LENGTH -> R.string.gallery_exif_focal
    ExifField.LENS_MODEL -> R.string.gallery_exif_lens
    ExifField.ISO -> R.string.gallery_exif_iso
    ExifField.APERTURE -> R.string.gallery_exif_aperture
    ExifField.SHUTTER -> R.string.gallery_exif_shutter
    ExifField.CAMERA -> R.string.gallery_exif_camera
    ExifField.CAPTURE_TIME -> R.string.gallery_exif_date
    ExifField.EXPOSURE_BIAS -> R.string.gallery_exif_bias
    ExifField.EQUIVALENT_FOCAL_LENGTH -> R.string.gallery_exif_equivalent
}
