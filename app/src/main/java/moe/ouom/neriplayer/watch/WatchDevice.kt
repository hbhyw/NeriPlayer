package moe.ouom.neriplayer.watch

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.watch/WatchDevice
 */

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics

/**
 * 手表形态识别。
 *
 * 手表上直接使用手机版界面，只是把整体 UI 密度调小；
 * 这里只负责判断“这是不是一块手表”，不再做界面分流。
 *
 * 不少国产 Android 8.1 手表（例如 OW20W1）虽然声明了
 * `android.hardware.type.watch`，但屏幕是 402x476 的方屏、等效宽度接近 300dp，
 * 单看尺寸会被当成手机，所以优先用系统 feature 判定。
 */
object WatchDevice {
    /** 超过这个宽度就不认为是手表（普通手机的 smallestScreenWidthDp 一般 >= 320）。 */
    private const val WATCH_MAX_SMALLEST_WIDTH_DP = 240

    /** 兜底时的短边像素上限，兼容 1.2 ~ 1.6 英寸的小屏设备。 */
    private const val WATCH_MAX_SHORT_SIDE_PX = 480

    fun isWatchHardware(context: Context): Boolean {
        val configuration = context.resources.configuration
        if (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_WATCH
        ) {
            return true
        }
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            return true
        }
        return looksLikeWatchScreen(configuration, context.resources.displayMetrics)
    }

    fun isRoundScreen(resources: Resources): Boolean =
        resources.configuration.isScreenRound

    internal fun looksLikeWatchScreen(
        configuration: Configuration,
        metrics: DisplayMetrics
    ): Boolean {
        val smallestWidthDp = configuration.smallestScreenWidthDp
        // 未上报（0）说明是老 ROM 的兼容模式，不做猜测，交给其它判据
        if (smallestWidthDp <= 0) return false
        if (smallestWidthDp > WATCH_MAX_SMALLEST_WIDTH_DP) return false
        if (configuration.isScreenRound) return true
        return minOf(metrics.widthPixels, metrics.heightPixels) <= WATCH_MAX_SHORT_SIDE_PX
    }
}
