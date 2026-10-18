package moe.ouom.neriplayer.data.model.platform

import android.app.Application
import android.os.Build
import java.io.File

/**
 * 进程名兼容读取。
 *
 * `Application.getProcessName()` 是 API 28（P）才引入的静态方法，
 * Android 8.1（API 27）手表上直接调用会抛 `NoSuchMethodError`
 * ——而它就在 `Application.onCreate` 的第一批语句里，等于应用根本起不来。
 *
 * 低版本改为读 `/proc/self/cmdline`（NUL 结尾），语义一致。
 */
fun currentProcessNameCompat(): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val systemName = runCatching { Application.getProcessName() }.getOrNull()
        if (!systemName.isNullOrBlank()) return systemName
    }
    return readCmdlineProcessName()
}

private fun readCmdlineProcessName(): String = runCatching {
    File("/proc/self/cmdline").readText().trimEnd('\u0000', '\n', ' ')
}.getOrNull().orEmpty()
