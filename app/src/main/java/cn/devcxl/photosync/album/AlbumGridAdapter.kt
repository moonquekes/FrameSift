package cn.devcxl.photosync.album

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.devcxl.photosync.R
import java.text.SimpleDateFormat
import java.util.Locale

internal class AlbumGridAdapter(
    private val thumbnail: (CameraPhoto, (Bitmap?) -> Unit) -> Unit,
    private val selected: (Int) -> Boolean,
    private val imported: (Int) -> Boolean,
    private val selectionMode: () -> Boolean,
    private val open: (List<CameraPhoto>) -> Unit,
    private val toggle: (List<CameraPhoto>) -> Unit,
    private val beginSelection: (List<CameraPhoto>) -> Unit,
    private val chooseDay: (String) -> Unit,
    private val toggleDay: (String) -> Unit,
    private val daySelected: (String) -> Boolean
) : ListAdapter<AlbumTile, RecyclerView.ViewHolder>(Difference) {
    override fun getItemViewType(position: Int): Int = if (getItem(position) is AlbumTile.Day) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == 0) R.layout.item_gallery_day else R.layout.item_gallery_photo
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return if (viewType == 0) DayHolder(view) else PhotoHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val tile = getItem(position)) {
            is AlbumTile.Day -> {
                val title = holder.itemView.findViewById<TextView>(R.id.dayTitle)
                title.text = title.context.getString(
                    R.string.gallery_date_count,
                    (if (tile.collapsed) "▸ " else "▾ ") + dateLabel(tile.key).ifBlank {
                        title.context.getString(R.string.gallery_date_unknown)
                    }, tile.count
                )
                holder.itemView.setOnClickListener { toggleDay(tile.key) }
                holder.itemView.findViewById<TextView>(R.id.dayChoose).apply {
                    setText(if (daySelected(tile.key)) R.string.gallery_day_unselect else R.string.gallery_choose_day)
                    setOnClickListener { chooseDay(tile.key) }
                }
            }
            is AlbumTile.Photo -> bindPhoto(holder as PhotoHolder, tile)
        }
    }

    private fun bindPhoto(holder: PhotoHolder, tile: AlbumTile.Photo) {
        val view = holder.itemView
        val photo = tile.cover
        holder.identity = photo.identity
        view.clipToOutline = true
        holder.image.setImageResource(R.drawable.gallery_camera)
        holder.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        thumbnail(photo) { bitmap ->
            if (holder.identity == photo.identity && bitmap != null) {
                holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.image.setImageBitmap(bitmap)
            }
        }
        val chosen = tile.members.count { selected(it.handle) }
        val check = view.findViewById<TextView>(R.id.tileCheck)
        check.isSelected = chosen > 0
        check.text = if (chosen == 0) "" else view.context.getString(
            if (chosen == tile.members.size) R.string.gallery_selected_mark else R.string.gallery_partial_mark
        )
        view.findViewById<View>(R.id.tileSelectedOverlay).visibility = if (chosen > 0) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.tileBurst).apply {
            visibility = if (tile.members.size > 1) View.VISIBLE else View.GONE
            text = context.getString(R.string.gallery_burst_badge, tile.members.size)
        }
        view.findViewById<View>(R.id.tileImported).visibility =
            if (tile.members.all { imported(it.handle) }) View.VISIBLE else View.GONE
        check.setOnClickListener { toggle(tile.members) }
        view.setOnClickListener { if (selectionMode()) toggle(tile.members) else open(tile.members) }
        view.setOnLongClickListener { beginSelection(tile.members); true }
    }

    private class DayHolder(view: View) : RecyclerView.ViewHolder(view)
    private class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.tileImage)
        var identity: String = ""
    }

    private object Difference : DiffUtil.ItemCallback<AlbumTile>() {
        override fun areItemsTheSame(old: AlbumTile, new: AlbumTile): Boolean = when {
            old is AlbumTile.Day && new is AlbumTile.Day -> old.key == new.key
            old is AlbumTile.Photo && new is AlbumTile.Photo -> old.cover.handle == new.cover.handle
            else -> false
        }
        override fun areContentsTheSame(old: AlbumTile, new: AlbumTile): Boolean = old == new
    }
}

internal fun dateLabel(day: String, short: Boolean = false): String = runCatching {
    val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).parse(day)
    SimpleDateFormat(if (short) "M月d日" else "yyyy年M月d日", Locale.SIMPLIFIED_CHINESE).format(requireNotNull(date))
}.getOrDefault("")
