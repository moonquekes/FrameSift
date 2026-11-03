package cn.devcxl.photosync.album

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import cn.devcxl.photosync.R
import cn.devcxl.photosync.databinding.ScreenCameraAlbumBinding
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/** Portrait camera roll: capture-date browsing, photo-first selection and verified original imports. */
internal class CameraAlbumScreen(
    private val activity: ComponentActivity,
    private val controller: UsbPtpConnectionController,
    private val container: FrameLayout,
    saveOriginal: (File, String) -> Uri?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binding = ScreenCameraAlbumBinding.inflate(activity.layoutInflater, container, true)
    private val thumbnails = AlbumThumbnails(activity, controller, scope)
    private val transfers = AlbumTransfers(activity, controller, saveOriginal)
    private val menus = AlbumMenus(activity)
    private var photos: List<CameraPhoto> = emptyList()
    private val selected = linkedSetOf<Int>()
    private val imported = mutableSetOf<Int>()
    private var sort = AlbumSort.NEWEST
    private var date: String? = null
    private val collapsedDays = mutableSetOf<String>()
    private var mergeBursts = true
    private var selectionMode = false
    private var connected = false
    private var importing = false
    private var indexJob: Job? = null
    private var preview: AlbumPreviewDialog? = null
    private var completedWeight = 0L
    private var currentWeight = 1L
    private var totalWeight = 1L
    private var currentTransferHandle = 0
    private val adapter = AlbumGridAdapter(
        ::thumbnail, { it in selected }, { it in imported }, { selectionMode }, ::open,
        ::toggle, { selectionMode = true; toggle(it) }, ::selectDay,
        { day -> if (!collapsedDays.add(day)) collapsedDays.remove(day); rebuild() },
        { day -> photos.filter { it.day == day }.let { it.isNotEmpty() && it.all { photo -> photo.handle in selected } } }
    )
    private val chrome = ScrollChrome(binding.galleryChromeContainer, binding.galleryChrome, binding.galleryGrid)

    init {
        val grid = GridLayoutManager(activity, 3)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (adapter.currentList.getOrNull(position) is AlbumTile.Day) 3 else 1
        }
        binding.galleryGrid.layoutManager = grid
        binding.galleryGrid.adapter = adapter
        binding.galleryGrid.itemAnimator = null
        binding.galleryBurst.isSelected = mergeBursts
        binding.galleryBurst.setOnClickListener {
            mergeBursts = !mergeBursts
            binding.galleryBurst.isSelected = mergeBursts
            if (mergeBursts) Toast.makeText(activity, R.string.gallery_burst_explanation, Toast.LENGTH_LONG).show()
            rebuild()
        }
        binding.galleryDate.setOnClickListener { menus.dates(photos) { date = it; if (it != null) collapsedDays.remove(it); chrome.show(); rebuild(); binding.galleryGrid.scrollToPosition(0) } }
        binding.gallerySort.setOnClickListener { menus.sort(sort) { sort = it; chrome.show(); rebuild(); binding.galleryGrid.scrollToPosition(0) } }
        binding.galleryRefresh.setOnClickListener { if (connected) loadIndex(force = true) else controller.scanAndConnectIfPossible() }
        binding.galleryImport.setOnClickListener {
            if (selected.isEmpty()) { selectionMode = !selectionMode; updateSelection() } else importSelected()
        }
        updateSelection()
    }

    fun onConnectionState(state: UsbPtpConnectionState) {
        val wasConnected = connected
        connected = state is UsbPtpConnectionState.Connected
        binding.galleryConnection.setText(when (state) {
            is UsbPtpConnectionState.Connected -> R.string.gallery_connected
            is UsbPtpConnectionState.PermissionRequested -> R.string.gallery_permission
            is UsbPtpConnectionState.Connecting -> R.string.gallery_connecting
            is UsbPtpConnectionState.Disconnected -> if (state.reason == "index_failed") R.string.gallery_read_connection_failed else R.string.gallery_disconnected
            is UsbPtpConnectionState.Error -> if (state.reason == "permission_denied") R.string.gallery_permission else R.string.gallery_read_connection_failed
            else -> R.string.gallery_waiting
        })
        if (connected && !wasConnected) loadIndex()
        if (!connected) {
            indexJob?.cancel()
            preview?.close()
            preview = null
            binding.galleryIndexProgress.visibility = View.GONE
        }
        updateSelection()
    }

    private fun loadIndex(force: Boolean = false) {
        if (importing) return
        indexJob?.cancel()
        selected.clear()
        binding.galleryIndexProgress.visibility = View.VISIBLE
        binding.galleryIndexProgress.isIndeterminate = true
        binding.galleryIndexStatus.setText(R.string.gallery_indexing)
        binding.galleryEmptyTitle.setText(R.string.gallery_indexing)
        binding.galleryEmptyBody.visibility = View.GONE
        indexJob = scope.launch {
            try {
                photos = AlbumIndexLoader(activity, controller).load(force) { entries, scanned, total, complete ->
                    if (photos.firstOrNull()?.cacheToken != entries.firstOrNull()?.cacheToken) {
                        imported.clear()
                        if (entries.isEmpty()) { date = null; collapsedDays.clear() }
                    }
                    photos = entries
                    binding.galleryIndexStatus.text = if (complete) summary() else
                        activity.getString(R.string.gallery_index_progress, scanned, total)
                    binding.galleryIndexProgress.isIndeterminate = false
                    binding.galleryIndexProgress.progress = if (total > 0) scanned * 100 / total else 0
                    rebuild()
                }
                binding.galleryIndexStatus.text = summary()
                binding.galleryEmptyTitle.setText(R.string.gallery_no_photos)
                Timber.tag("USB_COMPAT").i(
                    "GALLERY_INDEX_COMPLETE jpeg=%d dates=%d candidates=%d", photos.size,
                    photos.map { it.day }.distinct().size, burstCandidates(photos).count { it.size > 1 }
                )
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.galleryIndexStatus.setText(R.string.gallery_index_failed)
                binding.galleryEmptyTitle.setText(R.string.gallery_index_failed)
                Timber.tag("USB_COMPAT").e(e, "GALLERY_INDEX_FAILED")
                controller.disconnect("index_failed")
            } finally { binding.galleryIndexProgress.visibility = View.GONE }
        }
    }

    private fun summary(): String = activity.getString(
        R.string.gallery_index_done, photos.size, photos.map { it.day }.filter { it != "unknown" }.distinct().size
    )

    private fun rebuild() {
        adapter.submitList(buildAlbumTiles(photos, sort, date, mergeBursts, collapsedDays))
        binding.galleryDate.text = date?.let {
            dateLabel(it, short = true).ifBlank { activity.getString(R.string.gallery_date_unknown) } + " ▾"
        } ?: activity.getString(R.string.gallery_all_dates)
        binding.gallerySort.setText(sortLabel(sort))
        binding.galleryEmpty.visibility = if (photos.none { date == null || it.day == date }) View.VISIBLE else View.GONE
        updateSelection()
        chrome.ensureVisibleAtTop()
    }

    private fun thumbnail(photo: CameraPhoto, done: (Bitmap?) -> Unit) {
        val cached = thumbnails.cached(photo)
        if (cached != null) { done(cached); return }
        scope.launch {
            try { done(thumbnails.get(photo)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { done(null); Timber.tag("USB_COMPAT").w(e, "GALLERY_THUMB_FAILED handle=0x%08x", photo.handle) }
        }
    }

    private fun toggle(members: List<CameraPhoto>) {
        if (importing) return
        if (members.all { it.handle in selected }) members.forEach { selected.remove(it.handle) }
        else members.forEach { selected.add(it.handle) }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateSelection()
    }

    private fun selectDay(day: String) { toggle(photos.filter { it.day == day }) }

    private fun open(members: List<CameraPhoto>) {
        if (importing) return
        val sequence = buildPreviewSequence(photos, sort, date, members.first().handle, mergeBursts)
        Timber.tag("USB_COMPAT").i("GALLERY_PREVIEW_SEQUENCE total=%d initial=%d stackMembers=%d",
            sequence.photos.size, sequence.initialIndex + 1, members.size)
        preview = AlbumPreviewDialog(activity, sequence.photos, sequence.initialIndex, sequence.bursts, thumbnails, transfers,
            { it in selected }, { toggle(listOf(it)) }).also { it.show() }
    }

    private fun updateSelection() {
        binding.gallerySelected.text = if (selected.isNotEmpty()) activity.getString(R.string.gallery_selected, selected.size)
            else activity.getString(if (selectionMode) R.string.gallery_selection_hint else R.string.gallery_select_hint)
        binding.galleryImport.text = when {
            importing -> activity.getString(R.string.gallery_import_busy)
            selected.isNotEmpty() -> activity.getString(R.string.gallery_import, selected.size)
            selectionMode -> activity.getString(R.string.gallery_cancel_selection)
            else -> activity.getString(R.string.gallery_select)
        }
        binding.galleryImport.isEnabled = !importing && connected
        binding.galleryRefresh.isEnabled = !importing
    }

    private fun importSelected() {
        if (importing || !connected) return
        val batch = sortPhotos(photos.filter { it.handle in selected }, sort)
        if (batch.isEmpty()) return
        importing = true
        completedWeight = 0
        totalWeight = batch.sumOf { it.bytes.coerceAtLeast(1) }
        binding.galleryTransfer.visibility = View.VISIBLE
        updateSelection()
        scope.launch {
            var success = 0
            var failures = 0
            Timber.tag("USB_COMPAT").i("GALLERY_IMPORT_BEGIN selected=%d", batch.size)
            try {
                for ((index, photo) in batch.withIndex()) {
                    ensureActive()
                    currentWeight = photo.bytes.coerceAtLeast(1)
                    currentTransferHandle = photo.handle
                    binding.galleryTransferTitle.text = activity.getString(R.string.gallery_importing, index + 1, batch.size)
                    binding.galleryTransferStage.setText(R.string.gallery_keep_connected)
                    try {
                        transfers.importOriginal(photo) { stage -> scope.launch { renderStage(stage) } }
                        selected.remove(photo.handle)
                        imported.add(photo.handle)
                        success++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures++; Timber.tag("USB_COMPAT").e(e, "GALLERY_IMPORT_FAILED handle=0x%08x", photo.handle) }
                    completedWeight += currentWeight
                    renderProgress((completedWeight * 100 / totalWeight).toInt())
                }
            } finally {
                importing = false
                selectionMode = false
                transfers.clearPins()
                binding.galleryTransferTitle.text = if (failures == 0)
                    activity.getString(R.string.gallery_import_complete, success)
                    else activity.getString(R.string.gallery_import_failed, success, failures)
                binding.galleryTransferStage.text = ""
                Timber.tag("USB_COMPAT").i("GALLERY_IMPORT_END success=%d failed=%d", success, failures)
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                updateSelection()
                scope.launch { delay(4500); if (!importing) binding.galleryTransfer.visibility = View.GONE }
            }
        }
    }

    private fun renderStage(stage: ImportStage) {
        binding.galleryTransferStage.setText(when (stage) {
            ImportStage.READING -> R.string.gallery_keep_connected
            ImportStage.SAVING -> R.string.gallery_writing
            ImportStage.VERIFYING -> R.string.gallery_verify
        })
    }

    private fun renderProgress(percent: Int) {
        binding.galleryTransferProgress.progress = percent.coerceIn(0, 100)
        binding.galleryTransferPercent.text = activity.getString(R.string.gallery_percent, percent.coerceIn(0, 100))
    }

    fun onUsbProgress(handle: Int, total: Long, done: Long) {
        preview?.onProgress(handle, total, done)
        if (importing && handle == currentTransferHandle) scope.launch {
            val fraction = if (total > 0) done.toDouble() / total else 0.0
            renderProgress(((completedWeight + currentWeight * fraction * 0.97) * 100 / totalWeight).toInt())
        }
    }

    fun show() { container.visibility = View.VISIBLE }

    fun handleBack(): Boolean {
        if (selected.isNotEmpty() || selectionMode) { selected.clear(); selectionMode = false; adapter.notifyItemRangeChanged(0, adapter.itemCount); updateSelection(); return true }
        if (date != null) { date = null; rebuild(); return true }
        return false
    }

    fun close() { preview?.close(); chrome.close(); scope.cancel(); thumbnails.clearMemory() }
}
