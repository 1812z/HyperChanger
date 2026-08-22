package btm.m.os4.systemuihook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class SettingsAppearanceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val slot = uri.pathSegments.firstOrNull().orEmpty()
        val file = appearanceFile(slot)
        val prefs = requireContext().getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, 0)
        val columns = projection ?: COLUMNS
        val row = MatrixCursor(columns)
        val values = mapOf(
            COLUMN_MIME to prefs.getString(mimeKey(slot), "").orEmpty(),
            COLUMN_SIZE to if (file.isFile) file.length() else -1L,
            COLUMN_MODIFIED to if (file.isFile) file.lastModified() else -1L,
            // MatrixCursor is read through Cursor.getInt() in the Settings process.
            // Store a numeric flag so the value survives the provider IPC boundary.
            COLUMN_ENABLED to if (enabled(prefs, slot)) 1 else 0,
            COLUMN_OPACITY to opacity(prefs, slot),
            COLUMN_BLUR to blur(prefs, slot),
            COLUMN_FONT to font(prefs, slot),
            COLUMN_SCALE to prefs.getInt("logo_scale", 100),
            COLUMN_LOGO_MODE to prefs.getInt("logo_mode", LOGO_MODE_SYSTEM),
            COLUMN_LIGHT_CARD_OPACITY to prefs.getInt("light_card_opacity", 100),
        )
        row.addRow(columns.map { values[it] ?: 0 })
        return row
    }

    override fun getType(uri: Uri): String? = requireContext()
        .getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, 0)
        .getString(mimeKey(uri.pathSegments.firstOrNull().orEmpty()), null)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = appearanceFile(uri.pathSegments.firstOrNull().orEmpty())
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun appearanceFile(slot: String): File {
        require(slot == APPEARANCE_SLOT_HOME || slot == APPEARANCE_SLOT_DEVICE || slot == APPEARANCE_SLOT_LOGO)
        return File(File(requireContext().filesDir, "settings_appearance"), "$slot.bin")
    }

    private fun enabled(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getBoolean("home_enabled", false)
        APPEARANCE_SLOT_DEVICE -> prefs.getBoolean("device_enabled", false)
        APPEARANCE_SLOT_LOGO -> prefs.getInt("logo_mode", LOGO_MODE_SYSTEM) != LOGO_MODE_SYSTEM
        else -> false
    }

    private fun opacity(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getInt("home_opacity", 100)
        APPEARANCE_SLOT_DEVICE -> prefs.getInt("device_opacity", 100)
        else -> 100
    }

    private fun blur(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getFloatCompat("home_blur", 0f)
        APPEARANCE_SLOT_DEVICE -> prefs.getFloatCompat("device_blur", 0f)
        else -> 0f
    }

    private fun android.content.SharedPreferences.getFloatCompat(key: String, default: Float): Float =
        runCatching { getFloat(key, default) }.getOrElse {
            runCatching { getInt(key, default.toInt()).toFloat() }.getOrDefault(default)
        }

    private fun font(prefs: android.content.SharedPreferences, slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> prefs.getInt("home_font", 0)
        APPEARANCE_SLOT_DEVICE -> prefs.getInt("device_font", 0)
        else -> 0
    }

    private fun mimeKey(slot: String) = when (slot) {
        APPEARANCE_SLOT_HOME -> "home_mime"
        APPEARANCE_SLOT_DEVICE -> "device_mime"
        APPEARANCE_SLOT_LOGO -> "logo_mime"
        else -> ""
    }

    companion object {
        const val COLUMN_MIME = "mime_type"
        const val COLUMN_SIZE = "size"
        const val COLUMN_MODIFIED = "modified"
        const val COLUMN_ENABLED = "enabled"
        const val COLUMN_OPACITY = "opacity"
        const val COLUMN_BLUR = "blur"
        const val COLUMN_FONT = "font"
        const val COLUMN_SCALE = "scale"
        const val COLUMN_LOGO_MODE = "logo_mode"
        const val COLUMN_LIGHT_CARD_OPACITY = "light_card_opacity"
        private val COLUMNS = arrayOf(COLUMN_MIME, COLUMN_SIZE, COLUMN_MODIFIED, COLUMN_ENABLED, COLUMN_OPACITY, COLUMN_BLUR, COLUMN_FONT, COLUMN_SCALE, COLUMN_LOGO_MODE, COLUMN_LIGHT_CARD_OPACITY)
    }
}
