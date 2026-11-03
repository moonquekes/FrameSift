package cn.devcxl.photosync.album

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A grid cell that keeps its height equal to its available column width. */
class SquarePhotoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
    }
}
