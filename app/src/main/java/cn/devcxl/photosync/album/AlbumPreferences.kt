package cn.devcxl.photosync.album

import android.content.Context

internal class AlbumPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("camera-album", Context.MODE_PRIVATE)

    var mergeBursts: Boolean
        get() = preferences.getBoolean("merge-bursts", true)
        set(value) { preferences.edit().putBoolean("merge-bursts", value).apply() }

    var exifEnabled: Boolean
        get() = preferences.getBoolean("exif-enabled", true)
        set(value) { preferences.edit().putBoolean("exif-enabled", value).apply() }

    var exifFields: Set<ExifField>
        get() {
            val keys = preferences.getStringSet("exif-fields", ExifField.defaults.map { it.name }.toSet()).orEmpty()
            return ExifField.entries.filter { it.name in keys }.toSet()
        }
        set(value) { preferences.edit().putStringSet("exif-fields", value.map { it.name }.toSet()).apply() }

    fun readBurst(): BurstSettings = BurstSettings(
        preferences.getInt("burst-gap-seconds", 1).coerceIn(0, 5),
        preferences.getInt("burst-minimum", 3).coerceIn(2, 10),
        preferences.getBoolean("burst-consecutive", true)
    )

    fun saveBurst(settings: BurstSettings) {
        preferences.edit().putInt("burst-gap-seconds", settings.maxGapSeconds)
            .putInt("burst-minimum", settings.minimumPhotos)
            .putBoolean("burst-consecutive", settings.requireConsecutive).apply()
    }
}
