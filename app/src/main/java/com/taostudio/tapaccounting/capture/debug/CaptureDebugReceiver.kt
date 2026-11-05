package com.taostudio.tapaccounting.capture.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taostudio.tapaccounting.BuildConfig
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.ingest.CaptureIngestCoordinator
import com.taostudio.tapaccounting.capture.data.CaptureRepository
import com.taostudio.tapaccounting.capture.data.RuleBundleStore
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import com.taostudio.tapaccounting.capture.rule.CaptureLegacyImporter
import com.taostudio.tapaccounting.capture.rule.CaptureRuleBundleBootstrap
import com.taostudio.tapaccounting.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * P1 调试入口（adb 可用）：
 *
 * ```text
 * # 查看规则包/待记状态
 * adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_PING
 *
 * # 从插件重新导入规则包（覆盖主应用 filesDir/rule_bundle.json）
 * adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_IMPORT_RULES --ez force true
 *
 * # 规则包改动后让引擎重新加载
 * adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_RELOAD_RULES
 *
 * # 临时打开主应用通知（P1 默认静默，避免和旧插件重复提醒）
 * adb shell am broadcast -a com.taostudio.tapaccounting.action.CAPTURE_SET_PROMPT_MODE \
 *   --es mode NOTIFICATION
 * ```
 *
 * 不写任何敏感数据；exported 只为方便 adb shell 调试。
 */
class CaptureDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val app = context?.applicationContext ?: return
        when (intent?.action) {
            ACTION_SET_PROMPT_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE)?.trim()?.uppercase()
                if (mode !in VALID_MODES) {
                    Log.w(TAG, "invalid prompt mode: $mode")
                    return
                }
                CapturePrefs.setPromptMode(app, mode!!)
                Log.i(TAG, "prompt mode set to $mode")
            }

            ACTION_RELOAD_RULES -> {
                AutoAccountingRuleEngine.invalidate()
                val imported = CaptureRuleBundleBootstrap.ensureImported(app)
                Log.i(TAG, "rules reloaded; bundlePresent=${RuleBundleStore.hasBundle(app)} imported=$imported")
            }

            ACTION_IMPORT_RULES -> {
                val force = intent.getBooleanExtra(EXTRA_FORCE, true)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val imported = CaptureRuleBundleBootstrap.ensureImported(app, force = force)
                        val result = JSONObject()
                            .put("ok", imported)
                            .put("bundlePresent", RuleBundleStore.hasBundle(app))
                            .toString()
                        pending.setResultData(result)
                    } catch (t: Throwable) {
                        Log.w(TAG, "import rules failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_SIMULATE -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val now = System.currentTimeMillis()
                        // adb 调试既可能用 --ei(int) 也可能用 --el(long)，两种都接。
                        val longAmount = intent.getLongExtra(EXTRA_AMOUNT_CENTS, -1L)
                        val intAmount = intent.getIntExtra(EXTRA_AMOUNT_CENTS, -1)
                        val amountCents = (if (longAmount > 0L) longAmount else intAmount.toLong())
                            .takeIf { it > 0L }
                            ?: 900L
                        val merchant = intent.getStringExtra(EXTRA_MERCHANT)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: "同程旅行"
                        val amountText = String.format(Locale.CHINA, "%.2f", amountCents / 100.0)
                        val rawJson = JSONObject().put(
                            "mMap",
                            JSONObject()
                                .put(
                                    "description",
                                    "使用招商银行储蓄卡(4852)支付￥$amountText",
                                )
                                .put("title", "已支付¥$amountText")
                                .put("display_name", merchant)
                                .put("source", "微信支付")
                                .put("t", now),
                        ).toString()
                        val payload = JSONObject()
                            .put("captureId", UUID.randomUUID().toString())
                            .put("source", "WECHAT")
                            .put("rawType", "DATA")
                            .put("currency", "CNY")
                            .put("paidAt", now)
                            .put("capturedAt", now)
                            .put("rawJson", rawJson)
                            .put("ruleVersion", "debug-sim")
                            .toString()
                        val ack = CaptureIngestCoordinator.ingest(
                            app,
                            BuildConfig.CAPTURE_INGEST_TOKEN,
                            payload,
                        )
                        Log.i(TAG, "simulate ack=$ack")
                        pending.setResultData(ack)
                    } catch (t: Throwable) {
                        Log.w(TAG, "simulate failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_IMPORT_LEGACY -> {
                val force = intent.getBooleanExtra(EXTRA_FORCE, true)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val imported = CaptureLegacyImporter.importIfNeeded(app, force = force)
                        pending.setResultData(
                            JSONObject().put("ok", imported).toString()
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "import legacy failed", t)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_PING -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val repository = CaptureRepository(AppDatabase.getDatabase(app).paymentCaptureDao())
                        val payload = JSONObject()
                            .put("ok", true)
                            .put("bundlePresent", RuleBundleStore.hasBundle(app))
                            .put("promptMode", CapturePrefs.getPromptMode(app))
                            .put("onlyExpense", CapturePrefs.isOnlyExpense(app))
                            .put("pendingCount", repository.countPending())
                            .put("pluginInstalled", isPluginInstalled(app))
                        Log.i(TAG, "ping=$payload")
                        pending.setResultData(payload.toString())
                    } catch (t: Throwable) {
                        pending.setResultData(
                            JSONObject().put("ok", false).put("error", t.message.orEmpty()).toString()
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun isPluginInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(CaptureContract.PLUGIN_PACKAGE, 0) != null
    }.getOrDefault(false)

    companion object {
        const val ACTION_SET_PROMPT_MODE =
            "com.taostudio.tapaccounting.action.CAPTURE_SET_PROMPT_MODE"
        const val ACTION_RELOAD_RULES =
            "com.taostudio.tapaccounting.action.CAPTURE_RELOAD_RULES"
        const val ACTION_IMPORT_RULES =
            "com.taostudio.tapaccounting.action.CAPTURE_IMPORT_RULES"
        const val ACTION_IMPORT_LEGACY =
            "com.taostudio.tapaccounting.action.CAPTURE_IMPORT_LEGACY"
        const val ACTION_PING =
            "com.taostudio.tapaccounting.action.CAPTURE_PING"
        const val ACTION_SIMULATE =
            "com.taostudio.tapaccounting.action.CAPTURE_SIMULATE"

        const val EXTRA_MODE = "mode"
        const val EXTRA_FORCE = "force"
        const val EXTRA_AMOUNT_CENTS = "amountCents"
        const val EXTRA_MERCHANT = "merchant"

        private val VALID_MODES = setOf(
            CapturePrefs.MODE_BUBBLE,
            CapturePrefs.MODE_NOTIFICATION,
            CapturePrefs.MODE_SILENT,
        )
        private const val TAG = "TapCaptureDebug"
    }
}
