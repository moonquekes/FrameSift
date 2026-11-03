package cn.devcxl.photosync.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/** Small camera thumbnails are cached independently of full original JPEG downloads. */
internal class AlbumThumbnails(
    context: Context, private val controller: UsbPtpConnectionController, private val scope: CoroutineScope
) {
    private val directory = File(context.cacheDir, "camera-thumbnails-v1").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val pending = mutableMapOf<String, Deferred<Bitmap>>()

    fun cached(photo: CameraPhoto): Bitmap? = memory.get(photo.identity)

    suspend fun get(photo: CameraPhoto): Bitmap = withContext(Dispatchers.Main.immediate) {
        memory.get(photo.identity) ?: run {
            val request = pending.getOrPut(photo.identity) {
                scope.async(Dispatchers.IO) {
                    val key = MessageDigest.getInstance("SHA-256").digest(photo.identity.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    val file = File(directory, "$key.jpg")
                    val bytes = if (file.exists()) file.readBytes() else
                        controller.withConnectedCamera { it.getThumb(photo.handle) }.also { file.writeBytes(it) }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw java.io.IOException("Thumbnail is not decodable")
                    val orientationFile = File(directory, "$key.orientation")
                    val orientation = if (orientationFile.exists()) orientationFile.readText().toIntOrNull() ?: 1 else {
                        val originalOrientation = runCatching {
                            controller.withConnectedCamera { camera ->
                                JpegOrientation.read(camera.getObjectHeader(photo.handle))
                                    ?: JpegOrientation.read(camera.getObjectHeader(photo.handle, 4096))
                            }
                        }.getOrElse { if (it is CancellationException) throw it else null }
                        val value = originalOrientation ?: runCatching {
                            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                        }.getOrDefault(1)
                        if (originalOrientation != null) orientationFile.writeText(value.toString())
                        Timber.tag("USB_COMPAT").i(
                            "THUMB_ORIENTATION handle=0x%08x orientation=%d originalHeader=%b",
                            photo.handle, value, originalOrientation != null
                        )
                        value
                    }
                    orient(bitmap, orientation).also { memory.put(photo.identity, it) }
                }
            }
            try { request.await() } finally { pending.remove(photo.identity) }
        }
    }

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
