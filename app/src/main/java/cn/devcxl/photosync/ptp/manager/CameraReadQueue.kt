package cn.devcxl.photosync.ptp.manager

import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

internal enum class CameraReadPriority {
    CURRENT_ORIGINAL, CURRENT_PREVIEW, VISIBLE_THUMBNAIL, NEIGHBOR_PREVIEW, BACKGROUND
}

/** Cancels queued work, finishes active USB reads, and shares identical keyed image requests. */
internal class CameraReadQueue(scope: CoroutineScope) {
    private class Read(
        var priority: CameraReadPriority, val sequence: Long, val key: String?, val action: suspend () -> Any?
    ) {
        val result = CompletableDeferred<Any?>()
        val queuedAt = System.nanoTime()
        var clients = 0
        var started = false
    }
    private val mutex = Mutex()
    private val pending = PriorityQueue(compareBy<Read> { it.priority.ordinal }.thenBy { it.sequence })
    private val shared = mutableMapOf<String, Read>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var sequence = 0L

    init {
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val request = mutex.withLock { pending.poll()?.also { it.started = true } } ?: break
                    if (!request.result.isCancelled) execute(request)
                }
            }
        }
    }

    suspend fun <T> submit(priority: CameraReadPriority, key: String? = null, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val request = mutex.withLock {
            val existing = key?.let(shared::get)
            val entry = existing ?: Read(priority, sequence++, key, action).also {
                pending.add(it)
                if (key != null) shared[key] = it
            }
            if (!entry.started && priority < entry.priority) {
                pending.remove(entry); entry.priority = priority; pending.add(entry)
            }
            entry.clients++
            wake.trySend(Unit)
            entry
        }
        try {
            @Suppress("UNCHECKED_CAST")
            return request.result.await() as T
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    request.clients--
                    if (request.clients == 0 && !request.started && !request.result.isCompleted) {
                        request.result.cancel()
                        if (request.key != null && shared[request.key] === request) shared.remove(request.key)
                    }
                }
            }
        }
    }

    private suspend fun execute(request: Read) {
        val wait = (System.nanoTime() - request.queuedAt) / 1_000_000
        if (wait > 5) Timber.tag("USB_COMPAT").i("CAMERA_QUEUE priority=%s waitMs=%d", request.priority.name, wait)
        try { request.result.complete(request.action()) }
        catch (e: CancellationException) { request.result.cancel(e); throw e }
        catch (e: Exception) { request.result.completeExceptionally(e) }
        finally {
            mutex.withLock {
                if (request.key != null && shared[request.key] === request) shared.remove(request.key)
            }
        }
    }
}
