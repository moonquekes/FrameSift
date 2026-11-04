package cn.devcxl.photosync.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifFormattingTest {
    @Test fun shouldShowReciprocal_whenExposureIsAFraction() {
        assertEquals("1/250 s", exifShutter(1.0 / 250))
        assertEquals("1/60 s", exifShutter(0.016667))
    }

    @Test fun shouldKeepDecimalSeconds_whenExposureIsLongOrNotAnIntegerReciprocal() {
        assertEquals("2 s", exifShutter(2.0))
        assertEquals("0.6 s", exifShutter(0.6))
        assertEquals("1.3 s", exifShutter(1.3))
    }

    @Test fun shouldNotInventShutter_whenExposureIsMissingOrInvalid() {
        assertNull(exifShutter(Double.NaN))
        assertNull(exifShutter(0.0))
        assertNull(exifShutter(-1.0))
    }

    @Test fun shouldKeepNumericPrecisionWithoutTrailingZeroes_whenFormattingMetadata() {
        assertEquals("17", exifNumber(17.0))
        assertEquals("2.8", exifNumber(2.8))
        assertEquals("0", exifNumber(0.0, 2))
        assertNull(exifNumber(Double.POSITIVE_INFINITY))
    }
}
