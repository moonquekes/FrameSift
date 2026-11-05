package cn.devcxl.photosync.album

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class EmbeddedPreview(val offset: Long, val bytes: Int)

/** Locates an MPF Full-HD JPEG using only a bounded JPEG header. Offsets are relative to its TIFF header. */
internal object JpegMpf {
    fun read(header: ByteArray, fileBytes: Long): EmbeddedPreview? = runCatching {
        if (header.size < 4 || u8(header, 0) != 255 || u8(header, 1) != 216) return@runCatching null
        var position = 2
        while (position + 4 <= header.size && u8(header, position) == 255) {
            val marker = u8(header, position + 1)
            if (marker == 0xda || marker == 0xd9) break
            val length = u8(header, position + 2) * 256 + u8(header, position + 3)
            if (length < 2 || position.toLong() + length + 2 > header.size) break
            val start = position + 4
            if (marker == 0xe2 && length >= 18 && header.copyOfRange(start, start + 4)
                    .contentEquals(byteArrayOf(77, 80, 70, 0))) {
                return@runCatching tiff(header, start + 4, position + length + 2, fileBytes)
            }
            position += length + 2
        }
        null
    }.getOrNull()

    private fun tiff(bytes: ByteArray, start: Int, end: Int, fileBytes: Long): EmbeddedPreview? {
        val order = when {
            bytes[start] == 73.toByte() && bytes[start + 1] == 73.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[start] == 77.toByte() && bytes[start + 1] == 77.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val data = ByteBuffer.wrap(bytes).order(order)
        if (data.getShort(start + 2).toInt() != 42) return null
        val ifd = (start.toLong() + u32(data, start + 4)).takeIf { it in start.toLong()..(end - 2).toLong() }
            ?.toInt() ?: return null
        val count = data.getShort(ifd).toInt() and 0xffff
        for (index in 0 until count) {
            val tag = ifd + 2 + index * 12
            if (tag + 12 > end) return null
            if ((data.getShort(tag).toInt() and 0xffff) != 0xb002) continue
            if (data.getShort(tag + 2).toInt() != 7) return null
            val size = u32(data, tag + 4)
            val table = start.toLong() + u32(data, tag + 8)
            if (size < 32 || size % 16 != 0L || table < start || table + size > end) return null
            for (entry in 1 until (size / 16).toInt()) {
                val at = table.toInt() + entry * 16
                val attributes = u32(data, at)
                if (attributes and 0x00ffffff != 0x010002L || (attributes shr 24) and 7 != 0L) continue
                val length = u32(data, at + 4)
                val offset = start.toLong() + u32(data, at + 8)
                if (length in 1..(2 * 1024 * 1024).toLong() && offset >= end && offset + length <= fileBytes) {
                    return EmbeddedPreview(offset, length.toInt())
                }
            }
        }
        return null
    }

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 255
    private fun u32(data: ByteBuffer, at: Int): Long = data.getInt(at).toLong() and 0xffffffffL
}
