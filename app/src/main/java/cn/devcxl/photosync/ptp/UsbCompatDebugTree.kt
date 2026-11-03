package cn.devcxl.photosync.ptp

import android.os.Process
import java.io.File
import java.io.IOException
import timber.log.Timber

/** Keeps USB diagnostics available when a device suppresses informational logcat records. */
class UsbCompatDebugTree(private val output: File) : Timber.DebugTree() {
    private val fileLock = Any()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, message, t)
        if (tag != "USB_COMPAT") return
        synchronized(fileLock) {
            try {
                output.appendText(
                    "${System.currentTimeMillis()} ${Process.myPid()}/${Process.myTid()} $message\n"
                )
            } catch (_: IOException) {
                // Diagnostics must not turn a successful USB operation into a failure.
            }
        }
    }
}
