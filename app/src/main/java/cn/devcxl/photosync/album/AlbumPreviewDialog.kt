package cn.devcxl.photosync.album

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.devcxl.photosync.R
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

/** Large original preview with per-image selection; opening it never publishes an image to the gallery. */
internal class AlbumPreviewDialog(
    private val activity: ComponentActivity,
    private val photos: List<CameraPhoto>,
    initialIndex: Int,
    private val bursts: Map<Int, BurstPosition>,
    private val thumbnails: AlbumThumbnails,
    private val transfers: AlbumTransfers,
    private val selected: (Int) -> Boolean,
    private val toggle: (CameraPhoto) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dialog = object : Dialog(activity, R.style.Theme_PhotoSync) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus) window?.let(AlbumSystemBars::show)
        }
    }
    private val root = activity.layoutInflater.inflate(R.layout.dialog_gallery_preview, null)
    private val original: SubsamplingScaleImageView = root.findViewById(R.id.previewOriginal)
    private val quick: ImageView = root.findViewById(R.id.previewQuick)
    private val loading: TextView = root.findViewById(R.id.previewLoading)
    private val pick: TextView = root.findViewById(R.id.previewPick)
    private var index = initialIndex
    private var currentJob: Job? = null
    private var prefetchJob: Job? = null
    private var direction = 1
    private var loadingCurrent = false

    fun show() {
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.findViewById<View>(R.id.previewBack).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.previewPrevious).setOnClickListener { move(-1) }
        root.findViewById<View>(R.id.previewNext).setOnClickListener { move(1) }
        pick.setOnClickListener { toggle(photos[index]); renderSelection() }
        original.orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(first: MotionEvent?, last: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (first == null || abs(velocityX) < abs(velocityY) || original.scale > original.minScale * 1.15f) return false
                if (abs(last.x - first.x) < 80 * activity.resources.displayMetrics.density) return false
                move(if (last.x < first.x) 1 else -1)
                return true
            }
        })
        original.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        quick.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        dialog.setOnDismissListener { scope.cancel(); original.recycle(); transfers.clearPins() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.let(AlbumSystemBars::show)
        render()
    }

    fun onProgress(handle: Int, total: Long, done: Long) {
        if (!dialog.isShowing || !loadingCurrent || photos[index].handle != handle) return
        scope.launch {
            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0
            loading.text = activity.getString(R.string.gallery_preview_loading, percent)
        }
    }

    private fun move(delta: Int) {
        val next = (index + delta).coerceIn(0, photos.lastIndex)
        if (next == index) return
        direction = delta
        index = next
        render()
    }

    private fun renderSelection() {
        pick.isSelected = selected(photos[index].handle)
        pick.setText(if (pick.isSelected) R.string.gallery_picked_this else R.string.gallery_pick_this)
    }

    private fun render() {
        currentJob?.cancel()
        prefetchJob?.cancel()
        original.recycle()
        val photo = photos[index]
        val adjacent = photos.getOrNull(index + direction)
        transfers.pin(listOfNotNull(photo, adjacent))
        loadingCurrent = true
        quick.visibility = View.VISIBLE
        quick.setImageBitmap(thumbnails.cached(photo))
        loading.visibility = View.VISIBLE
        loading.text = activity.getString(R.string.gallery_preview_loading, 0)
        val burst = bursts[photo.handle]
        root.findViewById<TextView>(R.id.previewPosition).text = if (burst == null)
            activity.getString(R.string.gallery_preview_position, index + 1, photos.size)
        else activity.getString(R.string.gallery_preview_burst, index + 1, photos.size, burst.index, burst.count)
        root.findViewById<View>(R.id.previewPrevious).alpha = if (index > 0) 1f else 0.25f
        root.findViewById<View>(R.id.previewNext).alpha = if (index < photos.lastIndex) 1f else 0.25f
        renderSelection()
        currentJob = scope.launch {
            try {
                if (thumbnails.cached(photo) == null) quick.setImageBitmap(thumbnails.get(photo))
                val file = transfers.download(photo)
                loadingCurrent = false
                original.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoaded() { quick.visibility = View.GONE; loading.visibility = View.GONE }
                    override fun onImageLoadError(e: Exception) { loading.setText(R.string.gallery_preview_failed) }
                })
                original.setImage(ImageSource.uri(file.absolutePath))
                Timber.tag("USB_COMPAT").i("GALLERY_PREVIEW handle=0x%08x bytes=%d", photo.handle, file.length())
                if (adjacent != null) prefetchJob = scope.launch {
                    try {
                        val next = transfers.download(adjacent)
                        Timber.tag("USB_COMPAT").i("GALLERY_PREFETCH handle=0x%08x bytes=%d", adjacent.handle, next.length())
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Timber.tag("USB_COMPAT").w(e, "GALLERY_PREFETCH_FAILED handle=0x%08x", adjacent.handle) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading.setText(R.string.gallery_preview_failed)
                Timber.tag("USB_COMPAT").w(e, "GALLERY_PREVIEW_FAILED handle=0x%08x", photo.handle)
            }
        }
    }

    fun close() { dialog.dismiss() }
}
