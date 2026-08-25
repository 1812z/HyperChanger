package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

private const val CAMERA_PREFERENCE_GROUP = "settings"
private const val CAMERA_INITIALIZED = "initialized"
private const val CAMERA_MASTER_ENABLED = "master_enabled"
private const val CAMERA_LEICA_UI = "leica_ui"
private const val CAMERA_PRESERVE_FOCAL_LENGTHS = "preserve_native_focal_lengths"
private const val CAMERA_GALLERY_WATERMARKS = "gallery_all_watermarks"

data class CameraSettings(
    val masterEnabled: Boolean = true,
    val leicaUi: Boolean = true,
    val preserveNativeFocalLengths: Boolean = true,
    val galleryAllWatermarks: Boolean = true,
)

class CameraSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(CAMERA_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: CameraSettings = local.toCameraSettings()
        private set

    fun reload() {
        settings = local.toCameraSettings()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(CAMERA_PREFERENCE_GROUP)
        settings = if (remote.contains(CAMERA_INITIALIZED)) remote.toCameraSettings() else settings.also { remote.writeCameraSettings(it) }
        local.writeCameraSettings(settings)
    }

    fun update(service: XposedService?, transform: (CameraSettings) -> CameraSettings) {
        settings = transform(settings)
        local.writeCameraSettings(settings)
        service?.getRemotePreferences(CAMERA_PREFERENCE_GROUP)?.writeCameraSettings(settings)
    }
}

private fun SharedPreferences.toCameraSettings() = CameraSettings(
    masterEnabled = getBoolean(CAMERA_MASTER_ENABLED, true),
    leicaUi = getBoolean(CAMERA_LEICA_UI, true),
    preserveNativeFocalLengths = getBoolean(CAMERA_PRESERVE_FOCAL_LENGTHS, true),
    galleryAllWatermarks = getBoolean(CAMERA_GALLERY_WATERMARKS, true),
)

private fun SharedPreferences.writeCameraSettings(value: CameraSettings) {
    edit()
        .putBoolean(CAMERA_INITIALIZED, true)
        .putBoolean(CAMERA_MASTER_ENABLED, value.masterEnabled)
        .putBoolean(CAMERA_LEICA_UI, value.leicaUi)
        .putBoolean(CAMERA_PRESERVE_FOCAL_LENGTHS, value.preserveNativeFocalLengths)
        .putBoolean(CAMERA_GALLERY_WATERMARKS, value.galleryAllWatermarks)
        .apply()
}
