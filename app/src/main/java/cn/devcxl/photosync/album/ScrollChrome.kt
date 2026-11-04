package cn.devcxl.photosync.album

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import timber.log.Timber

/** Keeps grid bounds fixed: toolbar motion never becomes a new list scroll event. */
internal class ScrollChrome(private val wrapper: ViewGroup, private val grid: RecyclerView) {
    private val density = wrapper.resources.displayMetrics.density
    private val gesture = ChromeGesture(24 * density, 18 * density)
    private val initialPadding = grid.paddingTop
    private var previousY = 0f
    private var expandedHeight = 0
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reserveToolbarSpace() }
    private val scrolling = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(view: RecyclerView, state: Int) {
            if (state == RecyclerView.SCROLL_STATE_IDLE && !view.canScrollVertically(-1)) show()
        }
    }
    private val touching = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(view: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { previousY = event.rawY; gesture.beginDrag() }
                MotionEvent.ACTION_MOVE -> {
                    val delta = previousY - event.rawY
                    previousY = event.rawY
                    gesture.drag(delta, view.canScrollVertically(1) || view.canScrollVertically(-1))
                        ?.let(::animate)
                }
            }
            return false
        }
    }

    init {
        wrapper.addOnLayoutChangeListener(layout)
        wrapper.post { reserveToolbarSpace() }
        grid.addOnScrollListener(scrolling)
        grid.addOnItemTouchListener(touching)
    }

    fun show() { if (gesture.hidden) { gesture.show(); animate(false) } }
    fun ensureVisibleAtTop() { grid.post { if (!grid.canScrollVertically(-1)) show() } }

    private fun reserveToolbarSpace() {
        if (wrapper.height <= 0 || wrapper.height == expandedHeight) return
        expandedHeight = wrapper.height
        grid.setPadding(grid.paddingLeft, initialPadding + expandedHeight, grid.paddingRight, grid.paddingBottom)
        if (gesture.hidden) wrapper.translationY = -expandedHeight.toFloat()
    }

    private fun animate(hidden: Boolean) {
        wrapper.animate().cancel()
        wrapper.animate().translationY(if (hidden) -expandedHeight.toFloat() else 0f).setDuration(180).start()
        Timber.tag("USB_COMPAT").i("GALLERY_CHROME hidden=%b", hidden)
    }

    fun close() {
        wrapper.animate().cancel()
        wrapper.removeOnLayoutChangeListener(layout)
        grid.removeOnScrollListener(scrolling)
        grid.removeOnItemTouchListener(touching)
    }
}
