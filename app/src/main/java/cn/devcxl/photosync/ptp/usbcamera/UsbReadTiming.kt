package cn.devcxl.photosync.ptp.usbcamera

import timber.log.Timber

/** Monotonic timings around host calls; camera processing and USB waiting remain combined. */
internal class UsbReadTiming(private val operation: Int) {
    enum class Phase { COMMAND, FIRST_READ, BODY_READ, LOCAL_WRITE, RESPONSE, PARSE }
    private val started = System.nanoTime()
    private val nanos = LongArray(Phase.entries.size)
    var bodyCalls = 0

    fun <T> measure(phase: Phase, action: () -> T): T {
        val begin = System.nanoTime()
        try { return action() }
        finally { nanos[phase.ordinal] += System.nanoTime() - begin }
    }

    fun report(bytes: Int) {
        Timber.tag("USB_COMPAT").i(
            "READ_TIMING op=0x%04x bytes=%d commandUs=%d firstReadUs=%d bodyReadUs=%d " +
                "localWriteUs=%d responseUs=%d parseUs=%d bodyCalls=%d totalUs=%d",
            operation, bytes, nanos[0] / 1000, nanos[1] / 1000, nanos[2] / 1000,
            nanos[3] / 1000, nanos[4] / 1000, nanos[5] / 1000, bodyCalls,
            (System.nanoTime() - started) / 1000
        )
    }
}
