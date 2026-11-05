package cn.devcxl.photosync.album

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import cn.devcxl.photosync.ptp.manager.CameraReadPriority
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Enumerates the granted camera folder; EXIF capture dates remain authoritative over filesystem times. */
internal class MassStorageIndex(
    private val context: Context, private val controller: UsbPtpConnectionController, private val files: MassStorageFiles
) {
    suspend fun load(
        tree: Uri, force: Boolean, update: (List<CameraPhoto>, Int, Int, Boolean) -> Unit
    ): List<CameraPhoto> {
        val started = System.nanoTime()
        val listed = withContext(Dispatchers.IO) {
            list(tree, DocumentsContract.getTreeDocumentId(tree)).sortedByDescending { it.modifiedMillis }
        }
        val token = MessageDigest.getInstance("SHA-256").digest(tree.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cache = File(context.filesDir, "file-index-$token.json")
        val saved = withContext(Dispatchers.IO) { readCache(cache) }
        files.clearHeaders()
        val valid = !force && validate(listed, saved)
        if (!valid) files.clearHeaders()
        if (valid && listed.all { saved[it.uri]?.let { old -> old.bytes == it.bytes &&
                    old.modifiedMillis == it.modifiedMillis } == true }) {
            val reused = listed.mapIndexed { index, item -> requireNotNull(saved[item.uri]).copy(handle = index + 1) }
            update(reused, reused.size, reused.size, true)
            Timber.tag("USB_COMPAT").i("FILE_INDEX_COMPLETE jpeg=%d reused=%d elapsedMs=%d",
                reused.size, reused.size, (System.nanoTime() - started) / 1_000_000)
            return reused
        }
        val photos = mutableListOf<CameraPhoto>()
        for ((index, item) in listed.withIndex()) {
            currentCoroutineContext().ensureActive()
            val old = saved[item.uri]
            val candidate = if (valid && old != null && old.bytes == item.bytes && old.modifiedMillis == item.modifiedMillis)
                old.copy(handle = index + 1) else controller.withFileRead(CameraReadPriority.BACKGROUND) {
                    val candidate = CameraPhoto(index + 1, item.name, item.bytes, "", cacheToken = "file:$token",
                        documentUri = item.uri, modifiedMillis = item.modifiedMillis)
                    val header = files.header(candidate)
                    val exif = ExifInterface(ByteArrayInputStream(header))
                    candidate.copy(captureDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?.let(::captureDate).orEmpty(), contentStamp = stamp(header))
                }
            photos.add(candidate)
            if (index % 16 == 15 || index == listed.lastIndex) update(photos.toList(), index + 1, listed.size,
                index == listed.lastIndex)
        }
        if (listed.isEmpty()) update(emptyList(), 0, 0, true)
        withContext(Dispatchers.IO) { writeCache(cache, photos) }
        Timber.tag("USB_COMPAT").i("FILE_INDEX_COMPLETE jpeg=%d reused=%d elapsedMs=%d", photos.size,
            photos.count { saved[it.documentUri]?.let { old -> old.identity == it.identity } == true },
            (System.nanoTime() - started) / 1_000_000)
        return photos
    }

    private data class Entry(val uri: String, val name: String, val bytes: Long, val modifiedMillis: Long)

    private suspend fun validate(listed: List<Entry>, saved: Map<String?, CameraPhoto>): Boolean {
        if (saved.isEmpty()) return false
        val candidates = listed.mapNotNull { saved[it.uri]?.takeIf { old ->
            old.bytes == it.bytes && old.modifiedMillis == it.modifiedMillis
        } }
        if (candidates.isEmpty()) return listed.isEmpty()
        for (photo in listOf(candidates.first(), candidates[candidates.size / 2], candidates.last()).distinctBy { it.identity }) {
            if (photo.contentStamp.isEmpty()) return false
            val same = controller.withFileRead(CameraReadPriority.BACKGROUND) { stamp(files.header(photo)) == photo.contentStamp }
            if (!same) return false
        }
        return true
    }

    private fun list(tree: Uri, parent: String, depth: Int = 0): List<Entry> {
        require(tree.authority == "com.android.externalstorage.documents" && depth <= 8)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val result = mutableListOf<Entry>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        checkNotNull(context.contentResolver.query(uri, columns, null, null, null)).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) result.addAll(list(tree, id, depth + 1))
                else if (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) result.add(Entry(
                    DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name,
                    cursor.getLong(3), cursor.getLong(4)))
            }
        }
        return result
    }

    private fun readCache(file: File): Map<String?, CameraPhoto> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            CameraPhoto(index + 1, item.getString("name"), item.getLong("bytes"), item.getString("date"),
                cacheToken = item.getString("token"), documentUri = item.getString("uri"),
                modifiedMillis = item.getLong("modified"), contentStamp = item.optString("stamp", ""))
        }.associateBy { it.documentUri }
    }.getOrDefault(emptyMap())

    private fun writeCache(file: File, photos: List<CameraPhoto>) {
        val array = JSONArray()
        photos.forEach { array.put(JSONObject().put("uri", it.documentUri).put("name", it.name).put("bytes", it.bytes)
            .put("modified", it.modifiedMillis).put("date", it.captureDate).put("token", it.cacheToken)
            .put("stamp", it.contentStamp)) }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(array.toString())
        check(temporary.renameTo(file)) { "Could not commit camera file index" }
    }

    private fun captureDate(value: String): String = if (value.matches(Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}")))
        value.take(10).replace(":", "") + "T" + value.substring(11).replace(":", "") else ""

    private fun stamp(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
