package com.taostudio.tapaccounting.capture.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.R
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.capture.ui.PendingListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻待记通知：
 * - 展示待记笔数、合计金额与最新几笔明细（InboxStyle）；
 * - 支持在通知栏直接点「✎ 补最新备注」通过 RemoteInput 打字保存，无需打开 App；
 * - 支持一键「忽略最新」。
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

    /** 根据数据库当前待记列表刷新通知与广播。 */
    suspend fun refreshAll(context: Context) {
        val app = context.applicationContext
        val onlyExpense = CapturePrefs.isOnlyExpense(app)
        val pendingItems = withContext(Dispatchers.IO) {
            CaptureApp.from(app).repository.listPending(50, onlyExpense = onlyExpense)
        }
        refreshWithItems(app, pendingItems)
    }

    /** 兼容旧调用签名。 */
    fun refresh(context: Context, count: Int, latestItems: List<PaymentCapture> = emptyList()) {
        val app = context.applicationContext
        ensureChannel(app)
        if (count <= 0 || CapturePrefs.getPromptMode(app) == CapturePrefs.MODE_SILENT) {
            cancel(app)
        } else {
            notifyPending(app, count, latestItems)
        }
        broadcastCount(app, count)
    }

    fun refreshWithItems(context: Context, pendingItems: List<PaymentCapture>) {
        val app = context.applicationContext
        val count = pendingItems.size
        ensureChannel(app)
        if (count <= 0 || CapturePrefs.getPromptMode(app) == CapturePrefs.MODE_SILENT) {
            cancel(app)
        } else {
            notifyPending(app, count, pendingItems)
        }
        broadcastCount(app, count)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    private fun notifyPending(context: Context, count: Int, items: List<PaymentCapture>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val contentIntent = Intent(context, PendingListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val immutableFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(context, 0, contentIntent, immutableFlags)

        val totalCents = items.mapNotNull { it.amountCents }.sum()
        val title = if (totalCents > 0L) {
            String.format(Locale.CHINA, "%d 笔待记 · 共 ¥%.2f", count, totalCents / 100.0)
        } else {
            context.getString(R.string.notify_pending_title, count)
        }

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val latest = items.firstOrNull()
        val subtitle = if (latest != null) {
            formatItemLine(latest, timeFmt)
        } else {
            "点开补备注或敲击手机背面速记"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        if (items.size > 1) {
            val inbox = Notification.InboxStyle()
                .setBigContentTitle(title)
            items.take(5).forEach { item ->
                inbox.addLine(formatItemLine(item, timeFmt))
            }
            if (items.size > 5) {
                inbox.setSummaryText("还有 ${items.size - 5} 笔待记，点开查看全部")
            } else {
                inbox.setSummaryText("下拉可快捷补备注 · 敲击手机可一键 AI 分类")
            }
            builder.setStyle(inbox)
        }

        // 为最新一笔提供通知栏直接打字补备注（RemoteInput）与一键忽略
        if (latest != null) {
            val mutableFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

            val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_REPLY_NOTE
                putExtra(NotificationActionReceiver.EXTRA_CAPTURE_ID, latest.id)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                latest.id.hashCode(),
                replyIntent,
                mutableFlags,
            )
            val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REMOTE_NOTE)
                .setLabel("买什么了？如：青菜（自动匹配分类）")
                .build()
            val replyAction = Notification.Action.Builder(
                R.drawable.ic_capture,
                if (latest.note.isNullOrBlank()) "✎ 补最新备注" else "✎ 改备注(${latest.note.take(4)})",
                replyPendingIntent,
            )
                .addRemoteInput(remoteInput)
                .build()
            builder.addAction(replyAction)

            val ignoreIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_IGNORE_CAPTURE
                putExtra(NotificationActionReceiver.EXTRA_CAPTURE_ID, latest.id)
            }
            val ignorePendingIntent = PendingIntent.getBroadcast(
                context,
                latest.id.hashCode() + 1,
                ignoreIntent,
                immutableFlags,
            )
            val ignoreAction = Notification.Action.Builder(
                R.drawable.ic_capture,
                "忽略最新",
                ignorePendingIntent,
            ).build()
            builder.addAction(ignoreAction)
        }

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun formatItemLine(item: PaymentCapture, timeFmt: SimpleDateFormat): String {
        val src = when (item.source) {
            CaptureSource.WECHAT -> "微信"
            CaptureSource.ALIPAY -> "支付宝"
            else -> item.source
        }
        val amt = item.amountCents?.let {
            String.format(Locale.CHINA, "¥%.2f", it / 100.0)
        } ?: "待补金额"
        val asset = item.assetName ?: item.paymentMethodRaw
        val merchant = item.merchant?.takeIf { it.isNotBlank() }
        val note = item.note?.takeIf { it.isNotBlank() }
        val cat = item.categoryName?.takeIf { it.isNotBlank() }
        return buildString {
            append(timeFmt.format(Date(item.paidAt))).append(' ')
            append(src).append(' ').append(amt)
            if (asset != null) append(" · ").append(asset)
            if (merchant != null) append(" · ").append(merchant)
            if (note != null) append(" [备注:").append(note).append(']')
            if (cat != null) append(" (").append(cat).append(')')
        }
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
            android.util.Log.w("TapCapture", "broadcast count failed", t)
        }
    }
}
