package com.taostudio.tapaccounting.capture.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * relay / 调试工具 → 主应用的第二个入口（ContentProvider.call）。
 *
 * 只实现 `ingest`；不暴露任何通用 CRUD。调用方必须带
 * [CaptureContract.EXTRA_TOKEN]（BuildConfig.CAPTURE_INGEST_TOKEN）。
 *
 * 与 `CaptureBridgeProvider` 分开：后者是 signature 级、给旧插件调 AI/入账用的；
 * 本 Provider 允许 hook/relay 这类不同签名的进程用 token 投递原始报文。
 */
class CaptureIngestProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return bundleOf(errorJson("provider not attached"))
        if (method != CaptureContract.APP_INGEST_METHOD) {
            return bundleOf(errorJson("unknown_method: $method"))
        }
        val token = extras?.getString(CaptureContract.EXTRA_TOKEN) ?: arg
        val payload = extras?.getString(CaptureContract.EXTRA_PAYLOAD)
        val ack = try {
            runBlocking(Dispatchers.IO) {
                CaptureIngestCoordinator.ingest(ctx, token, payload)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "provider ingest failed", t)
            errorJson(t.message ?: t.javaClass.simpleName)
        }
        return bundleOf(ack)
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("ok", false)
        .put("contractVersion", CaptureContract.VERSION)
        .put("error", message)
        .toString()

    private fun bundleOf(json: String): Bundle = Bundle().apply {
        putString(CaptureContract.ACK_RESULT, json)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException("CaptureIngestProvider only supports call()")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("CaptureIngestProvider only supports call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("CaptureIngestProvider only supports call()")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("CaptureIngestProvider only supports call()")

    private companion object {
        const val TAG = "TapCaptureIngestApp"
    }
}
