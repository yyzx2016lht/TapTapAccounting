package com.taostudio.tapaccounting.capture.service

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.ingest.CaptureIngestHandler
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 插件进程内直连 TCP 服务端（127.0.0.1:52047）。
 *
 * 为什么需要：
 * 在 ColorOS / Android 15+ 上，后台进程（如 com.android.phone 的 RelayServer）调用
 * startForegroundService 拉起插件会被系统以 mAllowStart_noBinding=PROC_STATE_TOP 静默拦截，
 * 导致依赖广播或 FGS Intent 投递延迟高达 10~30 秒。
 *
 * 只要插件进程存活（由 CaptureKeepAliveService 保活），微信/支付宝 Hook 线程或 RelayServer
 * 直接通过 127.0.0.1:52047 投递 JSON，1~3ms 即可完成规则匹配、入库与弹出悬浮气泡。
 */
object PluginDirectServer {

    private const val TAG = "TapCaptureDirect"
    private val started = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var appContext: Context? = null

    /** 清理旧版卡在 com.android.phone relay_queue 里的遗留任务 ID。 */
    private val STALE_RELAY_IDS = listOf(
        "fa6a225c-26e6-4d19-8750-71f1256e5924",
        "490294f7-6e34-465d-b972-ce3d8d3426d8",
        "cdf55062-c225-4b33-a2f0-dbffbe4ce0ff",
        "e5f1ff11-e065-4dc9-a353-2b56afec6537",
    )

    fun ensureStarted(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        if (!started.compareAndSet(false, true)) return

        executor.execute {
            STALE_RELAY_IDS.forEach { sendRelayAck(it) }
        }

        Thread({ runServer() }, "TapCaptureDirectServer").apply {
            isDaemon = true
            start()
        }
    }

    private fun runServer() {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(
                InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    CaptureContract.PLUGIN_DIRECT_PORT,
                ),
                50,
            )
            Log.i(TAG, "direct server listening on 127.0.0.1:${CaptureContract.PLUGIN_DIRECT_PORT}")
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    executor.execute { handleClient(client) }
                } catch (t: Throwable) {
                    if (server.isClosed) break
                    Log.w(TAG, "accept error: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bind failed on ${CaptureContract.PLUGIN_DIRECT_PORT}: ${t.message}")
            started.set(false)
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 3_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val length = try {
                    input.readInt()
                } catch (_: Throwable) {
                    return
                }
                if (length <= 0 || length > CaptureContract.RELAY_MAX_BYTES) {
                    writeResponse(output, JSONObject().put("ok", false).put("error", "bad length").toString())
                    return
                }

                val bytes = ByteArray(length)
                input.readFully(bytes)
                val obj = JSONObject(String(bytes, Charsets.UTF_8))
                val token = obj.optString("token", "")
                val payload = obj.optString("payload", "")
                val relayId = obj.optString(CaptureContract.EXTRA_RELAY_ID, "")
                    .ifEmpty { obj.optString("captureId", "") }

                val ctx = appContext
                if (ctx == null) {
                    writeResponse(output, JSONObject().put("ok", false).put("error", "no context").toString())
                    return
                }
                if (token != BuildConfig.CAPTURE_INGEST_TOKEN || payload.isBlank()) {
                    writeResponse(output, JSONObject().put("ok", false).put("error", "invalid token/payload").toString())
                    return
                }

                // 立即返回 TCP ACK（<2ms），避免发送方在 soTimeout 上阻塞等待 QuickJS/DB；
                // 进程被 Hans 冻结时 accept 线程不会运行，发送方 80ms 超时即可立即识别出进程已冻结并切换唤醒通道。
                writeResponse(output, JSONObject().put("ok", true).put("captureId", relayId).toString())
                ingestAsync(ctx, token, payload, relayId)
            } catch (t: Throwable) {
                Log.w(TAG, "client handle failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    fun ingestAsync(context: Context, token: String, payload: String, relayId: String) {
        val app = context.applicationContext ?: context
        appContext = app
        executor.execute {
            try {
                val ackJson = CaptureIngestHandler.ingest(app, token, payload)
                Log.i(TAG, "direct ingest done id=$relayId ack=$ackJson")
                if (relayId.isNotEmpty() && JSONObject(ackJson).optBoolean("ok", false)) {
                    sendRelayAck(relayId)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "async ingest failed id=$relayId: ${t.message}")
            }
        }
    }

    private fun writeResponse(output: DataOutputStream, json: String) {
        try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        } catch (_: Throwable) {
        }
    }

    fun sendRelayAck(relayId: String) {
        if (relayId.isBlank()) return
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", CaptureContract.RELAY_PORT),
                    400,
                )
                socket.soTimeout = 800
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
        } catch (_: Throwable) {
        }
    }
}
