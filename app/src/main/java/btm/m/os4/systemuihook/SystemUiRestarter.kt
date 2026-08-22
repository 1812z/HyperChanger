package btm.m.os4.systemuihook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.concurrent.thread

object SystemUiRestarter {
    fun restart(context: Context, targets: Set<ScopeApplication>) {
        thread(name = "scope-app-restart", isDaemon = true) {
            if (targets.isEmpty()) {
                showToast(context, "\u8bf7\u81f3\u5c11\u9009\u62e9\u4e00\u4e2a\u4f5c\u7528\u57df\u5e94\u7528")
                return@thread
            }

            val rootCheck = runSu("id")
            if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
                showToast(context, "\u672a\u83b7\u5f97 root \u6743\u9650\uff0c\u91cd\u542f\u5931\u8d25")
                return@thread
            }

            val failed = buildList {
                targets.forEach { target ->
                    if (runSu("pkill -f ${target.packageName}").exitCode != 0) {
                        add(target.title)
                    }
                }
            }
            if (failed.isEmpty()) {
                showToast(context, "\u5df2\u91cd\u542f\u9009\u4e2d\u7684\u4f5c\u7528\u57df\u5e94\u7528")
            } else {
                showToast(context, "\u91cd\u542f\u5931\u8d25\uff1a${failed.joinToString("\u3001")}")
            }
        }
    }

    private fun runSu(command: String): CommandResult = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        CommandResult(exitCode, output)
    }.getOrElse { CommandResult(-1, "") }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}

enum class ScopeApplication(val title: String, val packageName: String) {
    SYSTEM_UI("\u7cfb\u7edf\u754c\u9762", "com.android.systemui"),
    AOD("\u606f\u5c4f\u4e0e\u9501\u5c4f\u7f16\u8f91", "com.miui.aod"),
    SUBSCREEN_CENTER("\u80cc\u5c4f", "com.xiaomi.subscreencenter"),
    GALLERY("\u76f8\u518c", "com.miui.gallery"),
    CAMERA("\u76f8\u673a", "com.android.camera"),
    MEDIA_EDITOR("\u5c0f\u7c73\u76f8\u518c-\u7f16\u8f91", "com.miui.mediaeditor"),
    SUPER_XIAOAI_IME("\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5", "com.xiaomi.type"),
    SETTINGS("\u8bbe\u7f6e", "com.android.settings"),
}
