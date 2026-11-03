package cn.devcxl.photosync.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JpegOrientationTest {
    private fun header(orientation: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val data = ByteArray(64)
        data[0] = 0xff.toByte(); data[1] = 0xd8.toByte()
        data[2] = 0xff.toByte(); data[3] = 0xe1.toByte()
        data[4] = 0xaf.toByte(); data[5] = 0xc8.toByte() // APP1 declares 45KB; only its start is downloaded.
        byteArrayOf(69, 120, 105, 102, 0, 0).copyInto(data, 6)
        data[12] = if (order == ByteOrder.LITTLE_ENDIAN) 73 else 77
        data[13] = data[12]
        ByteBuffer.wrap(data).order(order).apply {
            putShort(14, 42); putInt(16, 8); putShort(20, 1)
            putShort(22, 0x0112); putShort(24, 3); putInt(26, 1); putShort(30, orientation.toShort())
        }
        return data
    }

    @Test fun shouldReadAllOrientationValues_inBothTiffByteOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            for (value in 1..8) assertEquals(value, JpegOrientation.read(header(value, order)))
        }
    }

    @Test fun shouldReadOrientation8_withoutDownloadingTheWholeExifSegment() {
        assertEquals(8, JpegOrientation.read(header(8)))
    }

    @Test fun shouldSkipLeadingJpegSegments_beforeReadingExif() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 4, 0, 0) +
            header(6).copyOfRange(2, 64)
        assertEquals(6, JpegOrientation.read(bytes))
    }

    @Test fun shouldRejectOutOfBoundsIfdOffsets_insteadOfInventingAnOrientation() {
        val bytes = header(8)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Int.MAX_VALUE)
        assertNull(JpegOrientation.read(bytes))
        assertNull(JpegOrientation.read(header(8).copyOf(15)))
    }

    @Test fun shouldReturnUnknown_whenExifDoesNotContainOrientation() {
        val bytes = header(8)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(22, 0x0100)
        assertNull(JpegOrientation.read(bytes))
        assertNull(JpegOrientation.read(byteArrayOf(1, 2, 3)))
    }
}
