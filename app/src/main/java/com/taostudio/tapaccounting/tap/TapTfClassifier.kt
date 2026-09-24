package com.taostudio.tapaccounting.tap

import android.content.res.AssetManager
import android.os.Build
import android.os.Handler
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

class TapTfClassifier(
    assetManager: AssetManager,
    modelPath: String,
    private val lowPowerEnabled: Boolean = false
) : TfClassifier() {

    companion object {
        private const val TAG = "TapDetector"

        /** 手势识别链路排查开关：置 true 会打印每次推理结果，仅用于定位"敲了没反应" */
        const val TAP_DIAG = false

        /** 交给 [closeOn] 后等待原生解释器释放完成的兜底时长 */
        private const val CLOSE_FALLBACK_DELAY_MS = 200L
    }

    private val nnApiDelegate by lazy {
        if (lowPowerEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                NnApiDelegate(NnApiDelegate.Options().apply {
                    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_LOW_POWER)
                    setUseNnapiCpu(true)
                })
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI not available, falling back to CPU", e)
                null
            }
        } else null
    }

    private val options by lazy {
        Interpreter.Options().apply {
            nnApiDelegate?.let { addDelegate(it) }
        }
    }

    /**
     * 单次加载，绝不重建。
     *
     * 由 [predict] 首次触发，也就是在手势传感器的回调线程（TapSensorThread）上创建；
     * 重建会引入"新解释器替换旧解释器"的并发窗口，所以会话内只加载这一次。
     */
    @Volatile
    private var interpreter: Interpreter? = null

    private val interpreterLazy: Interpreter? by lazy {
        try {
            // P2-3: FileInputStream 用完即关；mmap 在关闭后仍有效
            assetManager.openFd(modelPath).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    Interpreter(
                        stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength),
                        options
                    )
                }
            }.also { created ->
                interpreter = created
                Log.d(TAG, "tflite file loaded: $modelPath (nnapi=$lowPowerEnabled)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "load tflite file error: $modelPath", e)
            null
        }
    }

    /** 只允许释放一次 */
    private val released = AtomicBoolean(false)

    override fun predict(input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        if (released.get()) return ArrayList()
        // 必须读 interpreterLazy 才会真正加载解释器，只读 interpreter 字段会得到 null
        val owned = interpreter ?: interpreterLazy ?: return ArrayList()
        // 加载期间可能已经被 closeOn/handOff 标记释放，这里再确认一次
        if (released.get()) return ArrayList()
        return try {
            val out = predict12(owned, input, size)
            if (TAP_DIAG) Log.d(TAG, "diag: predict ok classes=${out.firstOrNull()?.size ?: 0}")
            out
        } catch (e: IllegalStateException) {
            // 解释器已关闭时 TFLite 会抛 IllegalStateException，视为本次推理无效
            Log.w(TAG, "predict on closed interpreter, dropped: ${e.message}")
            ArrayList()
        } catch (e: Exception) {
            Log.e(TAG, "predict failed: ${e.javaClass.simpleName}: ${e.message}")
            ArrayList()
        }
    }

    /**
     * 把"关闭解释器"这件事排到 [handler] 所在线程（即传感器线程）的队尾。
     *
     * 原生解释器必须"创建线程即调用线程"：它在首次推理时于 TapSensorThread 上创建，
     * 所以也只能在该线程上关闭。TFLite 的 `Interpreter.close()` 一旦与正在执行的
     * `Invoke()` 并发，原生侧会直接段错误（SIGSEGV），Java 层 try/catch 拦不住，
     * 整个进程会被原生崩溃杀掉。
     *
     * 先 post 再返回，保证所有已排队的传感器回调都先跑完，关闭动作最后执行。
     */
    fun closeOn(handler: Handler?, fallbackHandler: Handler?) {
        if (!released.compareAndSet(false, true)) return
        val owned = interpreter
        interpreter = null
        val runnable = Runnable { releaseNow(owned) }
        val target = handler ?: fallbackHandler
        if (target != null) {
            target.post(runnable)
            // 兜底：目标线程可能已经开始退出，消息未必会被消费
            fallbackHandler?.takeIf { it !== target }?.postDelayed(runnable, CLOSE_FALLBACK_DELAY_MS)
        } else {
            runnable.run()
        }
    }

    /**
     * 只切断后续推理，不在这里做原生释放。
     *
     * 适用于"换了新的运行时、旧运行时句柄即将被丢弃"的场景：
     * 排空动作由持有传感器 Handler 的一方用 [closeOn] 完成。
     */
    fun handOff(): Interpreter? {
        if (!released.compareAndSet(false, true)) return null
        val owned = interpreter
        interpreter = null
        return owned
    }

    private fun releaseNow(owned: Interpreter?) {
        try {
            owned?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close interpreter failed: ${e.message}")
        }
        try {
            nnApiDelegate?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close nnapi delegate failed: ${e.message}")
        }
    }
}

