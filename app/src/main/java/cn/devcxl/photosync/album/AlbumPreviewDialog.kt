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
import cn.devcxl.photosync.ptp.manager.CameraReadPriority
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
    private val mediumView: SubsamplingScaleImageView = root.findViewById(R.id.previewMedium)
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
    private var mediumLoaded = false
    private var neighborJob: Job? = null
    private var direction = 1
    private var quickZoom = 1f
    private var touchInProgress = false
    private var multiTouch = false

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
        mediumView.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
            override fun onCenterChanged(newCenter: PointF?, origin: Int) = Unit
            override fun onScaleChanged(newScale: Float, origin: Int) {
                // Above one screen pixel per preview pixel, a user zoom benefits from the original.
                if (mediumLoaded && origin == SubsamplingScaleImageView.ORIGIN_TOUCH && newScale > 1.05f) {
                    showOriginal(photos[index])
                }
            }
        })
        bindGestures()
        dialog.setOnDismissListener {
            scope.cancel()
            original.recycle()
            mediumView.recycle()
            transfers.clearPins()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.let(AlbumSystemBars::show)
        render()
    }

    private fun bindGestures() {
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(first: MotionEvent?, last: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val image = if (mediumLoaded) mediumView else original
                if (first == null || multiTouch || abs(velocityX) < abs(velocityY)) return false
                if (image.scale > image.minScale * 1.15f) return false
                if (abs(last.x - first.x) < 80 * activity.resources.displayMetrics.density) return false
                move(if (last.x < first.x) 1 else -1)
                return true
            }
        })
        val zoom = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { quickZoom = 1f; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                quickZoom = (quickZoom * detector.scaleFactor).coerceIn(1f, 4f)
                if (quickZoom > 1.12f) showOriginal(photos[index])
                return true
            }
        })
        val imageTouch = View.OnTouchListener { _, event ->
            trackPreviewTouch(event)
            gestures.onTouchEvent(event)
        }
        original.setOnTouchListener(imageTouch)
        mediumView.setOnTouchListener(imageTouch)
        quick.isClickable = true
        quick.setOnTouchListener { _, event ->
            trackPreviewTouch(event)
            zoom.onTouchEvent(event)
            gestures.onTouchEvent(event)
            true
        }
    }

    private fun trackPreviewTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchInProgress = true
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchInProgress = false
                // Let the current viewer finish handling the event before reading its viewport.
                original.post { revealOriginal() }
            }
        }
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
        direction = if (delta < 0) -1 else 1
        render()
    }

    private fun renderSelection() {
        pick.isSelected = selected(photos[index].handle)
        pick.setText(if (pick.isSelected) R.string.gallery_picked_this else R.string.gallery_pick_this)
    }

    private fun render() {
        currentJob?.cancel()
        metadataJob?.cancel()
        neighborJob?.cancel()
        original.setOnImageEventListener(null)
        mediumView.setOnImageEventListener(null)
        original.recycle()
        mediumView.recycle()
        mediumView.visibility = View.GONE
        exif.clear()
        val photo = photos[index]
        transfers.pin(listOf(photo))
        loadingCurrent = false
        originalLoaded = false
        mediumLoaded = false
        quickZoom = 1f
        touchInProgress = false
        multiTouch = false
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
                if (photo.documentUri != null && transfers.cached(photo) == null) showMedium(photo)
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
        if (!mediumLoaded) mediumView.visibility = View.GONE
        loadOriginal.visibility = View.GONE
        loading.visibility = View.VISIBLE
        loading.text = activity.getString(R.string.gallery_preview_loading, 0)
        currentJob = scope.launch {
            try {
                val file = cached ?: transfers.download(photo)
                original.orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
                original.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoaded() {
                        if (photos[index].identity != photo.identity) return
                        Timber.tag("USB_COMPAT").i("GALLERY_PREVIEW_READY handle=0x%08x elapsedMs=%d cached=%b",
                            photo.handle, (System.nanoTime() - startedAt) / 1_000_000, cached != null)
                        revealOriginal()
                    }
                    override fun onImageLoadError(e: Exception) { originalFailed() }
                    override fun onTileLoadError(e: Exception) { if (!originalLoaded) originalFailed() }
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

    private fun revealOriginal() {
        if (!dialog.isShowing || !loadingCurrent || !original.isImageLoaded || touchInProgress) return
        original.restorePreviewViewport(mediumView.takeIf { mediumLoaded }, quickZoom)
        originalLoaded = true
        loadingCurrent = false
        mediumLoaded = false
        quick.visibility = View.GONE
        mediumView.visibility = View.GONE
        mediumView.recycle()
        loading.visibility = View.GONE
        prefetchNeighbor()
    }

    private fun originalFailed() {
        loadingCurrent = false
        original.setOnImageEventListener(null)
        original.recycle()
        loading.visibility = View.VISIBLE
        loadOriginal.visibility = View.VISIBLE
        loading.setText(R.string.gallery_preview_failed)
    }

    private suspend fun showMedium(photo: CameraPhoto) {
        val started = System.nanoTime()
        val medium = transfers.preview(photo) ?: return
        if (photos[index].identity != photo.identity || loadingCurrent) return
        mediumView.orientation = when (medium.orientation) { 6 -> 90; 8 -> 270; 3 -> 180; else -> 0 }
        mediumView.visibility = View.VISIBLE
        mediumView.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onImageLoaded() {
                if (photos[index].identity != photo.identity || loadingCurrent) return
                mediumLoaded = true
                quick.visibility = View.GONE; loading.visibility = View.GONE
                Timber.tag("USB_COMPAT").i("GALLERY_MEDIUM_READY handle=0x%08x elapsedMs=%d",
                    photo.handle, (System.nanoTime() - started) / 1_000_000)
                prefetchNeighbor()
            }
            override fun onImageLoadError(e: Exception) {
                mediumView.visibility = View.GONE
                Timber.tag("USB_COMPAT").w(e, "GALLERY_MEDIUM_DECODE_FAILED handle=0x%08x", photo.handle)
            }
        })
        mediumView.setImage(ImageSource.uri(medium.file.path))
    }

    private fun prefetchNeighbor() {
        neighborJob?.cancel()
        val neighbor = photos.getOrNull(index + direction)?.takeIf { it.documentUri != null } ?: return
        neighborJob = scope.launch {
            try { transfers.preview(neighbor, CameraReadPriority.NEIGHBOR_PREVIEW) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Timber.tag("USB_COMPAT").w(e, "PREVIEW_PREFETCH_FAILED") }
        }
    }

    fun close() { dialog.dismiss() }
}
