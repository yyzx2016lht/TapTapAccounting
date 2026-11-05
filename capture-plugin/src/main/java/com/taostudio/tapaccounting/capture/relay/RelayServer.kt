package com.taostudio.tapaccounting.capture.relay

import android.app.AndroidAppHelper
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
 * 常驻 relay server，等价于 AutoAccounting 跑在 com.android.phone 里的本地服务。
 *
 * 设计目标：不再依赖“广播 + 插件 App 进程存活”。
 *
 * 流程：
 *   hook（微信/支付宝/系统通知/短信）
 *     -> 127.0.0.1:52046（长度前缀 + UTF-8 JSON）
 *     -> relay 落盘到宿主私有目录 relay_queue/
 *     -> 拉起插件 CaptureKeepAliveService（前台服务）
 *     -> 插件跑规则/入库
 *     -> 插件通过同一端口回 ack
 *     -> relay 删除队列文件
 *
 * relay 会在第一个成功 bind 端口的宿主进程里运行：通常是 com.android.phone，
 * 也可能重启后先被微信/支付宝抢到。端口被占用时跳过，由已存在的 relay 服务。
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

    /**
     * 在 hook 进程里安装 relay。应用 Application 还没创建时，等
     * Instrumentation.callApplicationOnCreate 再启动。
     */
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

    /** 已有 Context（例如 system_server 的 systemContext）时直接启动。 */
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
                InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    CaptureContract.RELAY_PORT,
                ),
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
                } catch (t: Throwable) {
                    respond(output, false, "bad json")
                    return
                }

                val token = obj.optString("token", "")
                if (token != BuildConfig.CAPTURE_INGEST_TOKEN) {
                    respond(output, false, "invalid token")
                    return
                }

                // 插件入库后的 ack：删除队列文件。
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
                // CaptureForwarder 只有在直连 52047 失败（插件被 Hans 冻结或未启动）时才会投递到 52046，
                // 因此这里不等待轮询、也不再重复探测 52047，立即由 UID 1001 拉起透明唤醒 Activity 强制解冻！
                executor.execute { startPluginService(file, captureId, immediateWake = true) }
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
                            startPluginService(file, id, immediateWake = false)
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

    private fun startPluginService(file: File, relayId: String, immediateWake: Boolean = false) {
        val ctx = context ?: return
        val payload = try {
            JSONObject(file.readText(Charsets.UTF_8)).optString("payload", "")
        } catch (t: Throwable) {
            log("read queue failed id=$relayId: ${t.javaClass.simpleName}")
            inFlight.remove(relayId)
            return
        }
        if (payload.isEmpty()) {
            file.delete()
            inFlight.remove(relayId)
            return
        }

        // 1. 优先尝试 52047 直连（若插件已被解冻或在线，1~3ms 内直接完成）
        if (tryDirectPlugin(payload, relayId)) {
            deleteQueued(relayId)
            log("delivered via direct tcp id=$relayId")
            return
        }

        // 2. 优先通过 ContentProvider.call 投递：
        //    com.android.phone (UID 1001) 调用 ContentResolver.call 会由系统在后台启动/解冻目标进程，
        //    并且完全不会抢夺前台 Window 焦点，彻底解决微信聊天时输入法被顶退失去焦点的问题！
        if (tryProviderCall(ctx, payload)) {
            deleteQueued(relayId)
            log("delivered via provider id=$relayId")
            return
        }

        // 3. 次选通过显式高优广播唤醒插件进程
        try {
            val broadcastIntent = Intent(CaptureContract.ACTION_INGEST).apply {
                component = ComponentName(
                    CaptureContract.PLUGIN_PACKAGE,
                    CaptureContract.INGEST_RECEIVER_CLASS,
                )
                putExtra(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putExtra(CaptureContract.EXTRA_PAYLOAD, payload)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            ctx.sendBroadcast(broadcastIntent)
        } catch (t: Throwable) {
            log("broadcast plugin failed id=$relayId: ${t.javaClass.simpleName}")
        }

        // 广播发出后稍作等待再次直连
        try {
            Thread.sleep(30)
        } catch (_: InterruptedException) {
        }
        if (tryDirectPlugin(payload, relayId)) {
            deleteQueued(relayId)
            log("delivered via direct tcp after broadcast id=$relayId")
            return
        }

        // 4. 只有在确认是明确账单（payload 自带金额），且后台 IPC 均失效的极端兜底情况下，才考虑透明 Activity
        val hasExplicitAmount = runCatching {
            val json = JSONObject(payload)
            json.has("amountCents") && json.optLong("amountCents", 0L) > 0L
        }.getOrDefault(false)

        if (hasExplicitAmount) {
            try {
                val wakeIntent = Intent(CaptureContract.ACTION_WAKE_INGEST).apply {
                    component = ComponentName(
                        CaptureContract.PLUGIN_PACKAGE,
                        CaptureContract.WAKE_ACTIVITY_CLASS,
                    )
                    putExtra(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                    putExtra(CaptureContract.EXTRA_PAYLOAD, payload)
                    putExtra(CaptureContract.EXTRA_RELAY_ID, relayId)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                }
                ctx.startActivity(wakeIntent)
                log("started wake activity as fallback id=$relayId host=${ctx.packageName}")
            } catch (t: Throwable) {
                log("wake activity fallback failed id=$relayId: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun tryDirectPlugin(payload: String, relayId: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", CaptureContract.PLUGIN_DIRECT_PORT),
                    80,
                )
                socket.soTimeout = 100
                val out = DataOutputStream(socket.getOutputStream())
                val envelope = JSONObject()
                    .put("token", BuildConfig.CAPTURE_INGEST_TOKEN)
                    .put("captureId", relayId)
                    .put(CaptureContract.EXTRA_RELAY_ID, relayId)
                    .put("payload", payload)
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                if (length <= 0 || length > 64 * 1024) return false
                val response = ByteArray(length)
                input.readFully(response)
                JSONObject(String(response, Charsets.UTF_8)).optBoolean("ok", false)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryProviderCall(ctx: Context, payload: String): Boolean {
        return try {
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_TOKEN, BuildConfig.CAPTURE_INGEST_TOKEN)
                putString(CaptureContract.EXTRA_PAYLOAD, payload)
            }
            val bundle = ctx.contentResolver.call(
                Uri.parse("content://${CaptureContract.INGEST_AUTHORITY}"),
                CaptureContract.INGEST_METHOD,
                BuildConfig.CAPTURE_INGEST_TOKEN,
                extras,
            )
            val ack = bundle?.getString(CaptureContract.ACK_RESULT) ?: return false
            JSONObject(ack).optBoolean("ok", false)
        } catch (_: Throwable) {
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
