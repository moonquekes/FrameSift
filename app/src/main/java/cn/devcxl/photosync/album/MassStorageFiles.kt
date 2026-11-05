package cn.devcxl.photosync.album

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import cn.devcxl.photosync.ptp.manager.CameraReadPriority
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import timber.log.Timber

internal data class FilePreview(val file: File, val orientation: Int, val width: Int, val height: Int)

/** Every source descriptor is opened with mode "r"; only app cache files are writable. */
internal class MassStorageFiles(private val context: Context, private val controller: UsbPtpConnectionController) {
    private val headers = object : LruCache<String, ByteArray>(16 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = (value.size / 1024).coerceAtLeast(1)
    }

    fun header(photo: CameraPhoto): ByteArray {
        val key = "${photo.documentUri}:${photo.bytes}:${photo.modifiedMillis}"
        return headers.get(key) ?: readRange(photo, 0, minOf(128 * 1024L, photo.bytes).toInt())
            .also { headers.put(key, it) }
    }

    fun clearHeaders() { headers.evictAll() }

    suspend fun thumbnail(photo: CameraPhoto): Pair<ByteArray, Int> = controller.withFileRead(
        CameraReadPriority.VISIBLE_THUMBNAIL, "file-thumb:${photo.identity}"
    ) {
        val header = header(photo)
        val exif = ExifInterface(ByteArrayInputStream(header))
        val bytes = exif.thumbnailBytes ?: throw IOException("JPEG has no embedded thumbnail")
        bytes to (JpegOrientation.read(header) ?: 1)
    }

    suspend fun metadata(photo: CameraPhoto): ByteArray = controller.withFileRead(
        CameraReadPriority.CURRENT_PREVIEW, "file-exif:${photo.identity}"
    ) { header(photo) }

    suspend fun preview(photo: CameraPhoto, destination: File, priority: CameraReadPriority): FilePreview =
        controller.withFileRead(priority, "file-preview:${photo.identity}") {
            val start = System.nanoTime()
            val header = header(photo)
            val headerDone = System.nanoTime()
            val range = JpegMpf.read(header, photo.bytes) ?: throw IOException("JPEG has no supported MPF preview")
            val disk = destination.exists() && destination.length() == range.bytes.toLong()
            if (!disk) {
                val bytes = readRange(photo, range.offset, range.bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid MPF JPEG")
                val temporary = File(destination.parentFile, destination.name + ".part")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(destination)) throw IOException("Could not commit preview cache")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destination.path, bounds)
            destination.setLastModified(System.currentTimeMillis())
            Timber.tag("USB_COMPAT").i("FILE_PREVIEW bytes=%d width=%d height=%d cached=%b headerUs=%d elapsedMs=%d",
                range.bytes, bounds.outWidth, bounds.outHeight, disk, (headerDone - start) / 1000,
                (System.nanoTime() - start) / 1_000_000)
            FilePreview(destination, JpegOrientation.read(header) ?: 1, bounds.outWidth, bounds.outHeight)
        }

    suspend fun original(photo: CameraPhoto, destination: File, progress: (Long, Long) -> Unit) =
        controller.withFileRead(CameraReadPriority.CURRENT_ORIGINAL, "file-original:${photo.identity}") {
            if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw IOException("Original EXIF permission is required for byte-exact JPEG import")
            }
            val started = System.nanoTime()
            Timber.tag("USB_COMPAT").i("FILE_ORIGINAL_BEGIN handle=0x%08x bytes=%d", photo.handle, photo.bytes)
            val temporary = File(destination.parentFile, destination.name + ".part")
            var readNanos = 0L
            var writeNanos = 0L
            val openStarted = System.nanoTime()
            var openNanos = 0L
            open(photo).use { input ->
                openNanos = System.nanoTime() - openStarted
                checkSize(input, photo)
                FileOutputStream(temporary).buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val readStarted = System.nanoTime()
                        val count = input.read(buffer)
                        readNanos += System.nanoTime() - readStarted
                        if (count < 0) break
                        val writeStarted = System.nanoTime()
                        output.write(buffer, 0, count)
                        writeNanos += System.nanoTime() - writeStarted
                        done += count
                        if (System.nanoTime() - lastUpdate > 30_000_000 || done == photo.bytes) {
                            progress(photo.bytes, done); lastUpdate = System.nanoTime()
                            Timber.tag("USB_COMPAT").i("FILE_ORIGINAL_PROGRESS handle=0x%08x done=%d total=%d",
                                photo.handle, done, photo.bytes)
                        }
                    }
                    if (done != photo.bytes) throw IOException("Source JPEG byte count changed")
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("Could not commit original cache")
            Timber.tag("USB_COMPAT").i("FILE_ORIGINAL bytes=%d openUs=%d readUs=%d writeUs=%d elapsedMs=%d",
                photo.bytes, openNanos / 1000, readNanos / 1000, writeNanos / 1000,
                (System.nanoTime() - started) / 1_000_000)
        }

    private fun readRange(photo: CameraPhoto, offset: Long, count: Int): ByteArray {
        val started = System.nanoTime()
        val input = open(photo)
        val opened = System.nanoTime()
        return input.use {
            checkSize(input, photo)
            if (offset < 0 || count < 0 || offset + count > photo.bytes) throw IOException("Invalid JPEG range")
            input.channel.position(offset)
            val bytes = ByteArray(count)
            val readStarted = System.nanoTime()
            var position = 0
            while (position < count) {
                val read = input.read(bytes, position, count - position)
                if (read < 0) throw IOException("JPEG range ended early")
                position += read
            }
            if (offset > 0) Timber.tag("USB_COMPAT").i("FILE_RANGE bytes=%d openUs=%d readUs=%d elapsedUs=%d",
                count, (opened - started) / 1000, (System.nanoTime() - readStarted) / 1000,
                (System.nanoTime() - started) / 1000)
            bytes
        }
    }

    private fun open(photo: CameraPhoto): ParcelFileDescriptor.AutoCloseInputStream =
        ParcelFileDescriptor.AutoCloseInputStream(context.contentResolver.openFileDescriptor(
            Uri.parse(requireNotNull(photo.documentUri)), "r"
        ) ?: throw IOException("Camera file is unavailable"))

    private fun checkSize(input: ParcelFileDescriptor.AutoCloseInputStream, photo: CameraPhoto) {
        if (input.channel.size() != photo.bytes) throw IOException("Camera file changed; refresh the album")
    }
}
