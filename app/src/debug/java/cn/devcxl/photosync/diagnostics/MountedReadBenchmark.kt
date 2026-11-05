package cn.devcxl.photosync.diagnostics

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only sequential buffer-size comparison; opens granted camera documents read-only. */
internal object MountedReadBenchmark {
    fun run(context: Context, tree: Uri, names: String, buffers: String): JSONArray {
        require(tree.authority == "com.android.externalstorage.documents")
        val files = names.split(',')
        val sizes = buffers.split(',').map(String::toInt)
        require(files.size == sizes.size && files.size in 1..12)
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val result = JSONArray()
        for ((index, name) in files.withIndex()) {
            require(name.matches(Regex("100MSDCF/DSC[0-9]+\\.JPG")))
            require(sizes[index] in listOf(64, 256, 1024))
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "$parent/$name")
            val start = System.nanoTime()
            val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
            val opened = System.nanoTime()
            var reading = 0L
            var hashing = 0L
            var count = 0L
            var calls = 0
            val digest = MessageDigest.getInstance("SHA-256")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                require(input.channel.size() in 1..(32 * 1024 * 1024L))
                val bytes = ByteArray(sizes[index] * 1024)
                while (true) {
                    val before = System.nanoTime()
                    val read = input.read(bytes)
                    reading += System.nanoTime() - before
                    calls++
                    if (read < 0) break
                    val hashStart = System.nanoTime()
                    digest.update(bytes, 0, read)
                    hashing += System.nanoTime() - hashStart
                    count += read
                }
            }
            result.put(JSONObject().put("name", name).put("bufferKiB", sizes[index]).put("bytes", count)
                .put("openMs", (opened - start) / 1_000_000.0).put("readMs", reading / 1_000_000.0)
                .put("hashMs", hashing / 1_000_000.0).put("readCalls", calls)
                .put("totalMs", (System.nanoTime() - start) / 1_000_000.0)
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) }))
        }
        return result
    }
}
