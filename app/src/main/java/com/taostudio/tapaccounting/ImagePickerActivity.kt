package com.taostudio.tapaccounting

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import java.io.File

/**
 * 透明中间人 Activity，专门用于从 Service/OverlayManager 中触发系统图片选择器。
 * Service 无法直接 startActivityForResult，通过此 Activity 做桥接。
 *
 * 使用方式：
 *   OverlayManager 通过广播或接口回调通知本 Activity，
 *   本 Activity 拿到图片 URI 后通过静态回调传回给 OverlayManager。
 */
class ImagePickerActivity : Activity() {

    companion object {
        private const val REQUEST_PICK_IMAGE = 1001

        /** P2-25: 宿主销毁时清空静态回调，避免悬挂 OverlayManager */
        fun clearCallbacks() {
            onImagesPicked = null
            onPickCancelled = null
        }
        var onImagesPicked: ((List<Uri>) -> Unit)? = null
        /** 单图兼容回调 */
        var onImagePicked: ((Uri) -> Unit)? = null
        var onPickCancelled: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明窗口，不显示任何界面
        openImagePicker()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE) {
            Logger.d(this, "StreamPreview", "onActivityResult: resultCode=$resultCode, data=${data != null}, clipData=${data?.clipData?.itemCount}")
            if (resultCode == RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                val clipData = data.clipData
                if (clipData != null) {
                    // 多选
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                } else if (data.data != null) {
                    // 单选
                    uris.add(data.data!!)
                }
                Logger.d(this, "StreamPreview", "onActivityResult: uris=${uris.size}, onImagesPicked=${onImagesPicked != null}, onImagePicked=${onImagePicked != null}")
                if (uris.isEmpty()) {
                    onPickCancelled?.invoke()
                } else {
                    val stableUris = uris.mapNotNull { uri ->
                        runCatching { copyImageToCache(uri) }.getOrNull()
                    }
                    if (stableUris.isEmpty()) {
                        Toast.makeText(this, getString(R.string.image_read_failed), Toast.LENGTH_SHORT).show()
                        onPickCancelled?.invoke()
                    } else if (launchCaptureAccounting(stableUris)) {
                        // 待记图片记账：选图完成后交给 capture 专用 Activity，不走 OverlayManager 回调
                        finish()
                        return
                    } else {
                        // 优先调多图回调，兼容单图回调
                        val multiCb = onImagesPicked
                        if (multiCb != null) {
                            multiCb(stableUris)
                        } else {
                            onImagePicked?.invoke(stableUris.first())
                        }
                    }
                }
            } else {
                if (!intent.getStringExtra(CaptureContract.EXTRA_CAPTURE_ID).isNullOrBlank()) {
                    Toast.makeText(this, "已取消图片记账，待记仍保留", Toast.LENGTH_SHORT).show()
                }
                onPickCancelled?.invoke()
            }
        }
        finish()
    }

    /**
     * 待记图片记账模式：把已复制到缓存的图片 Uri 和 capture 上下文交给
     * [CaptureImageAccountingActivity]。返回 false 表示不是该模式。
     */
    private fun launchCaptureAccounting(uris: List<Uri>): Boolean {
        val captureId = intent?.getStringExtra(CaptureContract.EXTRA_CAPTURE_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val next = Intent(this, CaptureImageAccountingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putStringArrayListExtra(
                CaptureImageAccountingActivity.EXTRA_IMAGE_URIS,
                ArrayList(uris.map { it.toString() })
            )
            putExtra(CaptureContract.EXTRA_CAPTURE_ID, captureId)
            intent.getStringExtra(CaptureContract.EXTRA_CAPTURE_PAYLOAD)?.let {
                putExtra(CaptureContract.EXTRA_CAPTURE_PAYLOAD, it)
            }
            intent.getStringExtra(CaptureContract.EXTRA_REQUEST_ID)?.let {
                putExtra(CaptureContract.EXTRA_REQUEST_ID, it)
            }
        }
        return try {
            startActivity(next)
            true
        } catch (t: Throwable) {
            Logger.e(this, "StreamPreview", "launchCaptureAccounting failed: ${t.message}")
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun copyImageToCache(sourceUri: Uri): Uri {
        val input = contentResolver.openInputStream(sourceUri)
            ?: throw IllegalArgumentException("无法打开图片流")
        val imageDir = File(cacheDir, "picked_images").apply { mkdirs() }
        val outFile = File(imageDir, "receipt_${System.currentTimeMillis()}.jpg")

        input.use { ins ->
            outFile.outputStream().use { outs ->
                ins.copyTo(outs)
            }
        }
        return Uri.fromFile(outFile)
    }
}
