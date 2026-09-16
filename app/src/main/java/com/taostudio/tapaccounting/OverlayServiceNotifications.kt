package com.taostudio.tapaccounting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object OverlayServiceNotifications {
    /**
     * @param countDownTo 非空时在通知右侧渲染一个由系统驱动的实时倒计时（无需 App 每秒刷新），
     *                    传的是倒计时归零的 epoch 毫秒。
     */
    fun build(
        ctx: Context,
        channelId: String,
        content: String,
        countDownTo: Long? = null
    ): Notification {
        ensureChannel(ctx, channelId)
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(ctx, channelId)
            .setContentTitle("敲敲记账助手")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (countDownTo != null) {
            // 系统自己渲染倒计时，我们不需要为了跳秒去反复 notify。
            builder.setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(countDownTo)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun ensureChannel(ctx: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(channelId, "记账助手服务", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }
}

