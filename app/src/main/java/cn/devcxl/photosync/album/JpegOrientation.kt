package cn.devcxl.photosync.album

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads IFD0 orientation from a partial JPEG header without requiring the entire EXIF APP1 segment. */
internal object JpegOrientation {
    fun read(bytes: ByteArray): Int? = runCatching {
        if (bytes.size < 4 || u8(bytes, 0) != 0xff || u8(bytes, 1) != 0xd8) return@runCatching null
        var offset = 2
        while (offset + 4 <= bytes.size && u8(bytes, offset) == 0xff) {
            val marker = u8(bytes, offset + 1)
            if (marker == 0xda || marker == 0xd9) break
            val length = u8(bytes, offset + 2) * 256 + u8(bytes, offset + 3)
            if (length < 2) break
            val start = offset + 4
            if (marker == 0xe1 && start + 14 <= bytes.size &&
                bytes.copyOfRange(start, start + 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                return@runCatching readTiff(bytes, start + 6)
            }
            offset += length + 2
        }
        null
    }.getOrNull()

    private fun readTiff(bytes: ByteArray, start: Int): Int? {
        val order = when {
            bytes[start] == 73.toByte() && bytes[start + 1] == 73.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[start] == 77.toByte() && bytes[start + 1] == 77.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val data = ByteBuffer.wrap(bytes).order(order)
        if (data.getShort(start + 2).toInt() != 42) return null
        val relative = data.getInt(start + 4).toLong() and 0xffffffffL
        if (relative > bytes.size - start - 2) return null
        val ifd = start + relative.toInt()
        val count = data.getShort(ifd).toInt() and 0xffff
        repeat(count) { index ->
            val entry = ifd + 2 + index * 12
            if (entry + 12 > bytes.size) return null
            if ((data.getShort(entry).toInt() and 0xffff) == 0x0112 &&
                data.getShort(entry + 2).toInt() == 3 && data.getInt(entry + 4) == 1) {
                return (data.getShort(entry + 8).toInt() and 0xffff).takeIf { it in 1..8 }
            }
        }
        return null
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff
}
