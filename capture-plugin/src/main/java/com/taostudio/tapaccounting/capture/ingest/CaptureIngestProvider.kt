package com.taostudio.tapaccounting.capture.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * P5 后插件唯一保留的 Provider：只读的「旧数据迁移出口」。
 *
 * - `openFile("rule_bundle")`：导出插件内置/旧 filesDir 里的 AutoAccounting 规则包；
 * - `openFile("legacy_db")`：导出旧 `capture.db`（WAL checkpoint 后），主应用用 ATTACH 并入；
 * - `call("exportLegacyRuleBundle")`：给不支持 openFile 的旧主应用兜底。
 *
 * 不接收 ingest，不再持有 Room/规则/UI；调用方必须是同签名主应用。
 */
class CaptureIngestProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return bundleOf(errorJson("provider not attached"))
        if (!isAuthorizedCaller(ctx)) return bundleOf(errorJson("unauthorized caller"))

        val result = when (method) {
            CaptureContract.METHOD_EXPORT_LEGACY_RULE_BUNDLE -> {
                val text = readRuleBundleText(ctx)
                JSONObject()
                    .put("ok", true)
                    .put("size", text.length)
                    .put("json", text)
                    .toString()
            }

            CaptureContract.METHOD_PING -> JSONObject()
                .put("ok", true)
                .put("contractVersion", CaptureContract.VERSION)
                .put("legacyExportOnly", true)
                .toString()

            else -> errorJson("unknown_method: $method")
        }
        return bundleOf(result)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: throw FileNotFoundException("provider not attached")
        if (!isAuthorizedCaller(ctx)) throw SecurityException("unauthorized caller")

        return when (uri.path?.trim('/')) {
            CaptureContract.LEGACY_RULE_BUNDLE_PATH -> {
                val text = readRuleBundleText(ctx)
                val file = File(ctx.cacheDir, "rule_bundle_export.json")
                file.writeText(text, Charsets.UTF_8)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            CaptureContract.LEGACY_DB_PATH -> {
                val dbFile = ctx.getDatabasePath("capture.db")
                if (!dbFile.isFile) throw FileNotFoundException("legacy capture.db not found")
                // 旧 DB 可能是 WAL 模式；先把 WAL 合并回主文件，避免主应用复制到半截数据。
                runCatching {
                    val db = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    )
                    try {
                        db.execSQL("PRAGMA wal_checkpoint(FULL)")
                    } finally {
                        db.close()
                    }
                }
                ParcelFileDescriptor.open(dbFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            else -> super.openFile(uri, mode)
        }
    }

    private fun readRuleBundleText(ctx: Context): String {
        val file = File(ctx.filesDir, "rule_bundle.json")
        if (file.isFile) {
            return file.readText(Charsets.UTF_8)
        }
        return ctx.assets.open("autoaccounting_rules.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    private fun isAuthorizedCaller(ctx: Context): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid() ||
            callingUid == Process.ROOT_UID ||
            callingUid == Process.SHELL_UID
        ) {
            return true
        }
        if (ctx.checkCallingPermission(CaptureContract.BRIDGE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return ctx.packageManager.checkSignatures(Process.myUid(), callingUid) ==
            PackageManager.SIGNATURE_MATCH
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
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("legacy export only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("legacy export only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("legacy export only")
}
