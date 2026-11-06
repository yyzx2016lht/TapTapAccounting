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
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.capture.ui.PendingListActivity
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
    const val GROUP_KEY_PENDING: String = "capture_pending_group"
    const val NOTIFICATION_ID_SUMMARY: Int = 1001

    @Deprecated("Use NOTIFICATION_ID_SUMMARY", ReplaceWith("NOTIFICATION_ID_SUMMARY"))
    const val NOTIFICATION_ID: Int = NOTIFICATION_ID_SUMMARY

    /** P2 待记页上线后，MainActivity/通知点击可以根据这个 extra 跳到收件箱。 */
    const val EXTRA_OPEN_CAPTURE_INBOX = "open_capture_inbox"

    private val postedChildIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    fun childNotificationId(captureId: String): Int {
        val code = captureId.hashCode()
        return if (code == NOTIFICATION_ID_SUMMARY) 1002 else code
    }

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
        if (count <= 0 || !CapturePrefs.isNotificationEnabled(app)) {
            cancel(app)
        } else {
            notifyPending(app, count, latestItems)
        }
    }

    fun refreshWithItems(context: Context, pendingItems: List<PaymentCapture>) {
        val app = context.applicationContext
        val count = pendingItems.size
        ensureChannel(app)
        if (count <= 0 || !CapturePrefs.isNotificationEnabled(app)) {
            cancel(app)
        } else {
            notifyPending(app, count, pendingItems)
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID_SUMMARY)
        val toCancel = synchronized(postedChildIds) {
            val list = postedChildIds.toList()
            postedChildIds.clear()
            list
        }
        toCancel.forEach { manager.cancel(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                manager.activeNotifications?.forEach { activeNotif ->
                    if (activeNotif.notification.group == GROUP_KEY_PENDING ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activeNotif.notification.channelId == CHANNEL_ID)) {
                        manager.cancel(activeNotif.id)
                    }
                }
            }
        }
    }

    fun cancelItem(context: Context, captureId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val childId = childNotificationId(captureId)
        manager.cancel(childId)
        postedChildIds.remove(childId)
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
            putExtra(EXTRA_OPEN_CAPTURE_INBOX, true)
        }
        val immutableFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val mutableFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
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

        // 1. Group Summary Notification
        val summaryBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        summaryBuilder
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY_PENDING)
            .setGroupSummary(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        if (items.size > 1) {
            val inbox = Notification.InboxStyle().setBigContentTitle(title)
            items.take(5).forEach { item ->
                inbox.addLine(formatItemLine(item, timeFmt))
            }
            if (items.size > 5) {
                inbox.setSummaryText("还有 ${items.size - 5} 笔待记，点开查看全部")
            } else {
                inbox.setSummaryText("下拉可快捷补备注 · 敲击手机可统一整理")
            }
            summaryBuilder.setStyle(inbox)
        }
        manager.notify(NOTIFICATION_ID_SUMMARY, summaryBuilder.build())

        // 2. Child Notifications (最多展示前 10 笔，独立挂载 RemoteInput 与 ✕ 不计)
        val displayedItems = items.take(10)
        val currentChildIds = mutableSetOf<Int>()

        displayedItems.forEach { item ->
            val childId = childNotificationId(item.id)
            currentChildIds.add(childId)

            val childItemIntent = Intent(context, PendingListActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_CAPTURE_INBOX, true)
                putExtra("target_capture_id", item.id)
            }
            val childPendingIntent = PendingIntent.getActivity(
                context,
                childId,
                childItemIntent,
                immutableFlags,
            )

            val isRefund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
            val isIncome = item.billType == LocalBillType.TYPE_INCOME
            val typeTag = when {
                isRefund -> "[退款]"
                isIncome -> "[收入]"
                item.billType == LocalBillType.TYPE_TRANSFER -> "[转账]"
                else -> ""
            }
            val amtPrefix = if (isIncome || isRefund) "+¥" else "¥"
            val amtStr = item.amountCents?.let {
                String.format(Locale.CHINA, "%s%.2f", amtPrefix, it / 100.0)
            } ?: "待补金额"

            val childTitle = buildString {
                append(amtStr)
                if (typeTag.isNotEmpty()) append(" ").append(typeTag)
                val desc = item.merchant?.takeIf { it.isNotBlank() }
                    ?: item.assetName?.takeIf { it.isNotBlank() }
                    ?: CaptureSources.displayName(item.source)
                append(" · ").append(desc)
            }

            val childText = buildString {
                append(timeFmt.format(Date(item.paidAt)))
                append(" · ").append(CaptureSources.displayName(item.source))
                if (item.assetName != null && item.assetName != item.merchant) {
                    append(" · ").append(item.assetName)
                }
                if (!item.categoryName.isNullOrBlank()) {
                    append(" (").append(item.categoryName).append(")")
                }
                if (!item.note.isNullOrBlank()) {
                    append(" [备注:").append(item.note).append("]")
                }
            }

            val childBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            childBuilder
                .setSmallIcon(R.drawable.ic_capture)
                .setContentTitle(childTitle)
                .setContentText(childText)
                .setContentIntent(childPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_KEY_PENDING)
                .setGroupSummary(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)

            // 专属 RemoteInput 补备注
            val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_REPLY_NOTE
                putExtra(NotificationActionReceiver.EXTRA_CAPTURE_ID, item.id)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                childId,
                replyIntent,
                mutableFlags,
            )
            val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REMOTE_NOTE)
                .setLabel("补一句备注，方便以后查找")
                .build()
            val replyAction = Notification.Action.Builder(
                R.drawable.ic_capture,
                if (item.note.isNullOrBlank()) "✎ 补备注" else "✎ 改备注(${item.note.take(4)})",
                replyPendingIntent,
            )
                .addRemoteInput(remoteInput)
                .build()
            childBuilder.addAction(replyAction)

            // 专属 ✕ 不计
            val ignoreIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_IGNORE_CAPTURE
                putExtra(NotificationActionReceiver.EXTRA_CAPTURE_ID, item.id)
            }
            val ignorePendingIntent = PendingIntent.getBroadcast(
                context,
                childId + 1,
                ignoreIntent,
                immutableFlags,
            )
            val ignoreAction = Notification.Action.Builder(
                R.drawable.ic_capture,
                "✕ 不计",
                ignorePendingIntent,
            ).build()
            childBuilder.addAction(ignoreAction)

            manager.notify(childId, childBuilder.build())
        }

        // 3. 清理已不再展示或已被处理的历史 Child Notifications
        val obsoleteChildIds = synchronized(postedChildIds) {
            val obsolete = postedChildIds.filter { it !in currentChildIds }
            postedChildIds.clear()
            postedChildIds.addAll(currentChildIds)
            obsolete
        }
        obsoleteChildIds.forEach { oldId ->
            manager.cancel(oldId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                manager.activeNotifications?.forEach { activeNotif ->
                    val id = activeNotif.id
                    if (id != NOTIFICATION_ID_SUMMARY && id !in currentChildIds) {
                        if (activeNotif.notification.group == GROUP_KEY_PENDING ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activeNotif.notification.channelId == CHANNEL_ID)) {
                            manager.cancel(id)
                        }
                    }
                }
            }
        }
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
