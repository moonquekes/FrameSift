package cn.devcxl.photosync.album

import android.content.Context
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber

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
        val saved = if (force) null else cache.read()
        val reusable = saved != null && validate(saved.photos.filter { it.handle in handles })
        val processed = if (reusable) saved!!.handles.intersect(handles.toSet()).toMutableSet() else mutableSetOf()
        val photos = if (reusable) saved!!.photos.filter { it.handle in handles }.toMutableList() else mutableListOf()
        val pending = handles.reversed().filter { it !in processed }
        Timber.tag("USB_COMPAT").i("GALLERY_INDEX_CACHE reused=%d pending=%d", processed.size, pending.size)
        update(photos.toList(), processed.size, handles.size, pending.isEmpty())
        for ((index, page) in pending.chunked(8).withIndex()) {
            currentCoroutineContext().ensureActive()
            photos.addAll(controller.withConnectedCamera { camera ->
                page.map { camera.getObjectInfo(it) }.map(CameraPhoto::from)
                    .filter { it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) }
            })
            processed.addAll(page)
            val complete = processed.size == handles.size
            update(photos.toList(), processed.size, handles.size, complete)
            if (index % 16 == 15 || complete) cache.write(processed, photos)
        }
        return photos
    }

    private suspend fun validate(photos: List<CameraPhoto>): Boolean {
        if (photos.isEmpty()) return false
        return controller.withConnectedCamera { camera ->
            listOf(photos.first(), photos.last()).distinctBy { it.handle }.all { cached ->
                CameraPhoto.from(camera.getObjectInfo(cached.handle)).identity == cached.identity
            }
        }
    }
}
