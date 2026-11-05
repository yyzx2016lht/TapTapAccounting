package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 插件内的透明图片选择 Activity。
 *
 * 供 [PendingListActivity] 与 [com.taostudio.tapaccounting.capture.notify.CaptureBubbleManager] 唤起系统相册选择器，
 * 选择完成后在插件内部回调，不跳转主记账 App。
 */
class CaptureImagePickerActivity : Activity() {

    companion object {
        private const val REQUEST_PICK = 2001
        private var onPickedCallback: ((Uri) -> Unit)? = null
        private var onCancelledCallback: (() -> Unit)? = null

        fun pickImage(
            context: Context,
            onPicked: (Uri) -> Unit,
            onCancelled: () -> Unit = {},
        ) {
            onPickedCallback = onPicked
            onCancelledCallback = onCancelled
            val intent = Intent(context, CaptureImagePickerActivity::class.java).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }

        fun clearCallbacks() {
            onPickedCallback = null
            onCancelledCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            }
            try {
                startActivityForResult(fallback, REQUEST_PICK)
            } catch (_: Throwable) {
                onCancelledCallback?.invoke()
                clearCallbacks()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri != null) {
                onPickedCallback?.invoke(uri)
            } else {
                onCancelledCallback?.invoke()
            }
            clearCallbacks()
        }
        finish()
    }
}
