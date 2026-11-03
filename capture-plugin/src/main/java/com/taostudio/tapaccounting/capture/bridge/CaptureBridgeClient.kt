package com.taostudio.tapaccounting.capture.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 插件 → 敲敲记账 [CaptureContract.BRIDGE_AUTHORITY] 的唯一调用入口。
 *
 * App 侧 `CaptureBridgeProvider.call()` 返回 `Bundle[ACK_RESULT]`：
 * - 普通方法返回 JSONObject（`{ok:true, ...}` / `{ok:false, error:...}`）；
 * - `getImportStatus` 返回 JSONArray。
 *
 * 所有方法内部切到 [Dispatchers.IO]，调用方即使从主线程 `launch` 也不会阻塞 UI；
 * 任何异常（App 未安装、无签名权限、binder 死亡、JSON 损坏）都吞掉并返回失败值，
 * 插件不会因为桥接不可用而崩溃。
 */
class CaptureBridgeClient(context: Context) {

    private val appContext: Context = context.applicationContext
    private val bridgeUri: Uri = Uri.parse("content://${CaptureContract.BRIDGE_AUTHORITY}")

    /** App 桥接是否可用。 */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val json = callForObject(CaptureContract.METHOD_PING, null, null) ?: return@withContext false
        json.optBoolean("ok", false)
    }

    /**
     * 查询 [captureIds] 在 App 侧 `external_imports` 里的状态。
     *
     * 返回 captureId → RECORDED/UNKNOWN。App 只回答“已写入”或“查不到”，
     * IMPORTING 之类是插件本地状态，不在这里出现。
     * 桥接不可用/返回为空时返回空 Map（调用方据此保持本地状态不变）。
     */
    suspend fun getImportStatus(captureIds: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val ids = captureIds.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_IDS)
                .toList()
            if (ids.isEmpty()) return@withContext emptyMap()

            val arg = JSONArray(ids).toString()
            val extras = Bundle().apply {
                putString(CaptureContract.EXTRA_CAPTURE_IDS, arg)
            }
            val array = callForArray(CaptureContract.METHOD_GET_IMPORT_STATUS, arg, extras)
                ?: return@withContext emptyMap()

            val result = LinkedHashMap<String, String>(ids.size)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("captureId", "").trim()
                if (id.isEmpty()) continue
                result[id] = item.optString("status", "")
                    .trim()
                    .uppercase(Locale.US)
                    .ifEmpty { STATUS_UNKNOWN }
            }
            // App 没返回的 id 视为未确认，避免调用方把“缺省”误判成已记。
            ids.forEach { id -> result.putIfAbsent(id, STATUS_UNKNOWN) }
            result
        }

    /**
     * 让 App 打开悬浮窗“自己记”表单。
     *
     * @return App 是否接受了请求（`ok:true`）。已存在回执时 App 会返回
     * `ok:true, duplicate:true`，同样视为成功；下一次对账会把本地状态推进为 RECORDED。
     */
    suspend fun openQuickRecord(
        prefill: JSONObject,
        captureId: String,
        requestId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val extras = Bundle().apply {
            putString(CaptureContract.EXTRA_PREFILL, prefill.toString())
            putString(CaptureContract.EXTRA_CAPTURE_ID, captureId)
            if (!requestId.isNullOrBlank()) {
                putString(CaptureContract.EXTRA_REQUEST_ID, requestId)
            }
        }
        val json = callForObject(CaptureContract.METHOD_OPEN_QUICK_RECORD, null, extras)
            ?: return@withContext false
        json.optBoolean("ok", false)
    }

    // ---------------- 底层调用 ----------------

    private fun callForObject(method: String, arg: String?, extras: Bundle?): JSONObject? {
        val raw = callRaw(method, arg, extras) ?: return null
        return runCatching { JSONObject(raw) }
            .onFailure { Log.w(TAG, "bad JSONObject from $method", it) }
            .getOrNull()
    }

    private fun callForArray(method: String, arg: String?, extras: Bundle?): JSONArray? {
        val raw = callRaw(method, arg, extras) ?: return null
        return runCatching { JSONArray(raw) }
            .onFailure { Log.w(TAG, "bad JSONArray from $method", it) }
            .getOrNull()
    }

    /** 返回 [CaptureContract.ACK_RESULT] 的原始 JSON 文本；任何失败都返回 null。 */
    private fun callRaw(method: String, arg: String?, extras: Bundle?): String? = try {
        val bundle = appContext.contentResolver.call(bridgeUri, method, arg, extras)
        bundle?.getString(CaptureContract.ACK_RESULT)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        // App 未安装 / 无 CAPTURE_BRIDGE 权限 / Provider 未导出 / binder 异常。
        Log.w(TAG, "bridge call failed: $method", t)
        null
    }

    companion object {
        private const val TAG = "TapCaptureBridge"
        private const val MAX_IDS = 200
        private const val STATUS_UNKNOWN = "UNKNOWN"
    }
}
