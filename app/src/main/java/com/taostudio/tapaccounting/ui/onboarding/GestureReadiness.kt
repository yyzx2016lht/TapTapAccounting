package com.taostudio.tapaccounting.ui.onboarding

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.OverlayService

/**
 * 快捷记账「准备项」的统一检测口径。
 *
 * 之前这套判断散落在 [com.taostudio.tapaccounting.GesturePermissionGuideActivity] 和
 * [com.taostudio.tapaccounting.ui.SensitivityActivity] 里各写一份，引导页需要第三份，
 * 所以抽到这里，保证三处对「准备好了没」的判断完全一致。
 */
object GestureReadiness {

    /** 悬浮窗权限：能画悬浮窗才能弹出记账面板。Android 6 以下默认有。 */
    fun hasOverlay(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    /** 电池优化豁免。 */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /** 后台被系统限制（Android 9+）。 */
    fun isBackgroundRestricted(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.isBackgroundRestricted
        } else {
            false
        }
    }.getOrDefault(false)

    /** 后台常驻是否就绪：豁免电池优化 + 未被限制后台。 */
    fun isBackgroundReady(ctx: Context): Boolean =
        isIgnoringBatteryOptimizations(ctx) && !isBackgroundRestricted(ctx)

    /** 通知权限是否就绪（含 App 级与渠道级）。 */
    fun isNotificationReady(ctx: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        val appEnabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(OverlayService.CHANNEL_ID)?.importance !=
                NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return granted && appEnabled && channelEnabled
    }

    /** 核心两项（悬浮窗 + 后台）是否都就绪，用来决定是否值得提示"去准备"。 */
    fun hasCoreGap(ctx: Context): Boolean = !hasOverlay(ctx) || !isBackgroundReady(ctx)

    /**
     * 已完成的「必要」项数量。自启/锁后台无法自动检测（各家 ROM 不一），
     * 只能给手动引导，所以不计入分母，与 GesturePermissionGuideActivity 口径一致。
     */
    fun requiredReadyCount(ctx: Context): Int {
        var n = 0
        if (hasOverlay(ctx)) n++
        if (isBackgroundReady(ctx)) n++
        return n
    }

    const val REQUIRED_TOTAL = 2
}
