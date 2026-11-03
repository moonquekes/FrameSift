package cn.devcxl.photosync.album

import android.app.AlertDialog
import android.content.Context
import cn.devcxl.photosync.R

internal class AlbumMenus(private val context: Context) {
    fun dates(photos: List<CameraPhoto>, choose: (String?) -> Unit) {
        val days = photos.groupBy { it.day }.keys.sortedWith(compareBy<String> { it == "unknown" }.thenByDescending { it })
        val options = listOf(context.getString(R.string.gallery_all_dates).removeSuffix(" ▾")) + days.map { day ->
            context.getString(R.string.gallery_date_count,
                dateLabel(day).ifBlank { context.getString(R.string.gallery_date_unknown) }, photos.count { it.day == day })
        }
        AlertDialog.Builder(context).setTitle(R.string.gallery_date_title)
            .setItems(options.toTypedArray()) { _, index -> choose(if (index == 0) null else days[index - 1]) }.show()
    }

    fun sort(current: AlbumSort, choose: (AlbumSort) -> Unit) {
        val values = AlbumSort.entries
        val labels = values.map { context.getString(sortLabel(it)) }.toTypedArray()
        AlertDialog.Builder(context).setTitle(R.string.gallery_sort_title)
            .setSingleChoiceItems(labels, current.ordinal) { dialog, index -> choose(values[index]); dialog.dismiss() }.show()
    }
}

internal fun sortLabel(sort: AlbumSort): Int = when (sort) {
    AlbumSort.NEWEST -> R.string.gallery_newest
    AlbumSort.OLDEST -> R.string.gallery_oldest
    AlbumSort.NAME_ASC -> R.string.gallery_name_asc
    AlbumSort.NAME_DESC -> R.string.gallery_name_desc
}
