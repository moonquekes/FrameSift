package cn.devcxl.photosync.album

internal data class BurstPosition(val index: Int, val count: Int)
internal data class AlbumPreviewSequence(
    val photos: List<CameraPhoto>, val initialIndex: Int, val bursts: Map<Int, BurstPosition>
)

/** Stacks affect only the grid; the pager always includes every photo in the current date filter. */
internal fun buildPreviewSequence(
    photos: List<CameraPhoto>, sort: AlbumSort, date: String?, cover: Int, showBursts: Boolean
): AlbumPreviewSequence {
    val ordered = sortPhotos(photos.filter { date == null || it.day == date }, sort)
    val positions = ordered.mapIndexed { index, photo -> photo.handle to index }.toMap()
    val bursts = if (showBursts) burstCandidates(ordered).filter { it.size > 1 }.flatMap { group ->
        group.sortedBy { positions[it.handle] }.mapIndexed { index, photo ->
            photo.handle to BurstPosition(index + 1, group.size)
        }
    }.toMap() else emptyMap()
    return AlbumPreviewSequence(ordered, ordered.indexOfFirst { it.handle == cover }.coerceAtLeast(0), bursts)
}
