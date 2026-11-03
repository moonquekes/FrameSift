package cn.devcxl.photosync.album

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumCachePolicyTest {
    @Test fun shouldReuse_whenCardHandlesAreUnchanged() {
        assertTrue(canReuseAlbumHandles(setOf(1, 2, 3), setOf(3, 2, 1), false))
    }

    @Test fun shouldKeepCheckpoint_whenOnlyNewObjectsWereAdded() {
        assertTrue(canReuseAlbumHandles(setOf(1, 2), setOf(1, 2, 3, 4), false))
    }

    @Test fun shouldInvalidate_whenAnyOldHandleDisappears() {
        assertFalse(canReuseAlbumHandles(setOf(1, 2, 3), setOf(1, 3, 4), false))
    }

    @Test fun shouldInvalidate_whenFormattingEmptiesTheCard() {
        assertFalse(canReuseAlbumHandles(setOf(1, 2, 3), emptySet(), false))
    }

    @Test fun shouldInvalidate_whenAReplacementCardHasDifferentHandles() {
        assertFalse(canReuseAlbumHandles(setOf(1, 2, 3), setOf(10, 11, 12), false))
    }

    @Test fun shouldInvalidate_whenRefreshIsExplicitEvenWithIdenticalHandles() {
        assertFalse(canReuseAlbumHandles(setOf(1, 2), setOf(1, 2), true))
    }

    @Test fun shouldAvoidOldImageKeys_whenHandlesAndMetadataAreReusedInANewGeneration() {
        val old = CameraPhoto(1, "DSC00001.JPG", 1000, "20261102T120000", 65537, "camera:generation1")
        assertNotEquals(old.identity, old.copy(cacheToken = "camera:generation2").identity)
        assertNotEquals(old.identity, old.copy(cacheToken = "other-camera:generation1").identity)
    }
}
