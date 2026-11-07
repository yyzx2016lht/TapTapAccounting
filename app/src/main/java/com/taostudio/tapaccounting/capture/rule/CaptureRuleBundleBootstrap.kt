package com.taostudio.tapaccounting.capture.rule

import android.content.Context
import android.net.Uri
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import com.taostudio.tapaccounting.capture.data.RuleBundleStore
import org.json.JSONObject

/**
 * 主应用首次启动/规则缺失时，从已安装的旧插件导出 AutoAccounting 规则包。
 *
 * 主应用 APK 不内置 GPL 规则内容，所以 P1~P3 期间必须依赖这条迁移链路：
 * - 优先 `ContentResolver.openInputStream(content://<plugin>/rule_bundle)`（不受 Binder 1MB 限制）；
 * - 旧插件不支持 openFile 时，回退到 `call("exportLegacyRuleBundle")`；
 * - 插件没装/导出失败时静默返回 false，规则引擎只跑本地备注/资产规则。
 */
object CaptureRuleBundleBootstrap {

    private const val TAG = "TapCaptureRuleImport"

    @Synchronized
    fun ensureImported(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext ?: context
        if (!force && RuleBundleStore.hasBundle(app)) return true
        if (!isPluginInstalled(app)) {
            Log.i(TAG, "plugin not installed; skip rule bundle import")
            return false
        }

        val fromFile = runCatching { readRuleBundleFile(app) }.getOrNull()
        if (!fromFile.isNullOrBlank()) {
            return install(app, fromFile, "openFile")
        }

        val fromCall = runCatching { readRuleBundleByCall(app) }.getOrNull()
        if (!fromCall.isNullOrBlank()) {
            return install(app, fromCall, "call")
        }

        Log.w(TAG, "rule bundle import failed: plugin provider unavailable")
        return false
    }

    private fun isPluginInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(CaptureContract.PLUGIN_PACKAGE, 0) != null
    }.getOrDefault(false)

    private fun readRuleBundleFile(context: Context): String? {
        val uri = Uri.parse("content://${CaptureContract.INGEST_AUTHORITY}/${CaptureContract.LEGACY_RULE_BUNDLE_PATH}")
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun readRuleBundleByCall(context: Context): String? {
        val uri = Uri.parse("content://${CaptureContract.INGEST_AUTHORITY}")
        // 旧插件没有这个方法时会返回 null/异常，回退为空即可。
        val bundle = context.contentResolver.call(
            uri,
            CaptureContract.METHOD_EXPORT_LEGACY_RULE_BUNDLE,
            null,
            null,
        ) ?: return null
        val raw = bundle.getString(CaptureContract.ACK_RESULT)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) return null
        return json.optString("json", "").takeIf { it.isNotBlank() }
    }

    private fun install(context: Context, json: String, source: String): Boolean = try {
        val bundle = RuleBundleStore.installFromJson(context, json)
        Log.i(
            TAG,
            "rule bundle imported from $source: rules=${bundle.rules.size} " +
                "enabled=${bundle.enabledCount} source=${bundle.source}",
        )
        true
    } catch (t: Throwable) {
        Log.w(TAG, "rule bundle install failed from $source: ${t.javaClass.simpleName}: ${t.message}")
        false
    }
}
