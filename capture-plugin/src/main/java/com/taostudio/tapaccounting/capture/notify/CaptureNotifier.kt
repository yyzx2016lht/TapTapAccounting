package com.taostudio.tapaccounting.capture.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.capture.R
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.ui.PendingListActivity

/**
 * P0 的普通常驻通知：只显示数量，不显示金额（锁屏 PRIVATE）。
 *
 * 不使用前台服务；进程被杀后通知仍由系统持有，下次注入再刷新。
 */
object CaptureNotifier {
    const val CHANNEL_ID: String = "capture_pending"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_pending),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notify_channel_pending_desc)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** 刷新“N 笔待记”，并把数量广播给 App。 */
    fun refresh(context: Context, count: Int) {
        val app = context.applicationContext
        ensureChannel(app)
        if (count <= 0) {
            cancel(app)
        } else {
            notifyCount(app, count)
        }
        broadcastCount(app, count)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    private fun notifyCount(context: Context, count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 通知权限被拒：只落库 + 广播数量，不崩溃。
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val contentIntent = Intent(context, PendingListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(context, 0, contentIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle(context.getString(R.string.notify_pending_title, count))
            .setContentText(context.getString(R.string.notify_pending_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /** App 侧用缓存的待记数量判断敲击；没有安装 App 时广播自然丢弃。 */
    private fun broadcastCount(context: Context, count: Int) {
        try {
            val intent = Intent(CaptureContract.ACTION_PENDING_COUNT_CHANGED)
                .setPackage(CaptureContract.APP_PACKAGE)
                .putExtra(CaptureContract.EXTRA_COUNT, count)
                .putExtra(CaptureContract.EXTRA_UPDATED_AT, System.currentTimeMillis())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            // 广播失败不影响落库。
            android.util.Log.w("TapCapture", "broadcast count failed", t)
        }
    }
}
