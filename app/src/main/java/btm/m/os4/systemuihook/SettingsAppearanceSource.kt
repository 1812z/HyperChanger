package btm.m.os4.systemuihook

import android.content.Context
import android.net.Uri

data class SettingsAppearanceSource(
    val slot: String,
    val uri: Uri,
    val mime: String,
    val size: Long,
    val modified: Long,
    val enabled: Boolean,
    val opacity: Int,
    val blur: Float,
    val fontMode: Int,
    val scale: Int,
    val logoMode: Int,
    val lightCardOpacity: Int,
) {
    val exists: Boolean get() = enabled && size >= 0L
    val isVideo: Boolean get() = mime.startsWith("video/")
    fun cacheKey(): String = "$slot:$mime:$size:$modified:$enabled:$opacity:$blur:$fontMode:$scale:$logoMode:$lightCardOpacity"
}

object SettingsAppearanceSources {
    fun uri(slot: String) = Uri.Builder()
        .scheme("content")
        .authority(SETTINGS_APPEARANCE_AUTHORITY)
        .appendPath(slot)
        .build()

    fun query(context: Context, slot: String): SettingsAppearanceSource {
        val uri = uri(slot)
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val mime = cursor.string(SettingsAppearanceProvider.COLUMN_MIME)
                SettingsAppearanceSource(
                    slot = slot,
                    uri = uri,
                    mime = mime,
                    size = cursor.long(SettingsAppearanceProvider.COLUMN_SIZE),
                    modified = cursor.long(SettingsAppearanceProvider.COLUMN_MODIFIED),
                    enabled = cursor.int(SettingsAppearanceProvider.COLUMN_ENABLED) != 0,
                    opacity = cursor.int(SettingsAppearanceProvider.COLUMN_OPACITY).coerceIn(0, 100),
                    blur = cursor.float(SettingsAppearanceProvider.COLUMN_BLUR).coerceIn(0f, 20f),
                    fontMode = cursor.int(SettingsAppearanceProvider.COLUMN_FONT).coerceIn(0, 2),
                    scale = cursor.int(SettingsAppearanceProvider.COLUMN_SCALE).coerceIn(50, 200),
                    logoMode = cursor.int(SettingsAppearanceProvider.COLUMN_LOGO_MODE).coerceIn(LOGO_MODE_SYSTEM, LOGO_MODE_KEEP_ADVANCED_MATERIAL),
                    lightCardOpacity = cursor.int(SettingsAppearanceProvider.COLUMN_LIGHT_CARD_OPACITY).coerceIn(0, 100),
                )
            } ?: missing(slot, uri)
        }.getOrElse { missing(slot, uri) }
    }

    private fun missing(slot: String, uri: Uri) = SettingsAppearanceSource(slot, uri, "", -1L, -1L, false, 100, 0f, 0, 100, LOGO_MODE_SYSTEM, 100)

    private fun android.database.Cursor.index(name: String) = getColumnIndex(name)
    private fun android.database.Cursor.string(name: String): String = getString(index(name)).orEmpty()
    private fun android.database.Cursor.int(name: String): Int = getInt(index(name))
    private fun android.database.Cursor.float(name: String): Float = getFloat(index(name))
    private fun android.database.Cursor.long(name: String): Long = getLong(index(name))
}
