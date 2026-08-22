package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

const val SETTINGS_APPEARANCE_PREFERENCES = "settings_appearance"
const val SETTINGS_APPEARANCE_AUTHORITY = "btm.m.os4.systemuihook.settingsappearance"

const val APPEARANCE_SLOT_HOME = "home"
const val APPEARANCE_SLOT_DEVICE = "device"
const val APPEARANCE_SLOT_LOGO = "logo"

const val LOGO_MODE_SYSTEM = 0
const val LOGO_MODE_NO_ADVANCED_MATERIAL = 1
const val LOGO_MODE_KEEP_ADVANCED_MATERIAL = 2

private const val KEY_INITIALIZED = "initialized"
private const val KEY_HOME_ENABLED = "home_enabled"
private const val KEY_HOME_OPACITY = "home_opacity"
private const val KEY_HOME_BLUR = "home_blur"
private const val KEY_HOME_FONT = "home_font"
private const val KEY_HOME_MIME = "home_mime"
private const val KEY_HOME_VERSION = "home_version"
private const val KEY_DEVICE_ENABLED = "device_enabled"
private const val KEY_DEVICE_OPACITY = "device_opacity"
private const val KEY_DEVICE_BLUR = "device_blur"
private const val KEY_DEVICE_FONT = "device_font"
private const val KEY_DEVICE_MIME = "device_mime"
private const val KEY_DEVICE_VERSION = "device_version"
private const val KEY_LOGO_MODE = "logo_mode"
private const val KEY_LOGO_SCALE = "logo_scale"
private const val KEY_LOGO_MIME = "logo_mime"
private const val KEY_LOGO_VERSION = "logo_version"
private const val KEY_LIGHT_CARD_OPACITY = "light_card_opacity"

data class SettingsAppearanceSettings(
    val homeEnabled: Boolean = false,
    val homeOpacity: Int = 100,
    val homeBlur: Float = 0f,
    val homeFontMode: Int = 0,
    val homeMime: String = "",
    val homeVersion: Long = 0L,
    val deviceEnabled: Boolean = false,
    val deviceOpacity: Int = 100,
    val deviceBlur: Float = 0f,
    val deviceFontMode: Int = 0,
    val deviceMime: String = "",
    val deviceVersion: Long = 0L,
    val logoMode: Int = LOGO_MODE_SYSTEM,
    val logoScale: Int = 100,
    val logoMime: String = "",
    val logoVersion: Long = 0L,
    val lightCardOpacity: Int = 100,
)

class SettingsAppearanceStore(context: Context) {
    private val local = context.getSharedPreferences(SETTINGS_APPEARANCE_PREFERENCES, Context.MODE_PRIVATE)
    var settings: SettingsAppearanceSettings = local.toSettingsAppearance()
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
        settings = if (remote.contains(KEY_INITIALIZED)) remote.toSettingsAppearance() else settings
        remote.writeSettingsAppearance(settings)
        local.writeSettingsAppearance(settings)
    }

    fun update(service: XposedService?, transform: (SettingsAppearanceSettings) -> SettingsAppearanceSettings) {
        settings = transform(settings).normalized()
        local.writeSettingsAppearance(settings)
        service?.getRemotePreferences(SETTINGS_APPEARANCE_PREFERENCES)?.writeSettingsAppearance(settings)
    }
}

internal fun SharedPreferences.toSettingsAppearance() = SettingsAppearanceSettings(
    homeEnabled = getBoolean(KEY_HOME_ENABLED, false),
    homeOpacity = getInt(KEY_HOME_OPACITY, 100),
    homeBlur = getFloatCompat(KEY_HOME_BLUR, 0f),
    homeFontMode = getInt(KEY_HOME_FONT, 0),
    homeMime = getString(KEY_HOME_MIME, "").orEmpty(),
    homeVersion = getLong(KEY_HOME_VERSION, 0L),
    deviceEnabled = getBoolean(KEY_DEVICE_ENABLED, false),
    deviceOpacity = getInt(KEY_DEVICE_OPACITY, 100),
    deviceBlur = getFloatCompat(KEY_DEVICE_BLUR, 0f),
    deviceFontMode = getInt(KEY_DEVICE_FONT, 0),
    deviceMime = getString(KEY_DEVICE_MIME, "").orEmpty(),
    deviceVersion = getLong(KEY_DEVICE_VERSION, 0L),
    logoMode = getInt(KEY_LOGO_MODE, LOGO_MODE_SYSTEM),
    logoScale = getInt(KEY_LOGO_SCALE, 100),
    logoMime = getString(KEY_LOGO_MIME, "").orEmpty(),
    logoVersion = getLong(KEY_LOGO_VERSION, 0L),
    lightCardOpacity = getInt(KEY_LIGHT_CARD_OPACITY, 100),
).normalized()

private fun SettingsAppearanceSettings.normalized() = copy(
    homeOpacity = homeOpacity.coerceIn(0, 100),
    homeBlur = homeBlur.coerceIn(0f, 20f),
    homeFontMode = homeFontMode.coerceIn(0, 2),
    deviceOpacity = deviceOpacity.coerceIn(0, 100),
    deviceBlur = deviceBlur.coerceIn(0f, 20f),
    deviceFontMode = deviceFontMode.coerceIn(0, 2),
    logoMode = logoMode.coerceIn(LOGO_MODE_SYSTEM, LOGO_MODE_KEEP_ADVANCED_MATERIAL),
    logoScale = logoScale.coerceIn(50, 200),
    lightCardOpacity = lightCardOpacity.coerceIn(0, 100),
)

private fun SharedPreferences.writeSettingsAppearance(value: SettingsAppearanceSettings) {
    edit()
        .putBoolean(KEY_INITIALIZED, true)
        .putBoolean(KEY_HOME_ENABLED, value.homeEnabled)
        .putInt(KEY_HOME_OPACITY, value.homeOpacity)
        .putFloat(KEY_HOME_BLUR, value.homeBlur)
        .putInt(KEY_HOME_FONT, value.homeFontMode)
        .putString(KEY_HOME_MIME, value.homeMime)
        .putLong(KEY_HOME_VERSION, value.homeVersion)
        .putBoolean(KEY_DEVICE_ENABLED, value.deviceEnabled)
        .putInt(KEY_DEVICE_OPACITY, value.deviceOpacity)
        .putFloat(KEY_DEVICE_BLUR, value.deviceBlur)
        .putInt(KEY_DEVICE_FONT, value.deviceFontMode)
        .putString(KEY_DEVICE_MIME, value.deviceMime)
        .putLong(KEY_DEVICE_VERSION, value.deviceVersion)
        .putInt(KEY_LOGO_MODE, value.logoMode)
        .putInt(KEY_LOGO_SCALE, value.logoScale)
        .putString(KEY_LOGO_MIME, value.logoMime)
        .putLong(KEY_LOGO_VERSION, value.logoVersion)
        .putInt(KEY_LIGHT_CARD_OPACITY, value.lightCardOpacity)
        .apply()
}

private fun SharedPreferences.getFloatCompat(key: String, default: Float): Float =
    runCatching { getFloat(key, default) }.getOrElse {
        runCatching { getInt(key, default.toInt()).toFloat() }.getOrDefault(default)
    }
