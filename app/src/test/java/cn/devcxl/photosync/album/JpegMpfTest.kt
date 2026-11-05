package cn.devcxl.photosync.album

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JpegMpfTest {
    @Test fun shouldUseTiffRelativeOffset_whenLittleEndian() {
        assertEquals(EmbeddedPreview(210, 100), JpegMpf.read(fixture(), 500))
    }

    @Test fun shouldUseTiffRelativeOffset_whenBigEndian() {
        assertEquals(EmbeddedPreview(210, 100), JpegMpf.read(fixture(ByteOrder.BIG_ENDIAN), 500))
    }

    @Test fun shouldRejectPreview_whenItExceedsOriginal() {
        assertNull(JpegMpf.read(fixture(), 250))
    }

    @Test fun shouldRejectPreview_whenHeaderIsTruncated() {
        assertNull(JpegMpf.read(fixture().copyOf(60), 500))
    }

    @Test fun shouldRejectPreview_whenMpfOffsetOverflows() {
        val bytes = fixture()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(64 - 4, -1)
        assertNull(JpegMpf.read(bytes, 500))
    }

    @Test fun shouldRejectNonJpeg() { assertNull(JpegMpf.read(ByteArray(200), 500)) }

    @Test fun shouldChangeIdentity_whenCameraFileIsReplaced() {
        val photo = CameraPhoto(1, "DSC00001.JPG", 100, "20261104T120000", cacheToken = "file:volume",
            documentUri = "content://camera/old/DCIM/DSC00001.JPG", modifiedMillis = 1000)
        assertNotEquals(photo.identity, photo.copy(bytes = 101).identity)
        assertNotEquals(photo.identity, photo.copy(modifiedMillis = 2000).identity)
        assertNotEquals(photo.identity, photo.copy(documentUri = "content://camera/new/DCIM/DSC00001.JPG").identity)
        assertNotEquals(photo.identity, photo.copy(contentStamp = "replaced-jpeg-header").identity)
        assertEquals(photo.identity, photo.copy(handle = 200).identity)
    }

    private fun fixture(order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val bytes = ByteArray(128)
        bytes[0] = 255.toByte(); bytes[1] = 216.toByte(); bytes[2] = 255.toByte(); bytes[3] = 226.toByte()
        bytes[5] = 90
        byteArrayOf(77, 80, 70, 0).copyInto(bytes, 6)
        val data = ByteBuffer.wrap(bytes).order(order)
        bytes[10] = if (order == ByteOrder.LITTLE_ENDIAN) 73 else 77
        bytes[11] = bytes[10]
        data.putShort(12, 42); data.putInt(14, 8); data.putShort(18, 1)
        data.putShort(20, 0xb002.toShort()); data.putShort(22, 7); data.putInt(24, 32); data.putInt(28, 26)
        data.putInt(52, 0x010002); data.putInt(56, 100); data.putInt(60, 200)
        return bytes
    }
}
