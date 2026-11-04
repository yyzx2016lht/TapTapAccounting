package com.taostudio.tapaccounting.capture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.R
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.ingest.CaptureIngestHandler
import com.taostudio.tapaccounting.capture.ui.PendingListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 保活前台服务，对应 AutoAccounting 的 CoreService。
 *
 * 为什么需要：ColorOS 会把后台插件进程标记为 FROZEN，显式广播虽然能唤醒，
 * 但会被推迟到进程解冻（实测 16 秒～数分钟）。前台服务让进程保持可运行，
 * 支付事件可以立即落库并刷新通知。
 *
 * 只做进程保活；真正的入库仍然由 [com.taostudio.tapaccounting.capture.ingest.CaptureIngestReceiver]
 * 处理。Android 12+ 从后台启动前台服务可能被拒，所有入口都捕获异常，失败不影响落库。
 */
class CaptureKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CaptureContract.ACTION_SERVICE_INGEST) {
            val token = intent.getStringExtra(CaptureContract.EXTRA_TOKEN)
            val payload = intent.getStringExtra(CaptureContract.EXTRA_PAYLOAD)
            val relayId = intent.getStringExtra(CaptureContract.EXTRA_RELAY_ID)
            if (token != BuildConfig.CAPTURE_INGEST_TOKEN || payload.isNullOrBlank()) {
                Log.w(TAG, "service ingest ignored: invalid token/payload")
                return START_STICKY
            }
            scope.launch {
                try {
                    val ack = CaptureIngestHandler.ingest(this@CaptureKeepAliveService, token, payload)
                    Log.i(TAG, "service ingest ack=$ack")
                    if (!relayId.isNullOrBlank()) {
                        sendRelayAck(relayId)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "service ingest failed", t)
                }
            }
        }
        // 被系统杀掉后尽量重启；重启失败时 relay/广播仍能唤醒进程。
        return START_STICKY
    }

    /** 入库完成后通过同一个 localhost 端口回 ack，relay 删除队列文件。 */
    private fun sendRelayAck(relayId: String) {
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", CaptureContract.RELAY_PORT),
                    500,
                )
                socket.soTimeout = 1_000
                val out = DataOutputStream(socket.getOutputStream())
                val bytes = JSONObject()
                    .put("token", BuildConfig.CAPTURE_INGEST_TOKEN)
                    .put(CaptureContract.RELAY_ACK_FIELD, relayId)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                if (length in 1..65_535) {
                    val response = ByteArray(length)
                    input.readFully(response)
                }
                Log.i(TAG, "relay ack sent id=$relayId")
            }
        } catch (t: Throwable) {
            // ack 丢了 relay 会重试；插件侧按 captureId/rawHash 幂等。
            Log.i(TAG, "relay ack failed id=$relayId: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        ensureChannel(this)
        val contentIntent = Intent(this, PendingListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle(getString(R.string.notify_service_title))
            .setContentText(getString(R.string.notify_service_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_service),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.notify_channel_service_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TapCaptureService"
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 1000

        /**
         * 尽力启动保活服务。后台调用可能抛 ForegroundServiceStartNotAllowedException，
         * 这里吞掉即可；下一次打开插件或收到广播时会再次尝试。
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CaptureKeepAliveService::class.java),
                )
            } catch (t: Throwable) {
                Log.i(TAG, "start skipped: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
