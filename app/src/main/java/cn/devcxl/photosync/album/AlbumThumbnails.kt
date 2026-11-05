package cn.devcxl.photosync.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import cn.devcxl.photosync.ptp.manager.CameraReadPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import timber.log.Timber

/** Cancel offscreen work while safely finishing an already-started camera transaction. */
internal class AlbumThumbnails(
    context: Context, private val controller: UsbPtpConnectionController, private val scope: CoroutineScope,
    private val files: MassStorageFiles = MassStorageFiles(context, controller)
) {
    private val directory = File(context.cacheDir, "camera-thumbnails-v1").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private data class Request(val result: Deferred<Bitmap>, var users: Int = 0)
    private val pending = mutableMapOf<String, Request>()
    private val cameraReads = Semaphore(1)

    fun cached(photo: CameraPhoto): Bitmap? = memory.get(photo.identity)

    suspend fun get(photo: CameraPhoto): Bitmap = withContext(Dispatchers.Main.immediate) {
        memory.get(photo.identity) ?: run {
            val request = pending.getOrPut(photo.identity) {
                Request(scope.async(Dispatchers.IO) { load(photo) })
            }
            request.users++
            try { request.result.await() } finally {
                request.users--
                if (request.users == 0) {
                    if (!request.result.isCompleted) request.result.cancel()
                    if (pending[photo.identity] === request) pending.remove(photo.identity)
                }
            }
        }
    }

    private suspend fun load(photo: CameraPhoto): Bitmap {
        val started = System.nanoTime()
        val key = MessageDigest.getInstance("SHA-256").digest(photo.identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.jpg")
        val direction = File(directory, "$key.orientation")
        val cachedOrientation = readOrientation(direction)
        val disk = file.exists() && cachedOrientation != null
        val (bytes, orientation) = if (disk) file.readBytes() to requireNotNull(cachedOrientation)
        else if (photo.documentUri != null) files.thumbnail(photo).also {
            file.writeBytes(it.first); direction.writeText(it.second.toString())
        }
        else cameraReads.withPermit {
            // Only one visible thumbnail enters the camera queue; raw bytes + orientation stay together.
            controller.withConnectedCamera(CameraReadPriority.VISIBLE_THUMBNAIL, "thumb:${photo.identity}") { camera ->
                val bytes = if (file.exists()) file.readBytes() else
                    camera.getThumb(photo.handle).also { file.writeBytes(it) }
                val orientation = readOrientation(direction) ?: run {
                    val original = JpegOrientation.read(camera.getObjectHeader(photo.handle))
                        ?: JpegOrientation.read(camera.getObjectHeader(photo.handle, 4096))
                    val value = original ?: JpegOrientation.read(bytes) ?: 1
                    direction.writeText(value.toString())
                    Timber.tag("USB_COMPAT").i("THUMB_ORIENTATION handle=0x%08x orientation=%d originalHeader=%b",
                        photo.handle, value, original != null)
                    value
                }
                bytes to orientation
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Thumbnail is not decodable")
        return orient(bitmap, orientation).also {
            memory.put(photo.identity, it)
            Timber.tag("USB_COMPAT").i("THUMB_READY handle=0x%08x source=%s elapsedMs=%d",
                photo.handle, if (disk) "disk" else if (photo.documentUri != null) "file" else "camera",
                (System.nanoTime() - started) / 1_000_000)
        }
    }

    private fun readOrientation(file: File): Int? =
        if (file.exists()) file.readText().toIntOrNull()?.takeIf { it in 1..8 } else null

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == 1) return bitmap
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.postRotate(90f)
            7 -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.postRotate(270f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun clearMemory() { memory.evictAll() }
}
