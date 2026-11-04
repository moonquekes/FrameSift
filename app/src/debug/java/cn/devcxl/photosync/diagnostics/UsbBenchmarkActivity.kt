package cn.devcxl.photosync.diagnostics

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cn.devcxl.photosync.App
import cn.devcxl.photosync.R
import cn.devcxl.photosync.album.PhotoExif
import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** ADB-only diagnostic entry in Debug builds. Reads camera data and writes only private scratch files. */
class UsbBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { setText(R.string.usb_benchmark_running); textSize = 20f }
        setContentView(status)
        val handle = intent.getIntExtra("handle", 0)
        if (handle <= 0 || savedInstanceState != null) { finish(); return }
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { runBenchmark(handle) }
                File(filesDir, "usb-benchmark.json").writeText(results.toString(2))
                Timber.tag("USB_COMPAT").i("BENCH_COMPLETE handle=0x%08x samples=%d", handle, results.length())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Timber.tag("USB_COMPAT").w(e, "BENCH_FAILED handle=0x%08x", handle)
            }
            status.setText(R.string.usb_benchmark_complete)
        }
    }

    private suspend fun runBenchmark(handle: Int): JSONArray {
        val results = JSONArray()
        val directory = File(externalCacheDir ?: cacheDir, "usb-benchmark").apply { mkdirs() }
        val controller = App.get().usbPtpConnectionController
        controller.withConnectedCamera { camera ->
            val objectInfo = camera.getObjectInfo(handle)
            require(objectInfo.filename.orEmpty().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg"))
            val info = camera.getDeviceInfo()
            Timber.tag("USB_COMPAT").i("BENCH_OPERATIONS codes=%s",
                info.operationsSupported.joinToString(",") { "0x%04x".format(it) })
            if (intent.getBooleanExtra("properties", false)) {
                val properties = camera.getObjectPropertiesSupported(objectInfo.objectFormatCode)
                results.put(JSONObject().put("kind", "supportedProperties")
                    .put("codes", JSONArray(properties.toList())))
                Timber.tag("USB_COMPAT").i("BENCH_PROPERTIES format=0x%04x codes=%s",
                    objectInfo.objectFormatCode, properties.joinToString(",") { "0x%04x".format(it) })
                representativeSample(camera, handle, properties, results, directory)
                return@withConnectedCamera
            }
            val offset = intent.getIntExtra("offset", 0)
            val length = intent.getIntExtra("length", 0)
            val readBuffer = intent.getIntExtra("readBuffer", 512)
            require(readBuffer == 512 || readBuffer == 16 * 1024)
            if (offset > 0 && length in 1..(2 * 1024 * 1024)) {
                rangeReads(camera, handle, results, directory, offset, length, readBuffer)
            } else {
                smallReads(camera, handle, results, readBuffer)
                val original = File(directory, "original.jpg")
                repeat(3) { index ->
                    sample(results, "fullGetObject", index) { check(camera.importFile(handle, original.path, false)) }
                    localReads(original, directory, results, index)
                }
            }
        }
        return results
    }

    private fun representativeSample(
        camera: BaselineInitiator, handle: Int, supported: IntArray, results: JSONArray, directory: File
    ) {
        val values = mutableMapOf<Int, Long>()
        for (code in 0xdc81..0xdc84) {
            if (code !in supported) continue
            val raw = camera.getObjectPropertyValue(handle, code)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val value = if (raw.size == 2) buffer.short.toLong() and 0xffff else buffer.int.toLong() and 0xffffffffL
            values[code] = value
            results.put(JSONObject().put("kind", "representativeProperty").put("code", code).put("value", value))
        }
        if (0xdc86 !in supported || values[0xdc82] !in 1L..(2L * 1024 * 1024)) return
        var raw = byteArrayOf()
        sample(results, "representativeSample", 0) { raw = camera.getObjectPropertyValue(handle, 0xdc86) }
        val bytes = if (raw.size > 4 && raw[0] != 0xff.toByte()) {
            val count = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).int
            require(count in 1..(raw.size - 4))
            raw.copyOfRange(4, 4 + count)
        } else raw
        File(directory, "representative.jpg").writeBytes(bytes)
        dimensions(results, "representativeSample", bytes)
    }

    private fun rangeReads(
        camera: BaselineInitiator, handle: Int, results: JSONArray, directory: File,
        offset: Int, length: Int, readBuffer: Int
    ) {
        val prefix = camera.getObjectHeader(handle, minOf(length, 512))
        repeat(3) { index ->
            var bytes = byteArrayOf()
            sample(results, "rangeRead", index) { bytes = camera.getObjectRange(handle, offset, length, readBuffer) }
            File(directory, "medium.jpg").writeBytes(bytes)
            val ignoredOffset = bytes.take(prefix.size).toByteArray().contentEquals(prefix)
            results.put(JSONObject().put("kind", "rangeIntegrity").put("requestedOffset", offset)
                .put("equalsFilePrefix", ignoredOffset))
            dimensions(results, "rangeCandidate", bytes)
            if (ignoredOffset) Timber.tag("USB_COMPAT").w("BENCH_RANGE_OFFSET_IGNORED offset=%d", offset)
        }
    }

    private fun smallReads(camera: BaselineInitiator, handle: Int, results: JSONArray, readBuffer: Int) {
        repeat(3) { index ->
            var bytes = byteArrayOf()
            sample(results, "thumb", index) { bytes = camera.getThumb(handle, readBuffer) }
            dimensions(results, "thumb", bytes)
            sample(results, "thumbDecode", index) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.recycle() }
            sample(results, "header512", index) { camera.getObjectHeader(handle) }
            sample(results, "header64KiB", index) { camera.getObjectHeader(handle, 64 * 1024) }
            sample(results, "header128KiB", index) {
                camera.getObjectRange(handle, 0, 128 * 1024, readBuffer)
            }
        }
    }

    private fun localReads(original: File, directory: File, results: JSONArray, index: Int) {
        results.put(JSONObject().put("kind", "originalBytes").put("bytes", original.length()))
        val buffer = ByteArray(64 * 1024)
        sample(results, "localReadSha256", index) {
            val digest = MessageDigest.getInstance("SHA-256")
            original.inputStream().use { input ->
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
        }
        sample(results, "localCopy", index) { original.copyTo(File(directory, "copy.jpg"), overwrite = true) }
        sample(results, "localSync", index) {
            FileOutputStream(File(directory, "copy.jpg"), true).use { it.fd.sync() }
        }
        sample(results, "exifRead", index) { PhotoExif.read(original) }
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        sample(results, "screenSampleDecode", index) { BitmapFactory.decodeFile(original.path, options)?.recycle() }
    }

    private fun sample(results: JSONArray, kind: String, index: Int, action: () -> Unit) {
        val start = System.nanoTime()
        action()
        val elapsed = (System.nanoTime() - start) / 1000
        results.put(JSONObject().put("kind", kind).put("index", index).put("elapsedUs", elapsed))
        Timber.tag("USB_COMPAT").i("BENCH_SAMPLE kind=%s index=%d elapsedUs=%d", kind, index, elapsed)
    }

    private fun dimensions(results: JSONArray, kind: String, bytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        results.put(JSONObject().put("kind", kind).put("bytes", bytes.size)
            .put("width", options.outWidth).put("height", options.outHeight))
    }
}
