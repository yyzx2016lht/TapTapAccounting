package com.taostudio.tapaccounting.capture.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.taostudio.tapaccounting.capture.contract.CaptureContract

/**
 * 调试/兼容入口：通过 `content call` 或声明了 provider authority 的同机应用调用。
 *
 * 微信/支付宝进程没有在 manifest 中声明本插件，Android 11+ 包可见性会过滤掉这个
 * authority，因此真实 hook 走 [CaptureIngestReceiver] 的显式广播；本 Provider 保留
 * 给 adb 调试和显式声明了 authority 的调用方。
 */
class CaptureIngestProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != CaptureContract.INGEST_METHOD) {
            return bundleOf(error("unknown method: $method"))
        }

        val token = extras?.getString(CaptureContract.EXTRA_TOKEN) ?: arg
        val payloadJson = extras?.getString(CaptureContract.EXTRA_PAYLOAD)
        val ctx = context ?: return bundleOf(error("provider not attached"))
        return bundleOf(CaptureIngestHandler.ingest(ctx, token, payloadJson))
    }

    private fun error(message: String): String =
        org.json.JSONObject()
            .put("ok", false)
            .put("contractVersion", CaptureContract.VERSION)
            .put("error", message)
            .toString()

    private fun bundleOf(json: String): Bundle = Bundle().apply {
        putString(CaptureContract.ACK_RESULT, json)
    }

    // P0 只读 call()，其余入口最小化。
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("ingest only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ingest only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("ingest only")
}
