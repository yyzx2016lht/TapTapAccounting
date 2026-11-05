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
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主应用侧待记通知。
 *
 * P1 只做“落库后的通知/快捷操作”，气泡在 P3 接管。过渡期默认静默，
 * 旧插件仍然负责它自己的通知/气泡，避免同一条支付弹两次。
 */
object CaptureNotifier {
    const val CHANNEL_ID: String = "capture_pending"
    private const val NOTIFICATION_ID = 1001

    /** P2 待记页上线后，MainActivity/通知点击可以根据这个 extra 跳到收件箱。 */
    const val EXTRA_OPEN_CAPTURE_INBOX = "open_capture_inbox"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "待记收件箱",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "支付捕获后的待记提醒"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** 根据数据库当前待记列表刷新通知。 */
    suspend fun refreshAll(context: Context) {
        val app = context.applicationContext
        val onlyExpense = CapturePrefs.isOnlyExpense(app)
        val pendingItems = withContext(Dispatchers.IO) {
            CaptureRepository(AppDatabase.getDatabase(app).paymentCaptureDao())
                .listPending(50, onlyExpense = onlyExpense)
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
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_CAPTURE_INBOX, true)
        }
        val immutableFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(context, 0, contentIntent, immutableFlags)

        val totalCents = items.mapNotNull { it.amountCents }.sum()
        val title = if (totalCents > 0L) {
            String.format(Locale.CHINA, "%d 笔待记 · 共 ¥%.2f", count, totalCents / 100.0)
        } else {
            "$count 笔待记"
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
                inbox.setSummaryText("下拉可快捷补备注 · 敲击手机可统一整理")
            }
            builder.setStyle(inbox)
        }

        // 为最新一笔提供通知栏直接打字补备注（RemoteInput）与一键不计。
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
                .setLabel("补一句备注，方便以后查找")
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
                "不计最新",
                ignorePendingIntent,
            ).build()
            builder.addAction(ignoreAction)
        }

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun formatItemLine(item: PaymentCapture, timeFmt: SimpleDateFormat): String {
        val src = CaptureSources.displayName(item.source)
        val isRefund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
        val isIncome = item.billType == LocalBillType.TYPE_INCOME
        val typeTag = when {
            isRefund -> "[退款]"
            isIncome -> "[收入]"
            item.billType == LocalBillType.TYPE_TRANSFER -> "[转账]"
            else -> ""
        }
        val amtPrefix = if (isIncome || isRefund) "+¥" else "¥"
        val amt = item.amountCents?.let {
            String.format(Locale.CHINA, "%s%.2f", amtPrefix, it / 100.0)
        } ?: "待补金额"
        val asset = item.assetName ?: item.paymentMethodRaw
        val merchant = item.merchant?.takeIf { it.isNotBlank() }
        val note = item.note?.takeIf { it.isNotBlank() }
        val cat = item.categoryName?.takeIf { it.isNotBlank() }
        return buildString {
            append(timeFmt.format(Date(item.paidAt))).append(' ')
            append(src).append(typeTag).append(' ').append(amt)
            if (asset != null) append(" · ").append(asset)
            if (merchant != null) append(" · ").append(merchant)
            if (note != null) append(" [备注:").append(note).append(']')
            if (cat != null) append(" (").append(cat).append(')')
        }
    }
}
