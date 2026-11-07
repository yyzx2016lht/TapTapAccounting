package com.taostudio.tapaccounting.capture.relay

import android.app.AndroidAppHelper
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.taostudio.tapaccounting.capture.BuildConfig
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻 relay：运行在 com.android.phone（UID 1001），是插件唯一的兜底队列。
 *
 * 数据流：
 *   hook（微信/支付宝/短信/通知）
 *     -> 127.0.0.1:52046（长度前缀 + UTF-8 JSON）
 *     -> relay 落盘到 phone 私有目录 relay_queue/
 *     -> 立即用 ContentResolver.call 调主应用 CaptureIngestProvider
 *     -> 成功（含 duplicate）删队列；失败/未响应则发显式广播，并每 5 秒重试
 *     -> 主应用落库后通过 CaptureRelayAck 回 ack，relay 删队列
 *
 * 这里**不再启动插件 FGS，也不再拉起 1px Activity**，因此不会抢微信/支付宝的前台焦点。
 */
internal object RelayServer {

    private const val TAG = "TapCaptureRelay"
    private const val QUEUE_DIR = "relay_queue"
    private const val RETRY_MS = 5_000L
    private const val WORKER_TICK_MS = 1_000L

    private val started = AtomicBoolean(false)
    private val appHookInstalled = AtomicBoolean(false)
    private val inFlight = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newFixedThreadPool(2)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var queueDir: File? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val current = runCatching { AndroidAppHelper.currentApplication() }.getOrNull()
        if (current != null) {
            ensureStarted(current)
            return
        }
        if (!appHookInstalled.compareAndSet(false, true)) return
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation",
                lpparam.classLoader,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val application = param.args.getOrNull(0) as? Application ?: return
                            if (application.packageName == lpparam.packageName) {
                                ensureStarted(application)
                            }
                        } catch (t: Throwable) {
                            log("application callback failed: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                },
            )
            log("waiting for Application: ${lpparam.packageName}")
        } catch (t: Throwable) {
            log("Instrumentation hook failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun ensureStarted(appContext: Context) {
        if (!started.compareAndSet(false, true)) return
        val ctx = appContext.applicationContext ?: appContext
        context = ctx

        val dir = File(ctx.filesDir, QUEUE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            log("queue dir create failed: ${dir.absolutePath}")
            started.set(false)
            return
        }
        queueDir = dir

        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), CaptureContract.RELAY_PORT),
                50,
            )
            serverSocket = socket
            log(
                "started host=${ctx.packageName} port=${CaptureContract.RELAY_PORT} " +
                    "queue=${dir.absolutePath}",
            )
            Thread({ acceptLoop(socket) }, "TapCaptureRelayAccept").apply {
                isDaemon = true
                start()
            }
            Thread({ workerLoop() }, "TapCaptureRelayWorker").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            // 端口被别的进程占用是正常情况：让已有 relay 继续服务。
            log("bind skipped: ${t.javaClass.simpleName}: ${t.message}")
            started.set(false)
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute { handleClient(client) }
            } catch (t: Throwable) {
                if (socket.isClosed) break
                log("accept failed: ${t.javaClass.simpleName}: ${t.message}")
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val length = try {
                    input.readInt()
                } catch (_: Throwable) {
                    return
                }
                if (length <= 0 || length > CaptureContract.RELAY_MAX_BYTES) {
                    respond(output, false, "bad length")
                    return
                }

                val bytes = ByteArray(length)
                input.readFully(bytes)
                val obj = try {
                    JSONObject(String(bytes, Charsets.UTF_8))
                } catch (_: Throwable) {
                    respond(output, false, "bad json")
                    return
                }

                val token = obj.optString("token", "")
                if (token != BuildConfig.CAPTURE_INGEST_TOKEN) {
                    respond(output, false, "invalid token")
                    return
                }

                // 主应用落库后的 ACK：删除队列文件。
                val ackId = obj.optString(CaptureContract.RELAY_ACK_FIELD, "").trim()
                if (ackId.isNotEmpty()) {
                    deleteQueued(ackId)
                    respond(output, true, null)
                    return
                }

                val captureId = obj.optString("captureId", "").trim()
                val payload = obj.optString("payload", "")
                if (captureId.isEmpty() || payload.isEmpty()) {
                    respond(output, false, "missing captureId/payload")
                    return
                }

                val dir = queueDir ?: run {
                    respond(output, false, "relay not ready")
                    return
                }
                val file = File(dir, safeName(captureId) + ".json")
                writeAtomic(file, obj.toString())
                inFlight[captureId] = System.currentTimeMillis()
                respond(output, true, null)
                log("queued id=$captureId size=${bytes.size}")
                executor.execute { deliverToApp(payload, captureId) }
            } catch (t: Throwable) {
                log("client failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun workerLoop() {
        while (true) {
            try {
                val dir = queueDir
                if (dir != null) {
                    val now = System.currentTimeMillis()
                    dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                        ?.forEach { file ->
                            val id = file.nameWithoutExtension
                            val last = inFlight[id] ?: 0L
                            if (now - last < RETRY_MS) return@forEach
                            inFlight[id] = now
                            val payload = runCatching {
                                JSONObject(file.readText(Charsets.UTF_8)).optString("payload", "")
                            }.getOrNull()
                            if (payload.isNullOrBlank()) {
                                file.delete()
                                inFlight.remove(id)
                            } else {
                                deliverToApp(payload, id)
                            }
                        }
                }
            } catch (t: Throwable) {
                log("worker failed: ${t.javaClass.simpleName}: ${t.message}")
            }

            try {
                Thread.sleep(WORKER_TICK_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /**
     * 优先直接调主应用 Provider（会拉起/解冻主应用，不需要 Activity）；
     * Provider 不可用时发显式广播；广播结果由主应用 CaptureRelayAck 回 ACK。
     */
    private fun deliverToApp(payload: String, relayId: String) {
        val ctx = context ?: return
        if (tryAppProvider(ctx, payload)) {
            deleteQueued(relayId)
            log("delivered via app provider id=$relayId")
            return
        }
        try {
            val intent = Intent(CaptureContract.APP_INGEST_ACTION).apply {
                component = ComponentName(
                    CaptureContract.APP_PACKAGE,
                    CaptureContract.APP_INGEST_RECEIVER_CLASS,
                )
                putExtra(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putExtra(CaptureContract.EXTRA_PAYLOAD, payload)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            ctx.sendBroadcast(intent)
            log("broadcast app id=$relayId")
        } catch (t: Throwable) {
            log("broadcast app failed id=$relayId: ${t.javaClass.simpleName}: ${t.message}")
        }
        // 不在这里删队列：等主应用落库后通过 52046 ack 删除；worker 会继续重试。
    }

    private fun tryAppProvider(ctx: Context, payload: String): Boolean {
        return try {
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putString(CaptureContract.EXTRA_PAYLOAD, payload)
            }
            val bundle = ctx.contentResolver.call(
                Uri.parse("content://${CaptureContract.APP_INGEST_AUTHORITY}"),
                CaptureContract.APP_INGEST_METHOD,
                BuildConfig.CAPTURE_INGEST_TOKEN,
                extras,
            )
            val ack = bundle?.getString(CaptureContract.ACK_RESULT) ?: return false
            JSONObject(ack).optBoolean("ok", false)
        } catch (t: Throwable) {
            log("app provider failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun deleteQueued(relayId: String) {
        val dir = queueDir ?: return
        val file = File(dir, safeName(relayId) + ".json")
        if (file.exists() && file.delete()) {
            log("ack id=$relayId")
        } else {
            log("ack id=$relayId (file missing)")
        }
        inFlight.remove(relayId)
    }

    private fun writeAtomic(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                throw IllegalStateException("rename failed: ${temp.absolutePath}")
            }
        }
    }

    private fun respond(output: DataOutputStream, ok: Boolean, error: String?) {
        try {
            val json = JSONObject().put("ok", ok)
            if (!ok && error != null) json.put("error", error)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        } catch (_: Throwable) {
            // 客户端可能已经断开。
        }
    }

    private fun safeName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    private fun log(message: String) {
        try {
            XposedBridge.log("[$TAG] $message")
        } catch (_: Throwable) {
            // ignore
        }
    }
}
