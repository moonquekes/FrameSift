package cn.devcxl.photosync.album

import android.content.Context
import android.net.Uri
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal enum class ImportStage { READING, SAVING, VERIFYING }

/** Reuses the existing raw GetObject downloader and gallery-copy callback. */
internal class AlbumTransfers(
    private val context: Context,
    private val controller: UsbPtpConnectionController,
    private val saveOriginal: (File, String) -> Uri?
) {
    private val directory = File(context.externalCacheDir ?: context.cacheDir, "camera-originals-v2").apply { mkdirs() }
    private val downloading = Mutex()
    private val pinned = ConcurrentHashMap.newKeySet<String>()

    suspend fun download(photo: CameraPhoto): File = downloading.withLock {
        withContext(Dispatchers.IO) {
            val file = cachedFile(photo)
            if (!file.exists() || photo.bytes <= 0 || file.length() != photo.bytes) {
                controller.withConnectedCamera { camera ->
                    val current = camera.getObjectInfo(photo.handle)
                    if (!camera.importFile(photo.handle, file.absolutePath, notifyDownloaded = false)) {
                        throw IOException("GetObject failed")
                    }
                    val expected = current.objectCompressedSize.toLong() and 0xffffffffL
                    if (file.length() != expected) throw IOException("Original byte count mismatch")
                }
            }
            file.setLastModified(System.currentTimeMillis())
            trim(file.name)
            file
        }
    }

    fun pin(photos: List<CameraPhoto>) { pinned.clear(); photos.forEach { pinned.add(cachedFile(it).name) } }
    fun clearPins() { pinned.clear() }

    private fun cachedFile(photo: CameraPhoto): File {
        val key = MessageDigest.getInstance("SHA-256").digest(photo.identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.jpg")
    }

    private fun trim(current: String) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.jpg")) }.orEmpty()
        var bytes = files.sumOf { it.length() }
        val limit = 256L * 1024 * 1024
        val parent = directory.canonicalFile
        for (file in files.sortedBy { it.lastModified() }) {
            if (bytes <= limit) break
            if (file.name == current || file.name in pinned || file.parentFile?.canonicalFile != parent) continue
            val size = file.length()
            if (file.delete()) bytes -= size
        }
    }

    suspend fun importOriginal(photo: CameraPhoto, stage: (ImportStage) -> Unit) {
        stage(ImportStage.READING)
        pin(listOf(photo))
        val file = download(photo)
        withContext(Dispatchers.IO) {
            val originalHash = file.inputStream().use(::sha256)
            stage(ImportStage.SAVING)
            val name = photo.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val uri = saveOriginal(file, name) ?: throw IOException("Gallery copy failed")
            stage(ImportStage.VERIFYING)
            val galleryHash = context.contentResolver.openInputStream(uri)?.use(::sha256)
            if (galleryHash != originalHash) {
                context.contentResolver.delete(uri, null, null)
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
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
