package cn.devcxl.photosync.album

import android.graphics.PointF
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import timber.log.Timber

internal fun SubsamplingScaleImageView.restorePreviewViewport(
    preview: SubsamplingScaleImageView?,
    fallbackZoom: Float
) {
    // The viewer reports center in rotated pixels, but its source dimensions are unrotated.
    val zoom = preview?.let { it.scale / it.minScale } ?: fallbackZoom
    val center = preview?.center?.let { PointF(it.x / preview.orientedWidth, it.y / preview.orientedHeight) }
        ?: PointF(0.5f, 0.5f)
    setScaleAndCenter(minScale * zoom, PointF(center.x * orientedWidth, center.y * orientedHeight))
    Timber.tag("USB_COMPAT").i(
        "GALLERY_ORIGINAL_VISIBLE zoom=%.3f centerX=%.3f centerY=%.3f rotation=%d",
        zoom, center.x, center.y, appliedOrientation
    )
}

private val SubsamplingScaleImageView.orientedWidth: Int
    get() = if (appliedOrientation == 90 || appliedOrientation == 270) sHeight else sWidth

private val SubsamplingScaleImageView.orientedHeight: Int
    get() = if (appliedOrientation == 90 || appliedOrientation == 270) sWidth else sHeight
