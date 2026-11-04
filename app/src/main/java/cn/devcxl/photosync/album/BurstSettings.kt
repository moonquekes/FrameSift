package cn.devcxl.photosync.album

/** Capture timestamps have one-second precision, so the threshold uses whole seconds. */
internal data class BurstSettings(
    val maxGapSeconds: Int = 1,
    val minimumPhotos: Int = 3,
    val requireConsecutive: Boolean = true
) {
    init {
        require(maxGapSeconds in 0..5)
        require(minimumPhotos in 2..10)
    }
}
