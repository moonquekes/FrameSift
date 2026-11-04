package cn.devcxl.photosync.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChromeGestureTest {
    @Test fun shouldHideOnlyOnce_whenTheFingerKeepsMovingUp() {
        val gesture = ChromeGesture(24f, 18f)
        assertNull(gesture.drag(20f, true))
        assertEquals(true, gesture.drag(10f, true))
        repeat(20) { assertNull(gesture.drag(10f, true)) }
    }

    @Test fun shouldIgnoreSmallReversals_whenToolbarIsHidden() {
        val gesture = ChromeGesture(24f, 18f)
        gesture.drag(30f, true)
        assertNull(gesture.drag(-5f, true))
        assertNull(gesture.drag(4f, true))
        assertNull(gesture.drag(-10f, true))
        assertEquals(false, gesture.drag(-10f, true))
    }

    @Test fun shouldDiscardPreviousPartialTravel_whenANewGestureStarts() {
        val gesture = ChromeGesture(24f, 18f)
        gesture.drag(20f, true)
        gesture.beginDrag()
        assertNull(gesture.drag(20f, true))
        assertFalse(gesture.hidden)
    }

    @Test fun shouldKeepControlsAvailable_whenThereIsNoScrollableContent() {
        val gesture = ChromeGesture(24f, 18f)
        repeat(20) { assertNull(gesture.drag(30f, false)) }
        assertFalse(gesture.hidden)
    }

    @Test fun shouldResetGesture_whenControlsAreShownProgrammatically() {
        val gesture = ChromeGesture(24f, 18f)
        gesture.drag(30f, true)
        gesture.show()
        assertFalse(gesture.hidden)
        assertNull(gesture.drag(10f, true))
    }
}
