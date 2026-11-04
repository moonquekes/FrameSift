package cn.devcxl.photosync.album

import android.app.Dialog
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import java.io.File

/** Cached preview first; full JPEG is requested only by an explicit action or a zoom gesture. */
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
    private val loadOriginal: View = root.findViewById(R.id.previewLoadOriginal)
    private val exif = PreviewExif(activity, root).apply { requestData = { showMetadata(photos[index]) } }
    private var index = initialIndex
    private var currentJob: Job? = null
    private var metadataJob: Job? = null
    private var loadingCurrent = false
    private var originalLoaded = false
    private var requestedZoom = 1f

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
        loadOriginal.setOnClickListener { showOriginal(photos[index]) }
        original.orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
        bindGestures()
        dialog.setOnDismissListener { scope.cancel(); original.recycle(); transfers.clearPins() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.let(AlbumSystemBars::show)
        render()
    }

    private fun bindGestures() {
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(first: MotionEvent?, last: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (first == null || abs(velocityX) < abs(velocityY) || original.scale > original.minScale * 1.15f) return false
                if (abs(last.x - first.x) < 80 * activity.resources.displayMetrics.density) return false
                move(if (last.x < first.x) 1 else -1)
                return true
            }
        })
        val zoom = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { requestedZoom = 1f; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                requestedZoom = (requestedZoom * detector.scaleFactor).coerceIn(1f, 4f)
                if (requestedZoom > 1.12f) showOriginal(photos[index])
                return true
            }
        })
        original.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        quick.isClickable = true
        quick.setOnTouchListener { _, event -> zoom.onTouchEvent(event); gestures.onTouchEvent(event); true }
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
        index = next
        render()
    }

    private fun renderSelection() {
        pick.isSelected = selected(photos[index].handle)
        pick.setText(if (pick.isSelected) R.string.gallery_picked_this else R.string.gallery_pick_this)
    }

    private fun render() {
        currentJob?.cancel()
        metadataJob?.cancel()
        original.recycle()
        exif.clear()
        val photo = photos[index]
        transfers.pin(listOf(photo))
        loadingCurrent = false
        originalLoaded = false
        requestedZoom = 1f
        quick.visibility = View.VISIBLE
        quick.setImageBitmap(thumbnails.cached(photo))
        loading.visibility = View.VISIBLE
        loading.setText(R.string.gallery_thumb_only)
        loadOriginal.visibility = View.VISIBLE
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
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Timber.tag("USB_COMPAT").w(e, "PREVIEW_THUMB_FAILED") }
        }
        val cached = transfers.cached(photo)
        if (cached != null) showOriginal(photo, cached) else showMetadata(photo)
    }

    private fun showMetadata(photo: CameraPhoto) {
        if (!exif.needsData || metadataJob?.isActive == true) return
        metadataJob = scope.launch {
            try { exif.load(transfers.metadata(photo), photo.handle) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Timber.tag("USB_COMPAT").w(e, "PREVIEW_EXIF_FAILED handle=0x%08x", photo.handle) }
        }
    }

    private fun showOriginal(photo: CameraPhoto, cached: File? = null) {
        if (loadingCurrent || originalLoaded) return
        currentJob?.cancel(); metadataJob?.cancel()
        val startedAt = System.nanoTime()
        loadingCurrent = true
        loadOriginal.visibility = View.GONE
        loading.text = activity.getString(R.string.gallery_preview_loading, 0)
        currentJob = scope.launch {
            try {
                val file = cached ?: transfers.download(photo)
                original.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoaded() {
                        originalLoaded = true; loadingCurrent = false
                        quick.visibility = View.GONE; loading.visibility = View.GONE
                        if (requestedZoom > 1.12f) original.setScaleAndCenter(original.minScale * requestedZoom,
                            PointF(original.sWidth / 2f, original.sHeight / 2f))
                        Timber.tag("USB_COMPAT").i("GALLERY_PREVIEW_READY handle=0x%08x elapsedMs=%d cached=%b",
                            photo.handle, (System.nanoTime() - startedAt) / 1_000_000, cached != null)
                    }
                    override fun onImageLoadError(e: Exception) { originalFailed() }
                })
                original.setImage(ImageSource.uri(file.absolutePath))
                exif.load(file, photo.handle)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                originalFailed()
                Timber.tag("USB_COMPAT").w(e, "GALLERY_PREVIEW_FAILED handle=0x%08x", photo.handle)
            }
        }
    }

    private fun originalFailed() {
        loadingCurrent = false
        loadOriginal.visibility = View.VISIBLE
        loading.setText(R.string.gallery_preview_failed)
    }

    fun close() { dialog.dismiss() }
}
