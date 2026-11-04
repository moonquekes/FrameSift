package cn.devcxl.photosync.ptp.manager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReadQueueTest {
    @Test fun shouldShareActiveRead_whenOriginalCallerCancelsAndAnotherCallerJoins() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CameraReadQueue(scope)
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.CURRENT_ORIGINAL, "original:photo") { reads++; gate.await(); 9 }
            }
            first.cancel(); first.join()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit<Int>(CameraReadPriority.CURRENT_ORIGINAL, "original:photo") { error("Duplicate USB read") }
            }
            gate.complete(Unit)
            assertEquals(9, second.await())
            assertEquals(1, reads)
        } finally { scope.cancel() }
    }

    @Test fun shouldPromoteSharedPreview_whenItBecomesTheCurrentPhoto() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CameraReadQueue(scope)
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val active = async(start = CoroutineStart.UNDISPATCHED) { queue.submit(CameraReadPriority.BACKGROUND) { gate.await() } }
            val neighbor = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.NEIGHBOR_PREVIEW, "preview:photo") { order.add("preview"); 1 }
            }
            val thumb = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.VISIBLE_THUMBNAIL) { order.add("thumb") }
            }
            val current = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit<Int>(CameraReadPriority.CURRENT_PREVIEW, "preview:photo") { error("Duplicate preview") }
            }
            gate.complete(Unit); active.await(); neighbor.await(); thumb.await(); current.await()
            assertEquals(listOf("preview", "thumb"), order)
        } finally { scope.cancel() }
    }

    @Test fun shouldPrioritizeCurrentPhoto_whenBackgroundReadsArePending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CameraReadQueue(scope)
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.BACKGROUND) { gate.await(); order.add("active") }
            }
            val low = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.BACKGROUND) { order.add("background") }
            }
            val high = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.CURRENT_ORIGINAL) { order.add("current") }
            }
            gate.complete(Unit); active.await(); low.await(); high.await()
            assertEquals(listOf("active", "current", "background"), order)
        } finally { scope.cancel() }
    }

    @Test fun shouldSkipCancelledRead_whenItHasNotStarted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CameraReadQueue(scope)
            val gate = CompletableDeferred<Unit>()
            val active = async(start = CoroutineStart.UNDISPATCHED) { queue.submit(CameraReadPriority.BACKGROUND) { gate.await() } }
            var executed = false
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.NEIGHBOR_PREVIEW) { executed = true }
            }
            queued.cancel(); queued.join(); gate.complete(Unit); active.await(); yield()
            assertFalse(executed)
        } finally { scope.cancel() }
    }

    @Test fun shouldFinishActiveRead_whenItsCallerCancels() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CameraReadQueue(scope)
            val gate = CompletableDeferred<Unit>()
            var finished = false
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                queue.submit(CameraReadPriority.CURRENT_ORIGINAL) { gate.await(); finished = true }
            }
            active.cancel(); active.join()
            assertFalse(finished)
            gate.complete(Unit); yield()
            assertTrue(finished)
        } finally { scope.cancel() }
    }

    @Test fun shouldContinueWorker_whenOneReadFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = CameraReadQueue(scope)
            val failed = runCatching { queue.submit(CameraReadPriority.BACKGROUND) { error("read failed") } }
            assertTrue(failed.isFailure)
            assertEquals(7, queue.submit(CameraReadPriority.CURRENT_ORIGINAL) { 7 })
        } finally { scope.cancel() }
    }
}
