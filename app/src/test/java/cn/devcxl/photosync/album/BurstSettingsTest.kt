package cn.devcxl.photosync.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstSettingsTest {
    private fun photo(id: Int, second: Int) = CameraPhoto(
        id, "DSC%05d.JPG".format(id), 100, "20261102T1200%02d".format(second)
    )

    @Test fun shouldOnlyMergeSameSecond_whenGapIsZero() {
        val groups = burstCandidates(listOf(photo(1, 0), photo(2, 0), photo(3, 0), photo(4, 1)), BurstSettings(0))
        assertEquals(listOf(3, 1), groups.map { it.size })
    }

    @Test fun shouldIncludeLargerAdjacentGaps_whenThresholdIsIncreased() {
        val photos = listOf(photo(1, 0), photo(2, 2), photo(3, 4))
        assertEquals(listOf(1, 1, 1), burstCandidates(photos).map { it.size })
        assertEquals(listOf(3), burstCandidates(photos, BurstSettings(2)).map { it.size })
    }

    @Test fun shouldLeaveShorterRunsSeparate_whenMinimumIsRaised() {
        assertEquals(listOf(1, 1, 1), burstCandidates((1..3).map { photo(it, 0) }, BurstSettings(1, 4)).map { it.size })
    }

    @Test fun shouldAllowPairs_whenMinimumIsTwo() {
        assertEquals(listOf(2), burstCandidates(listOf(photo(1, 0), photo(2, 1)), BurstSettings(1, 2)).map { it.size })
    }

    @Test fun shouldAllowNonconsecutiveNumbers_whenSequenceRequirementIsDisabled() {
        val photos = listOf(photo(1, 0), photo(4, 0), photo(9, 0))
        assertTrue(burstCandidates(photos).all { it.size == 1 })
        assertEquals(listOf(3), burstCandidates(photos, BurstSettings(1, 3, false)).map { it.size })
    }

    @Test fun shouldUseAdjacentGapsInsteadOfWholeRunDuration_whenShotsContinue() {
        assertEquals(listOf(3), burstCandidates(listOf(photo(1, 0), photo(2, 1), photo(3, 2))).map { it.size })
    }

    @Test fun shouldUseSameCustomThreshold_whenBuildingGridAndPreview() {
        val photos = listOf(photo(1, 0), photo(2, 2), photo(3, 10))
        val settings = BurstSettings(2, 2)
        val tiles = buildAlbumTiles(photos, AlbumSort.NEWEST, null, true, emptySet(), settings)
        assertEquals(listOf(1, 2), tiles.filterIsInstance<AlbumTile.Photo>().map { it.members.size })
        val preview = buildPreviewSequence(photos, AlbumSort.NEWEST, null, 2, true, settings)
        assertEquals(BurstPosition(1, 2), preview.bursts[2])
        assertEquals(listOf(3, 2, 1), preview.photos.map { it.handle })
    }
}
