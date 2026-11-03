package cn.devcxl.photosync.album

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Slides the controls away on upward browsing and restores them on downward scrolling. */
internal class ScrollChrome(private val wrapper: ViewGroup, private val content: View, private val grid: RecyclerView) {
    private var expandedHeight = 0
    private var hidden = false
    private var travel = 0
    private var touchY = 0f
    private var animation: ValueAnimator? = null
    private val density = content.resources.displayMetrics.density
    private val scrolling = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
            if (dy == 0) return
            travel = if (travel * dy < 0) dy else travel + dy
            if (travel > 18 * density && !hidden) setHidden(true)
            if (travel < -10 * density && hidden) setHidden(false)
            if (!view.canScrollVertically(-1)) show()
        }
    }
    private val touching = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(view: RecyclerView, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchY = event.y
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.y - touchY > 12 * density && hidden) show()
            return false
        }
    }

    init {
        content.post { expandedHeight = content.height }
        grid.addOnScrollListener(scrolling)
        grid.addOnItemTouchListener(touching)
    }

    fun show() { setHidden(false) }

    fun ensureVisibleAtTop() { grid.post { if (!grid.canScrollVertically(-1)) show() } }

    private fun setHidden(value: Boolean) {
        if (hidden == value) return
        if (!hidden && content.height > 0) expandedHeight = content.height
        if (expandedHeight == 0) return
        hidden = value
        travel = 0
        animation?.cancel()
        content.layoutParams = content.layoutParams.apply { height = expandedHeight }
        val target = if (value) 0 else expandedHeight
        animation = ValueAnimator.ofInt(wrapper.height, target).apply {
            duration = 180
            addUpdateListener { animator ->
                val height = animator.animatedValue as Int
                wrapper.layoutParams = wrapper.layoutParams.apply { this.height = height }
                content.translationY = (height - expandedHeight).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && !value) {
                        wrapper.layoutParams = wrapper.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                        content.layoutParams = content.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                        content.translationY = 0f
                    }
                }
            })
            start()
        }
    }

    fun close() { animation?.cancel(); grid.removeOnScrollListener(scrolling); grid.removeOnItemTouchListener(touching) }
}
