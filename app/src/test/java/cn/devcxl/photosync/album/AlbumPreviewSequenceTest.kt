package cn.devcxl.photosync.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPreviewSequenceTest {
    private fun photo(id: Int, time: String) = CameraPhoto(id, "DSC%05d.JPG".format(id), 100, time)
    private val photos = listOf(
        photo(1, "20261102T100000"), photo(2, "20261102T110000"),
        photo(3, "20261102T110000"), photo(4, "20261102T110000"), photo(5, "20261102T120000")
    )

    @Test fun shouldAllowBothNeighborsOutsideStack_whenOpeningItsCover() {
        val sequence = buildPreviewSequence(photos, AlbumSort.NEWEST, null, 4, true)
        assertEquals(listOf(5, 4, 3, 2, 1), sequence.photos.map { it.handle })
        assertEquals(1, sequence.initialIndex)
        assertEquals(5, sequence.photos[sequence.initialIndex - 1].handle)
        assertEquals(1, sequence.photos[sequence.initialIndex + 3].handle)
        assertEquals(BurstPosition(1, 3), sequence.bursts[4])
        assertEquals(BurstPosition(3, 3), sequence.bursts[2])
        assertEquals(null, sequence.bursts[1])
    }

    @Test fun shouldKeepDateFilter_whenPagingBeyondTheBurst() {
        val sequence = buildPreviewSequence(
            photos + photo(6, "20261101T120000"), AlbumSort.NEWEST, "20261102", 4, true
        )
        assertEquals(5, sequence.photos.size)
        assertTrue(sequence.photos.all { it.day == "20261102" })
    }

    @Test fun shouldFollowChosenSort_whenPagingThroughTheStack() {
        val sequence = buildPreviewSequence(photos, AlbumSort.OLDEST, null, 2, true)
        assertEquals(listOf(1, 2, 3, 4, 5), sequence.photos.map { it.handle })
        assertEquals(1, sequence.initialIndex)
        assertEquals(BurstPosition(1, 3), sequence.bursts[2])
    }

    @Test fun shouldKeepSameFullSequence_whenBurstMergingIsDisabled() {
        val sequence = buildPreviewSequence(photos, AlbumSort.NEWEST, null, 4, false)
        assertEquals(listOf(5, 4, 3, 2, 1), sequence.photos.map { it.handle })
        assertTrue(sequence.bursts.isEmpty())
    }
}
