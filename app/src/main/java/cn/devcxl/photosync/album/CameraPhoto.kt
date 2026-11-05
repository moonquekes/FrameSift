package cn.devcxl.photosync.album

import cn.devcxl.photosync.ptp.usbcamera.ObjectInfo
import java.text.SimpleDateFormat
import java.util.Locale

/** Camera-side JPEG metadata, retaining the camera's own local capture date. */
internal data class CameraPhoto(
    val handle: Int, val name: String, val bytes: Long, val captureDate: String, val storageId: Int = 0,
    val cacheToken: String = "", val documentUri: String? = null, val modifiedMillis: Long = 0,
    val contentStamp: String = ""
) {
    val timestamp: Long = runCatching {
        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).apply { isLenient = false }
            .parse(captureDate.take(15))?.time ?: 0L
    }.getOrDefault(0L)
    val day: String = if (timestamp > 0) captureDate.take(8) else "unknown"
    val fileSequence: Int? = Regex("(?i)DSC(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
    val identity: String = if (documentUri == null) "$cacheToken:$storageId:$handle:$name:$bytes:$captureDate"
        else "$cacheToken:$documentUri:$bytes:$modifiedMillis:$captureDate:$contentStamp"

    companion object {
        fun from(info: ObjectInfo): CameraPhoto = CameraPhoto(
            info.handle, info.filename.orEmpty(), info.objectCompressedSize.toLong() and 0xffffffffL,
            info.captureDate?.takeIf { it.isNotBlank() } ?: info.modificationDate.orEmpty(), info.storageId
        )
    }
}

internal enum class AlbumSort { NEWEST, OLDEST, NAME_ASC, NAME_DESC }

internal sealed class AlbumTile {
    data class Day(val key: String, val count: Int, val collapsed: Boolean = false) : AlbumTile()
    data class Photo(val members: List<CameraPhoto>) : AlbumTile() {
        val cover: CameraPhoto get() = members.first()
    }
}

internal fun sortPhotos(photos: List<CameraPhoto>, sort: AlbumSort): List<CameraPhoto> {
    val knownDateFirst = compareBy<CameraPhoto> { it.timestamp <= 0 }
    val comparator = when (sort) {
        AlbumSort.NEWEST -> knownDateFirst.thenByDescending { it.timestamp }.thenByDescending { it.name }
        AlbumSort.OLDEST -> knownDateFirst.thenBy { it.timestamp }.thenBy { it.name }
        AlbumSort.NAME_ASC -> compareBy { it.name }
        AlbumSort.NAME_DESC -> compareByDescending { it.name }
    }
    return photos.sortedWith(comparator)
}

/** A time/file-sequence heuristic, deliberately labeled as a candidate rather than proven burst metadata. */
internal fun burstCandidates(photos: List<CameraPhoto>, settings: BurstSettings = BurstSettings()): List<List<CameraPhoto>> {
    val groups = mutableListOf<MutableList<CameraPhoto>>()
    for (photo in sortPhotos(photos, AlbumSort.OLDEST)) {
        val previous = groups.lastOrNull()?.lastOrNull()
        val closeInTime = previous != null && previous.timestamp > 0 && photo.timestamp > 0 &&
            photo.day == previous.day && photo.timestamp - previous.timestamp in 0..settings.maxGapSeconds * 1000L
        val consecutive = previous?.fileSequence?.let { photo.fileSequence == it + 1 } == true
        if (closeInTime && (consecutive || !settings.requireConsecutive)) groups.last().add(photo)
        else groups.add(mutableListOf(photo))
    }
    return groups.flatMap { group -> if (group.size >= settings.minimumPhotos) listOf(group) else group.map { listOf(it) } }
}

internal fun buildAlbumTiles(
    photos: List<CameraPhoto>, sort: AlbumSort, date: String?, stackBursts: Boolean,
    collapsedDays: Set<String> = emptySet(), burstSettings: BurstSettings = BurstSettings()
): List<AlbumTile> {
    val filtered = photos.filter { date == null || it.day == date }
    val stacks = if (stackBursts) burstCandidates(filtered, burstSettings) else filtered.map { listOf(it) }
    val order = sortPhotos(filtered, sort).mapIndexed { index, photo -> photo.handle to index }.toMap()
    val sorted = stacks.map { it.sortedBy { photo -> order[photo.handle] ?: Int.MAX_VALUE } }
        .sortedBy { order[it.first().handle] ?: Int.MAX_VALUE }
    return sorted.groupBy { it.first().day }.flatMap { (day, dayStacks) ->
        listOf(AlbumTile.Day(day, dayStacks.sumOf { it.size }, day in collapsedDays)) +
            if (day in collapsedDays) emptyList() else dayStacks.map { AlbumTile.Photo(it) }
    }
}
