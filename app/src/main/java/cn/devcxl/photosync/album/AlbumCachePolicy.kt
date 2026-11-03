package cn.devcxl.photosync.album

/** Losing handles can mean a formatted/replaced card; never retain its processed-object checkpoint. */
internal fun canReuseAlbumHandles(cached: Set<Int>, current: Set<Int>, force: Boolean): Boolean =
    !force && current.containsAll(cached)
