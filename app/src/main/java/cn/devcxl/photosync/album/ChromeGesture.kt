package cn.devcxl.photosync.album

/** Positive delta means the finger moved up; tiny reversals must not flip toolbar state. */
internal class ChromeGesture(private val hideDistance: Float, private val showDistance: Float) {
    var hidden = false
        private set
    private var travel = 0f

    fun beginDrag() { travel = 0f }
    fun show() { hidden = false; travel = 0f }

    fun drag(delta: Float, canHide: Boolean): Boolean? {
        travel = if (travel * delta < 0) delta else travel + delta
        val target = when {
            travel > hideDistance && canHide -> true
            travel < -showDistance -> false
            else -> return null
        }
        if (target == hidden) return null
        hidden = target
        travel = 0f
        return target
    }
}
