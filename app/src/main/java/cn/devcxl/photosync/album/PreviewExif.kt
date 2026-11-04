package cn.devcxl.photosync.album

import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import cn.devcxl.photosync.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import timber.log.Timber

internal class PreviewExif(private val activity: ComponentActivity, root: View) {
    private val preferences = AlbumPreferences(activity)
    private val text = root.findViewById<TextView>(R.id.previewExif)
    private val toggle = root.findViewById<TextView>(R.id.previewExifToggle)
    private var values: Map<ExifField, String>? = null
    var requestData: (() -> Unit)? = null
    val needsData: Boolean get() = values == null && preferences.exifEnabled && preferences.exifFields.isNotEmpty()

    init {
        toggle.setOnClickListener {
            preferences.exifEnabled = !preferences.exifEnabled; render()
            if (needsData) requestData?.invoke()
        }
        toggle.setOnLongClickListener {
            ExifSettingsDialog(activity, preferences).show { render(); if (needsData) requestData?.invoke() }
            true
        }
    }

    fun clear() { values = null; render() }

    suspend fun load(file: File, handle: Int) {
        values = withContext(Dispatchers.IO) {
            try { PhotoExif.read(file) }
            catch (e: IOException) {
                Timber.tag("USB_COMPAT").w("EXIF_READ_FAILED handle=0x%08x type=%s", handle, e.javaClass.simpleName)
                emptyMap()
            }
        }
        Timber.tag("USB_COMPAT").i("EXIF_LOADED handle=0x%08x fields=%d", handle, values?.size ?: 0)
        render()
    }

    private fun render() {
        toggle.isSelected = preferences.exifEnabled
        val fields = preferences.exifFields
        val data = values
        text.visibility = if (preferences.exifEnabled && data != null && fields.isNotEmpty()) View.VISIBLE else View.GONE
        if (data == null) return
        fun item(field: ExifField) = activity.getString(R.string.gallery_exif_item, activity.getString(exifLabel(field)),
            data[field] ?: activity.getString(R.string.gallery_exif_missing))
        val lines = mutableListOf<String>()
        if (ExifField.LENS_MODEL in fields) lines.add(item(ExifField.LENS_MODEL))
        val other = ExifField.entries.filter { it in fields && it != ExifField.LENS_MODEL }
        if (other.isNotEmpty()) lines.add(other.joinToString(activity.getString(R.string.gallery_exif_separator), transform = ::item))
        text.text = lines.joinToString("\n")
    }
}
