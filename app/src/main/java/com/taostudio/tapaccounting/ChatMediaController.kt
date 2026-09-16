package com.taostudio.tapaccounting

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

class ChatMediaController(
    private val context: ChatActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val tvAiNameProvider: () -> TextView,
    private val ivAiAvatarProvider: () -> ImageView,
    private val ivChatBgProvider: () -> ImageView,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val ensureAiImageFeatureEnabled: () -> Boolean,
    private val showPageCenterDialog: (AlertDialog, Float) -> Unit,
    private val updateConversationSubtitle: () -> Unit,
    private val appendUserMessage: (String, Int, String) -> Unit,
    /** Called when a picked attachment is ready. */
    private val onAttachmentReady: (PendingImage) -> Unit,
    private val pendingAttachmentCount: () -> Int = { 0 },
    private val appendAiTextMessage: (String, Boolean) -> Unit,
    private val showPageBottomDialog: (AlertDialog) -> Unit,
    private val requestGalleryPermission: () -> Unit,
    private val requestCameraPermission: () -> Unit,
    private val reqPickImage: Int,
    private val reqTakePhoto: Int,
    private val reqPickFile: Int,
    private val reqPickBg: Int,
    private val reqCropBg: Int,
    private val reqPickAiAvatar: Int,
    private val reqPickUserAvatar: Int,
    private val reqCropAiAvatar: Int,
    private val reqCropUserAvatar: Int,
    private val msgTypeUserImage: Int
) {
    private var pendingEditAiAvatarView: ImageView? = null
    private var pendingCameraOutputUri: Uri? = null
    private var pendingOpenCameraAfterPermission = false
    private var pendingGalleryPickCount = 0
    private val maxAiImageBytes = ChatAttachmentHelper.MAX_IMAGE_BYTES
    private val maxOcrCharsForRouting = 1200

    fun showEditAiProfileDialog() {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_edit_ai_profile, null)
        val avatarContainer = view.findViewById<android.view.View>(R.id.layout_ai_profile_avatar)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_ai_profile_avatar)
        val etName = view.findViewById<android.widget.EditText>(R.id.et_ai_profile_name)
        val etIdentity = view.findViewById<android.widget.EditText>(R.id.et_ai_profile_identity)

        val currentName = Prefs.getAiChatName(context).ifBlank { "小计" }
        etName.setText(currentName)
        etName.setSelection(currentName.length)
        etIdentity.setText(Prefs.getAiChatIdentity(context))

        val avatarPath = Prefs.getAiChatAvatarPath(context)
        if (avatarPath.isNotBlank()) {
            Glide.with(context)
                .load(Uri.fromFile(File(avatarPath)))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .circleCrop()
                .placeholder(R.drawable.ic_ai_default_avatar)
                .into(ivAvatar)
        } else {
            ivAvatar.setImageResource(R.drawable.ic_ai_default_avatar)
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setTitle(context.getString(R.string.edit_ai_profile))
            .setView(view)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .setPositiveButton(context.getString(R.string.save)) { _, _ ->
                Prefs.setAiChatName(context, etName.text?.toString()?.trim().orEmpty().ifBlank { "小计" })
                Prefs.setAiChatIdentity(context, etIdentity.text?.toString()?.trim().orEmpty())
                refreshAiProfile()
            }
            .create()

        val pickAiAvatar = android.view.View.OnClickListener {
            pendingEditAiAvatarView = ivAvatar
            dialog.dismiss()
            openImagePicker(reqPickAiAvatar, "选择 AI 头像")
        }
        avatarContainer.setOnClickListener(pickAiAvatar)
        ivAvatar.setOnClickListener(pickAiAvatar)

        showPageCenterDialog(dialog, 0.88f)
    }

    fun showAttachmentMenu(alreadySelectedCount: Int) {
        if (!ensureAiImageFeatureEnabled()) return
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_chat_attach_menu, null)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<android.view.View>(R.id.item_attach_camera).setOnClickListener {
            dialog.dismiss()
            takePhoto()
        }
        view.findViewById<android.view.View>(R.id.item_attach_photos).setOnClickListener {
            dialog.dismiss()
            requestPickImages(alreadySelectedCount)
        }
        view.findViewById<android.view.View>(R.id.item_attach_file).setOnClickListener {
            dialog.dismiss()
            pickPdf()
        }
        view.findViewById<TextView>(R.id.btn_attach_cancel).setOnClickListener {
            dialog.dismiss()
        }
        showPageBottomDialog(dialog)
    }

    fun requestPickImages(alreadySelectedCount: Int) {
        if (!ensureAiImageFeatureEnabled()) return
        pendingGalleryPickCount = alreadySelectedCount
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchSystemImagePicker(alreadySelectedCount)
            return
        }
        val permission = android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            launchSystemImagePicker(alreadySelectedCount)
        } else {
            requestGalleryPermission()
        }
    }

    fun pickImagesFromSystem(alreadySelectedCount: Int) {
        launchSystemImagePicker(alreadySelectedCount)
    }

    fun takePhoto() {
        if (!ensureAiImageFeatureEnabled()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraIntent()
        } else {
            pendingOpenCameraAfterPermission = true
            requestCameraPermission()
        }
    }

    fun onCameraPermissionGranted() {
        if (pendingOpenCameraAfterPermission) {
            pendingOpenCameraAfterPermission = false
            launchCameraIntent()
        }
    }

    fun onCameraPermissionDenied() {
        pendingOpenCameraAfterPermission = false
        Utils.toast(context, context.getString(R.string.toast_camera_permission))
    }

    private fun pickPdf() {
        if (!ensureAiImageFeatureEnabled()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/pdf"
            addCategory(Intent.CATEGORY_OPENABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildRootUri(
                            "com.android.externalstorage.documents",
                            "primary"
                        )
                    )
                }
            }
        }.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/pdf"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        if (intent.resolveActivity(context.packageManager) == null) {
            Utils.toast(context, context.getString(R.string.chat_attach_no_file_app))
            return
        }
        startForResultNoAnim(intent, reqPickFile)
    }

    private fun launchSystemImagePicker(alreadySelectedCount: Int) {
        val remaining = (ChatImageComposer.MAX_PENDING_IMAGES - alreadySelectedCount).coerceAtLeast(1)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, remaining)
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, remaining > 1)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startForResultNoAnim(intent, reqPickImage)
        } else {
            startForResultNoAnim(
                Intent.createChooser(intent, context.getString(R.string.chat_attach_photos)),
                reqPickImage
            )
        }
    }

    private fun launchCameraIntent() {
        val imageDir = File(context.filesDir, "chat_images").also { it.mkdirs() }
        val photoFile = File(imageDir, "camera_${System.currentTimeMillis()}.jpg")
        val outputUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        pendingCameraOutputUri = outputUri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val cameraApp = intent.resolveActivity(context.packageManager) ?: run {
            pendingCameraOutputUri = null
            photoFile.delete()
            Utils.toast(context, context.getString(R.string.toast_no_camera_app))
            return
        }
        context.grantUriPermission(
            cameraApp.packageName,
            outputUri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        startForResultNoAnim(intent, reqTakePhoto)
    }

    private fun extractPickedUris(data: Intent?, maxCount: Int): List<Uri> {
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(i).uri)
                if (uris.size >= maxCount) break
            }
        } ?: data?.data?.let { uris.add(it) }
        return uris.take(maxCount)
    }

    private fun handlePickedFile(uri: Uri) {
        handlePickedAttachment(uri)
    }

    fun pickBgImage() {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_chat_bg_setting, null)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setView(view)
            .create()

        view.findViewById<TextView>(R.id.item_bg_pick_image).setOnClickListener {
            dialog.dismiss()
            openImagePicker(reqPickBg, "选择聊天背景")
        }
        view.findViewById<TextView>(R.id.item_bg_reset_default).setOnClickListener {
            Prefs.setAiChatBgPath(context, "")
            Glide.with(context).clear(ivChatBgProvider())
            ivChatBgProvider().visibility = android.view.View.INVISIBLE
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_bg_setting_cancel).setOnClickListener {
            dialog.dismiss()
        }
        showPageCenterDialog(dialog, 0.9f)
    }

    fun refreshAiProfile() {
        tvAiNameProvider().text = Prefs.getAiChatName(context).ifEmpty { "小计" }
        updateConversationSubtitle()
        val avatarPath = Prefs.getAiChatAvatarPath(context)
        if (avatarPath.isNotBlank()) {
            GlideLocalFiles.load(
                target = ivAiAvatarProvider(),
                file = File(avatarPath),
                placeholderRes = R.drawable.ic_ai_default_avatar,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            ivAiAvatarProvider().setImageResource(R.drawable.ic_ai_default_avatar)
        }
    }

    fun applyBackground() {
        val path = Prefs.getAiChatBgPath(context)
        if (path.isBlank()) {
            Glide.with(context).clear(ivChatBgProvider())
            ivChatBgProvider().visibility = android.view.View.INVISIBLE
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Prefs.setAiChatBgPath(context, "")
            Glide.with(context).clear(ivChatBgProvider())
            ivChatBgProvider().visibility = android.view.View.INVISIBLE
            return
        }
        ivChatBgProvider().visibility = android.view.View.VISIBLE
        Glide.with(context).clear(ivChatBgProvider())
        GlideLocalFiles.load(
            target = ivChatBgProvider(),
            file = file,
            diskCacheStrategy = DiskCacheStrategy.NONE,
            skipMemoryCache = true
        )
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == reqCropAiAvatar || requestCode == reqCropUserAvatar || requestCode == reqCropBg) {
                val error = data?.let { UCrop.getError(it) }
                if (error != null) {
                    val label = if (requestCode == reqCropBg) "背景裁剪失败" else "头像裁剪失败"
                    Utils.toast(context, context.getString(R.string.reselect_image_fmt, label))
                }
            }
            return requestCode in setOf(
                reqPickImage, reqTakePhoto, reqPickFile,
                reqPickBg, reqCropBg, reqPickAiAvatar, reqPickUserAvatar, reqCropAiAvatar, reqCropUserAvatar
            )
        }

        when (requestCode) {
            reqPickImage -> {
                val maxCount = (ChatImageComposer.MAX_PENDING_IMAGES - pendingGalleryPickCount).coerceAtLeast(1)
                extractPickedUris(data, maxCount).forEach(::handlePickedImage)
                return true
            }
            reqTakePhoto -> {
                val outputUri = pendingCameraOutputUri
                pendingCameraOutputUri = null
                if (outputUri?.path?.let { path ->
                        val file = File(path)
                        file.exists() && file.length() > 0L
                    } == true
                ) {
                    handlePickedImage(outputUri)
                } else {
                    data?.data?.let(::handlePickedImage)
                }
                return true
            }
            reqPickFile -> {
                data?.data?.let(::handlePickedFile)
                return true
            }
            reqPickBg -> {
                val uri = data?.data ?: return true
                showPickedImagePreview(
                    sourceUri = uri,
                    title = "背景预览",
                    hint = "确认后进入裁剪（按设备比例）"
                ) { confirmedUri ->
                    startBackgroundCrop(confirmedUri)
                }
                return true
            }
            reqCropBg -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return true
                saveAndApplyBackground(uri)
                return true
            }
            reqPickAiAvatar -> {
                val uri = data?.data ?: return true
                showPickedImagePreview(
                    sourceUri = uri,
                    title = "AI 头像预览",
                    hint = "确认后进入 1:1 裁剪"
                ) { confirmedUri ->
                    startAvatarCrop(confirmedUri, isAiAvatar = true)
                }
                return true
            }
            reqPickUserAvatar -> {
                val uri = data?.data ?: return true
                showPickedImagePreview(
                    sourceUri = uri,
                    title = "用户头像预览",
                    hint = "确认后进入 1:1 裁剪"
                ) { confirmedUri ->
                    startAvatarCrop(confirmedUri, isAiAvatar = false)
                }
                return true
            }
            reqCropAiAvatar -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return true
                saveAiAvatar(uri)
                return true
            }
            reqCropUserAvatar -> {
                val uri = data?.let { UCrop.getOutput(it) } ?: return true
                saveUserAvatar(uri)
                return true
            }
        }
        return false
    }

    private fun startAvatarCrop(sourceUri: Uri, isAiAvatar: Boolean) {
        val destFile = File(
            context.cacheDir,
            "avatar_crop/${if (isAiAvatar) "ai" else "user"}_${System.currentTimeMillis()}.jpg"
        ).also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(92)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setToolbarTitle(if (isAiAvatar) "裁剪 AI 头像" else "裁剪用户头像")
            setToolbarColor(Color.parseColor("#1A73E8"))
            setStatusBarColor(Color.parseColor("#1A73E8"))
            setToolbarWidgetColor(Color.WHITE)
            setDimmedLayerColor(Color.parseColor("#AA000000").toInt())
        }
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(1080, 1080)
            .withOptions(options)
            .getIntent(context)
        startForResultNoAnim(intent, if (isAiAvatar) reqCropAiAvatar else reqCropUserAvatar)
    }

    private fun startBackgroundCrop(sourceUri: Uri) {
        val destFile = File(
            context.cacheDir,
            "bg_crop/bg_${System.currentTimeMillis()}.jpg"
        ).also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(92)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setToolbarTitle("裁剪聊天背景")
            setToolbarColor(Color.parseColor("#1A73E8"))
            setStatusBarColor(Color.parseColor("#1A73E8"))
            setToolbarWidgetColor(Color.WHITE)
            setDimmedLayerColor(Color.parseColor("#AA000000").toInt())
        }
        val dm = context.resources.displayMetrics
        val targetW = dm.widthPixels.coerceAtLeast(1).toFloat()
        val targetH = dm.heightPixels.coerceAtLeast(1).toFloat()
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(targetW, targetH)
            .withMaxResultSize(2160, 2160)
            .withOptions(options)
            .getIntent(context)
        startForResultNoAnim(intent, reqCropBg)
    }

    private fun startForResultNoAnim(intent: Intent, requestCode: Int) {
        val options = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        context.startActivityForResult(intent, requestCode, options)
    }

    private fun openImagePicker(requestCode: Int, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        context.startActivityForResult(Intent.createChooser(intent, chooserTitle), requestCode)
    }

    private fun showPickedImagePreview(
        sourceUri: Uri,
        title: String,
        hint: String,
        onConfirm: (Uri) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_chat_image_preview, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_pick_preview_title)
        val ivPreview = view.findViewById<ImageView>(R.id.iv_pick_preview_image)
        val tvHint = view.findViewById<TextView>(R.id.tv_pick_preview_hint)
        val btnCancel = view.findViewById<TextView>(R.id.btn_pick_preview_cancel)
        val btnOk = view.findViewById<TextView>(R.id.btn_pick_preview_ok)

        tvTitle.text = title
        tvHint.text = hint
        Glide.with(context)
            .load(sourceUri)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(ivPreview)

        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setView(view)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOk.setOnClickListener {
            dialog.dismiss()
            onConfirm(sourceUri)
        }
        showPageCenterDialog(dialog, 0.9f)
    }

    private fun saveAndApplyBackground(uri: Uri) {
        runCatching {
            val bgDir = File(context.filesDir, "chat_bg").also { it.mkdirs() }
            val oldPath = Prefs.getAiChatBgPath(context)
            val destFile = File(bgDir, "chat_bg_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setAiChatBgPath(context, destFile.absolutePath)
            if (oldPath.isNotBlank() && oldPath != destFile.absolutePath) {
                val oldFile = File(oldPath)
                if (oldFile.exists() && oldFile.parentFile?.absolutePath == bgDir.absolutePath) {
                    oldFile.delete()
                }
            }
            applyBackground()
            Utils.toast(context, context.getString(R.string.toast_bg_updated))
        }.onFailure {
            Utils.toast(context, context.getString(R.string.toast_bg_update_failed))
        }
    }

    private fun saveAiAvatar(uri: Uri) {
        runCatching {
            val destFile = File(context.filesDir, "chat_ai_avatar.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setAiChatAvatarPath(context, destFile.absolutePath)
            pendingEditAiAvatarView?.let { iv ->
                GlideLocalFiles.load(
                    target = iv,
                    file = destFile,
                    placeholderRes = R.drawable.ic_ai_default_avatar,
                    circleCrop = true,
                    overrideSize = 128
                )
            }
            refreshAiProfile()
            Utils.toast(context, context.getString(R.string.toast_ai_avatar_updated))
        }.onFailure {
            Utils.toast(context, context.getString(R.string.toast_ai_avatar_failed))
        }
    }

    private fun saveUserAvatar(uri: Uri) {
        runCatching {
            val destFile = File(context.filesDir, "chat_user_avatar.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setUserChatAvatarPath(context, destFile.absolutePath)
            adapterProvider().notifyDataSetChanged()
            Utils.toast(context, context.getString(R.string.toast_user_avatar_updated))
        }.onFailure {
            Utils.toast(context, context.getString(R.string.toast_avatar_failed))
        }
    }

    /**
     * Copies the attachment to app storage, encodes for AI when needed, then hands
     * it to [onAttachmentReady].
     */
    private fun handlePickedImage(uri: Uri) {
        handlePickedAttachment(uri)
    }

    private fun handlePickedAttachment(uri: Uri) {
        lifecycleScope.launch {
            try {
                val attachments = withContext(Dispatchers.IO) {
                    buildPendingAttachments(uri)
                }
                attachments.forEach { attachment ->
                    onAttachmentReady(attachment)
                }
            } catch (e: UnsupportedAttachmentException) {
                Utils.toast(context, e.message.orEmpty())
            } catch (e: Exception) {
                appendAiTextMessage(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.chat_attach_process_failed),
                    false
                )
            }
        }
    }

    private fun buildPendingAttachments(uri: Uri): List<PendingImage> {
        val fileName = ChatAttachmentHelper.resolveDisplayName(context, uri)
        val mime = ChatAttachmentHelper.resolveMime(context, uri, fileName)

        if (ChatAttachmentHelper.isPdfMime(mime, fileName)) {
            if (!ChatAttachmentHelper.isSupportedFilePickerMime(mime, fileName)) {
                throw UnsupportedAttachmentException(
                    context.getString(R.string.chat_attach_file_unsupported)
                )
            }
            return listOf(buildPdfPendingAttachment(uri, fileName))
        }

        if (!ChatAttachmentHelper.isImageMime(mime)) {
            throw UnsupportedAttachmentException(
                context.getString(R.string.chat_attach_file_unsupported)
            )
        }

        val storedUri = copyPickedAttachmentToStorage(uri, mime, fileName)
        val stableFile = File(storedUri.path ?: "")
        compressImageForAi(stableFile)
        if (stableFile.length() > ChatAttachmentHelper.MAX_IMAGE_BYTES) {
            throw IOException(context.getString(R.string.chat_attach_image_too_large))
        }
        val bytes = context.contentResolver.openInputStream(storedUri)?.readBytes()
            ?: throw IOException(context.getString(R.string.chat_attach_read_failed))
        if (bytes.isEmpty()) {
            throw IOException(context.getString(R.string.chat_attach_empty_file))
        }
        return listOf(
            PendingImage(
                uri = storedUri,
                base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                mime = mime,
                fileName = fileName
            )
        )
    }

    private fun buildPdfPendingAttachment(uri: Uri, fileName: String): PendingImage {
        val mime = "application/pdf"
        val storedUri = copyPickedAttachmentToStorage(uri, mime, fileName)
        val stableFile = File(storedUri.path ?: "")
        if (stableFile.length() > ChatAttachmentHelper.MAX_PDF_BYTES) {
            throw IOException(context.getString(R.string.chat_attach_pdf_too_large))
        }
        val pageBytes = ChatPdfRenderer.renderPagesToJpeg(stableFile, ChatAttachmentHelper.MAX_PDF_PAGES)
        if (pageBytes.isEmpty()) {
            throw IOException(context.getString(R.string.chat_attach_pdf_render_failed))
        }
        pageBytes.forEach { bytes ->
            if (bytes.size > maxAiImageBytes) {
                throw IOException(context.getString(R.string.chat_attach_image_too_large))
            }
        }
        val pages = pageBytes.map { bytes ->
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to "image/jpeg"
        }
        return PendingImage(
            uri = storedUri,
            base64 = "",
            mime = mime,
            fileName = fileName,
            pdfPagePayloads = pages
        )
    }

    private class UnsupportedAttachmentException(message: String) : IOException(message)

    private fun compressImageInPlace(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
        try {
            FileOutputStream(file, false).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 为 AI 发送压缩图片（类似微信发图策略）：长边 ≤1280px、JPEG 75。
     * 参数与「图片记账」入口共用，见 [AiImageCompressor]。
     */
    private fun compressImageForAi(file: File) {
        AiImageCompressor.compressInPlace(file)
    }

    private fun copyPickedAttachmentToStorage(sourceUri: Uri, sourceMime: String, fileName: String): Uri {
        val ext = fileName.substringAfterLast('.', "")
            .lowercase(Locale.getDefault())
            .ifBlank {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(sourceMime)?.lowercase(Locale.getDefault())
            }
            ?.ifBlank { null }
            ?: when {
                sourceMime.contains("png", ignoreCase = true) -> "png"
                sourceMime.contains("pdf", ignoreCase = true) -> "pdf"
                sourceMime.contains("json", ignoreCase = true) -> "json"
                sourceMime.startsWith("text/", ignoreCase = true) -> "txt"
                else -> "bin"
            }
        val dir = File(context.filesDir, "chat_attachments").also { it.mkdirs() }
        val safeStem = fileName.substringBeforeLast('.')
            .replace(Regex("""[^\w\u4e00-\u9fff.-]"""), "_")
            .take(40)
            .ifBlank { "file" }
        val outFile = File(dir, "${safeStem}_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(sourceUri)?.use { ins ->
            FileOutputStream(outFile).use { outs -> ins.copyTo(outs) }
        } ?: throw IOException(context.getString(R.string.chat_attach_read_failed))
        return Uri.fromFile(outFile)
    }
}
