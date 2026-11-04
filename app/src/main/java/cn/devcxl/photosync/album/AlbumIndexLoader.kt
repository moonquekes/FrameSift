package cn.devcxl.photosync.album

import android.content.Context
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.util.UUID

/** Uses the real-device validated GetObjectInfo path, with resumable metadata checkpoints. */
internal class AlbumIndexLoader(private val context: Context, private val controller: UsbPtpConnectionController) {
    suspend fun load(force: Boolean, update: (List<CameraPhoto>, Int, Int, Boolean) -> Unit): List<CameraPhoto> {
        val (identity, handles) = controller.withConnectedCamera { camera ->
            val info = camera.getDeviceInfo()
            val identity = "${info.manufacturer}:${info.model}:${info.serialNumber}"
            identity to camera.getStorageIds().flatMap { camera.getObjectHandles(it, 0, 0).toList() }.distinct()
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(handles.sorted().joinToString(",").toByteArray()).joinToString("") { "%02x".format(it) }
        Timber.tag("USB_COMPAT").i("GALLERY_CARD_SNAPSHOT objects=%d handlesSha256=%s", handles.size, digest)
        val cache = AlbumMetadataCache(context, identity)
        val saved = cache.read()
        val reusable = saved != null && canReuseAlbumHandles(saved.handles, handles.toSet(), force) &&
            (handles.isEmpty() || validate(saved.photos))
        val generation = if (reusable) requireNotNull(saved).generation else UUID.randomUUID().toString()
        val processed = if (reusable) requireNotNull(saved).handles.toMutableSet() else mutableSetOf()
        val photos = if (reusable) requireNotNull(saved).photos.toMutableList() else mutableListOf()
        if (!reusable) Timber.tag("USB_COMPAT").i(
            "GALLERY_CACHE_RESET forced=%b removed=%d current=%d",
            force, saved?.handles?.count { it !in handles } ?: 0, handles.size
        )
        // Persist the empty state too: a formatted card must replace the old checkpoint immediately.
        cache.write(processed, photos, generation)
        val pending = handles.reversed().filter { it !in processed }
        Timber.tag("USB_COMPAT").i("GALLERY_INDEX_CACHE reused=%d pending=%d", processed.size, pending.size)
        update(photos.toList(), processed.size, handles.size, pending.isEmpty())
        for ((index, page) in pending.chunked(8).withIndex()) {
            currentCoroutineContext().ensureActive()
            for (handle in page) {
                currentCoroutineContext().ensureActive()
                val photo = controller.withConnectedCamera { CameraPhoto.from(it.getObjectInfo(handle)) }
                    .copy(cacheToken = cache.namespace(generation))
                if (photo.name.endsWith(".jpg", true) || photo.name.endsWith(".jpeg", true)) photos.add(photo)
            }
            processed.addAll(page)
            val complete = processed.size == handles.size
            update(photos.toList(), processed.size, handles.size, complete)
            if (index % 16 == 15 || complete) cache.write(processed, photos, generation)
        }
        return photos
    }

    private suspend fun validate(photos: List<CameraPhoto>): Boolean {
        if (photos.isEmpty()) return false
        return controller.withConnectedCamera { camera ->
            listOf(photos.first(), photos.last()).distinctBy { it.handle }.all { cached ->
                CameraPhoto.from(camera.getObjectInfo(cached.handle)).copy(cacheToken = cached.cacheToken).identity == cached.identity
            }
        }
    }
}
