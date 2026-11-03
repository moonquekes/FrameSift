package cn.devcxl.photosync.activity

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import cn.devcxl.photosync.ptp.usbcamera.ObjectInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Adds SD-card selection to the existing viewer, reusing its USB session and original-JPEG writer. */
internal class CameraAlbumDialog(
    private val activity: ComponentActivity,
    private val controller: UsbPtpConnectionController,
    private val saveOriginal: (File, String) -> Uri?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val photos = mutableListOf<ObjectInfo>()
    private var remainingHandles: List<Int> = emptyList()
    private val selected = linkedSetOf<Int>()
    private val loadingThumbs = mutableSetOf<Int>()
    private val failedThumbs = mutableSetOf<Int>()
    private val thumbnails = object : LruCache<Int, Bitmap>(8 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }
    private val adapter = AlbumAdapter()
    private lateinit var dialog: AlertDialog
    private lateinit var status: TextView
    private var busy = false

    fun show() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_camera_album, null)
        status = view.findViewById(R.id.albumStatus)
        val list = view.findViewById<ListView>(R.id.albumList)
        list.layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.44).toInt()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (!busy) {
                val handle = photos[position].handle
                if (!selected.add(handle)) selected.remove(handle)
                adapter.notifyDataSetChanged()
                updateSelection()
            }
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.camera_album)
            .setView(view)
            .setPositiveButton(R.string.album_import, null)
            .setNeutralButton(R.string.album_refresh, null)
            .setNegativeButton(R.string.album_close, null)
            .create()
        dialog.setOnDismissListener { scope.cancel(); thumbnails.evictAll() }
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.9).toInt(), -2)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { importSelected() }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            loadAlbum(refresh = remainingHandles.isEmpty())
        }
        loadAlbum()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !value
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !value && selected.isNotEmpty()
    }

    private fun updateSelection() {
        status.text = activity.getString(R.string.album_count, photos.size, selected.size, remainingHandles.size)
        setBusy(busy)
        Timber.tag("USB_COMPAT").i("ALBUM_SELECTION count=%d", selected.size)
    }

    private fun loadAlbum(refresh: Boolean = true) {
        if (busy) return
        setBusy(true)
        status.setText(R.string.album_loading)
        scope.launch {
            try {
                if (refresh) {
                    remainingHandles = controller.withConnectedCamera { camera ->
                        val storages = camera.getStorageIds()
                        val handles = storages.flatMap { camera.getObjectHandles(it, 0, 0).toList() }.distinct()
                        val handlesHash = sha256(ByteArrayInputStream(handles.sorted().joinToString(",").toByteArray()))
                        Timber.tag("USB_COMPAT").i(
                            "ALBUM_SNAPSHOT storages=%d objects=%d handlesSha256=%s",
                            storages.size, handles.size, handlesHash
                        )
                        handles
                    }
                    photos.clear()
                    selected.clear()
                    failedThumbs.clear()
                }
                val page = remainingHandles.take(48)
                val entries = controller.withConnectedCamera { camera ->
                    page.map { camera.getObjectInfo(it) }.filter {
                        it.associationType == 0 &&
                            it.filename?.substringAfterLast('.')?.lowercase(Locale.ROOT) in setOf("jpg", "jpeg")
                    }.sortedBy { it.filename }
                }
                remainingHandles = remainingHandles.drop(page.size)
                photos.addAll(entries)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setText(
                    if (remainingHandles.isEmpty()) R.string.album_refresh else R.string.album_more
                )
                adapter.notifyDataSetChanged()
                updateSelection()
                Timber.tag("USB_COMPAT").i(
                    "ALBUM_PAGE scanned=%d jpegLoaded=%d remaining=%d", page.size, photos.size, remainingHandles.size
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("USB_COMPAT").e(e, "ALBUM_READ_FAILED")
                status.setText(R.string.album_read_failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun requestThumbnail(photo: ObjectInfo) {
        if (photo.handle in failedThumbs || !loadingThumbs.add(photo.handle)) return
        scope.launch {
            try {
                val bitmap = controller.withConnectedCamera { camera ->
                    val bytes = camera.getThumb(photo.handle)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("GetThumb did not return a decodable image")
                }
                thumbnails.put(photo.handle, bitmap)
                adapter.notifyDataSetChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedThumbs.add(photo.handle)
                Timber.tag("USB_COMPAT").w(e, "THUMBNAIL_FAILED handle=0x%08x", photo.handle)
            } finally {
                loadingThumbs.remove(photo.handle)
            }
        }
    }

    private fun importSelected() {
        if (busy || selected.isEmpty()) return
        val batch = photos.filter { it.handle in selected }
        setBusy(true)
        Timber.tag("USB_COMPAT").i("ALBUM_IMPORT_BEGIN selected=%d", batch.size)
        scope.launch {
            var success = 0
            var failed = 0
            try {
                for ((index, photo) in batch.withIndex()) {
                    ensureActive()
                    status.text = activity.getString(R.string.album_importing, index + 1, batch.size)
                    try {
                        importOriginal(photo)
                        selected.remove(photo.handle)
                        success++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        Timber.tag("USB_COMPAT").e(e, "JPEG_IMPORT_FAILED handle=0x%08x", photo.handle)
                    }
                }
                adapter.notifyDataSetChanged()
                status.text = activity.getString(R.string.album_import_done, success, failed)
                Timber.tag("USB_COMPAT").i("ALBUM_IMPORT_END success=%d failed=%d", success, failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun importOriginal(photo: ObjectInfo) {
        val file = controller.withConnectedCamera { camera ->
            val current = camera.getObjectInfo(photo.handle)
            val directory = File(activity.externalCacheDir ?: activity.cacheDir, "album-${System.nanoTime()}")
            val name = current.filename?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                ?.takeIf { it.isNotBlank() } ?: "${photo.handle}.jpg"
            val destination = File(directory, name)
            if (!camera.importFile(photo.handle, destination.absolutePath)) throw IOException("GetObject failed")
            val expected = current.objectCompressedSize.toLong() and 0xffffffffL
            if (expected != destination.length()) throw IOException("Original JPEG byte count mismatch")
            destination
        }
        withContext(Dispatchers.IO) {
            val originalHash = file.inputStream().use(::sha256)
            val uri = saveOriginal(file, file.name) ?: throw IOException("Gallery copy failed")
            val galleryHash = activity.contentResolver.openInputStream(uri)?.use(::sha256)
            if (originalHash != galleryHash) {
                // This is only the newly created phone copy, never an object on the camera.
                activity.contentResolver.delete(uri, null, null)
                throw IOException("Gallery JPEG checksum mismatch")
            }
            Timber.tag("USB_COMPAT").i(
                "JPEG_VERIFIED handle=0x%08x bytes=%d sourceSha256=%s gallerySha256=%s equal=true",
                photo.handle, file.length(), originalHash, galleryHash
            )
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inner class AlbumAdapter : BaseAdapter() {
        override fun getCount(): Int = photos.size
        override fun getItem(position: Int): ObjectInfo = photos[position]
        override fun getItemId(position: Int): Long = photos[position].handle.toLong() and 0xffffffffL
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: activity.layoutInflater.inflate(R.layout.item_camera_album, parent, false)
            val photo = photos[position]
            val checkbox = row.findViewById<CheckBox>(R.id.albumCheckbox)
            checkbox.isChecked = photo.handle in selected
            checkbox.text = activity.getString(
                R.string.album_item, photo.filename, photo.imagePixWidth, photo.imagePixHeight,
                "%.2f".format(Locale.ROOT, (photo.objectCompressedSize.toLong() and 0xffffffffL) / 1_000_000.0)
            )
            val image = row.findViewById<ImageView>(R.id.albumThumbnail)
            val thumbnail = thumbnails.get(photo.handle)
            if (thumbnail != null) image.setImageBitmap(thumbnail) else {
                image.setImageResource(android.R.drawable.ic_menu_gallery)
                requestThumbnail(photo)
            }
            return row
        }
    }
}
