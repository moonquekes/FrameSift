package cn.devcxl.photosync.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumGroupingTest {
    private fun photo(id: Int, time: String) = CameraPhoto(id, "DSC%05d.JPG".format(id), 100, time)

    @Test fun shouldPutMissingDatesLast_whenSortingEitherDirection() {
        val photos = listOf(photo(1, ""), photo(2, "20260519T120001"), photo(3, "20260520T120001"))
        assertEquals(listOf(3, 2, 1), sortPhotos(photos, AlbumSort.NEWEST).map { it.handle })
        assertEquals(listOf(2, 3, 1), sortPhotos(photos, AlbumSort.OLDEST).map { it.handle })
    }

    @Test fun shouldGroupCandidate_whenThreeConsecutiveFilesAreCapturedWithinSeconds() {
        val photos = listOf(photo(1, "20260519T120000"), photo(2, "20260519T120000"), photo(3, "20260519T120001"))
        assertEquals(listOf(3), burstCandidates(photos).map { it.size })
    }

    @Test fun shouldKeepPairsSeparate_whenThereIsNotEnoughBurstEvidence() {
        assertEquals(2, burstCandidates(listOf(photo(1, "20260519T120000"), photo(2, "20260519T120000"))).size)
    }

    @Test fun shouldNotMerge_whenFileNumbersSkipOrCaptureDatesAreUnknown() {
        val photos = listOf(photo(1, "20260519T120000"), photo(4, "20260519T120000"), photo(8, "20260519T120000"))
        assertTrue(burstCandidates(photos).all { it.size == 1 })
        assertTrue(burstCandidates((1..4).map { photo(it, "") }).all { it.size == 1 })
    }

    @Test fun shouldNotMergeAcrossDates_whenShotsAreNearMidnight() {
        val photos = listOf(photo(1, "20260519T235959"), photo(2, "20260520T000000"), photo(3, "20260520T000000"))
        assertTrue(burstCandidates(photos).all { it.size == 1 })
    }

    @Test fun shouldRetainEveryPhoto_whenCandidateStacksAreCollapsed() {
        val photos = (1..9).map { photo(it, if (it < 5) "20260519T120000" else "20260520T120000") }
        val tiles = buildAlbumTiles(photos, AlbumSort.NEWEST, null, true)
        val restored = tiles.filterIsInstance<AlbumTile.Photo>().flatMap { it.members }.map { it.handle }.sorted()
        assertEquals(photos.map { it.handle }.sorted(), restored)
        assertEquals(9, tiles.filterIsInstance<AlbumTile.Day>().sumOf { it.count })
        assertEquals("20260520", (tiles.first() as AlbumTile.Day).key)
    }

    @Test fun shouldRestrictToChosenDate_whenDateFolderIsSelected() {
        val photos = listOf(photo(1, "20260519T120000"), photo(2, "20260520T120000"))
        val visible = buildAlbumTiles(photos, AlbumSort.NEWEST, "20260519", false)
            .filterIsInstance<AlbumTile.Photo>().flatMap { it.members }.map { it.handle }
        assertEquals(listOf(1), visible)
    }

    @Test fun shouldKeepDateCounts_whenACollapsedGroupHidesItsPhotos() {
        val photos = (1..5).map { photo(it, if (it <= 3) "20261102T120000" else "20261101T120000") }
        val tiles = buildAlbumTiles(photos, AlbumSort.NEWEST, null, false, setOf("20261102"))
        assertEquals(3, tiles.filterIsInstance<AlbumTile.Day>().first().count)
        assertTrue(tiles.filterIsInstance<AlbumTile.Day>().first().collapsed)
        assertEquals(listOf(5, 4), tiles.filterIsInstance<AlbumTile.Photo>().flatMap { it.members }.map { it.handle })
    }

    @Test fun shouldRestoreEveryPhoto_whenADateIsExpandedAgain() {
        val photos = (1..5).map { photo(it, "20261102T120000") }
        val expanded = buildAlbumTiles(photos, AlbumSort.NEWEST, null, true, emptySet())
        assertEquals(photos.map { it.handle }.toSet(), expanded.filterIsInstance<AlbumTile.Photo>()
            .flatMap { it.members }.map { it.handle }.toSet())
    }
}
