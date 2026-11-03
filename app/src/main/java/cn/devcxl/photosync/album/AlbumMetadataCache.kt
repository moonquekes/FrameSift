package cn.devcxl.photosync.album

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class CachedAlbum(val handles: Set<Int>, val photos: List<CameraPhoto>)

/** Private metadata checkpoints avoid re-reading an unchanged card on every connection. */
internal class AlbumMetadataCache(context: Context, cameraIdentity: String) {
    private val key = MessageDigest.getInstance("SHA-256").digest(cameraIdentity.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val file = File(context.filesDir, "camera-index-$key.json")

    suspend fun read(): CachedAlbum? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val root = JSONObject(file.readText())
            val handles = root.getJSONArray("handles").let { array -> (0 until array.length()).map { array.getInt(it) }.toSet() }
            val photos = root.getJSONArray("photos").let { array -> (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CameraPhoto(item.getInt("handle"), item.getString("name"), item.getLong("bytes"),
                    item.getString("date"), item.getInt("storage"))
            } }
            CachedAlbum(handles, photos)
        }.getOrNull()
    }

    suspend fun write(handles: Set<Int>, photos: List<CameraPhoto>) = withContext(Dispatchers.IO) {
        val root = JSONObject().put("version", 1).put("handles", JSONArray(handles.toList()))
        val items = JSONArray()
        photos.forEach { photo -> items.put(JSONObject().put("handle", photo.handle).put("name", photo.name)
            .put("bytes", photo.bytes).put("date", photo.captureDate).put("storage", photo.storageId)) }
        root.put("photos", items)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
    }
}
