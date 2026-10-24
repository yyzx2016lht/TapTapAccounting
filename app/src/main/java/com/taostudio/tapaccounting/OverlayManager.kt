package com.taostudio.tapaccounting

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.AccountingFormController
import com.taostudio.tapaccounting.logic.VoiceInputHandler
import com.taostudio.tapaccounting.logic.BatchBillInserter
import com.taostudio.tapaccounting.ui.dialog.MergePaymentDialog
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayManager(private val ctx: Context) {
    companion object {
        private const val OVERLAY_FINAL_Y_DP = 48f
        private const val OVERLAY_ENTER_DURATION_MS = 285L
        private const val OVERLAY_EXIT_DURATION_MS = 190L
        private const val OVERLAY_VISIBILITY_DURATION_MS = 170L
        private const val OVERLAY_ENTER_TRANSLATION_DP = 46f
        private const val OVERLAY_EXIT_TRANSLATION_DP = 30f
        private const val OVERLAY_RESTORE_TRANSLATION_DP = 14f
        private const val OVERLAY_ENTER_SCALE = 0.955f
        private const val OVERLAY_EXIT_SCALE = 0.965f
        private const val OVERLAY_HIDDEN_SCALE = 0.985f
        private const val CAPTURE_CARD_ENTER_DURATION_MS = 260L
        private const val CAPTURE_CARD_EXIT_DURATION_MS = 150L
        /** 截屏前等待合成器摘掉悬浮层，避免把加载卡/悬浮窗拍进画面。 */
        private const val CAPTURE_SETTLE_MS = 64L
        /** 截完反馈：一道扫光从上到下，节奏从容。 */
        private const val CAPTURE_SCAN_DURATION_MS = 900L
        private const val OVERLAY_OUTSIDE_TOUCH_GRACE_MS = 600L
    }

    private val overlayEnterInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val overlayExitInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    private val overlaySoftInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isRemovingOverlay = false
    private var overlayShownAtUptimeMs = 0L
    private var currentAnimator: Animator? = null
    private var currentViewAnimator: ViewPropertyAnimator? = null

    private fun navigationBarHeight(): Int {
        val res = ctx.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    private fun overlayFinalY(): Int {
        return (OVERLAY_FINAL_Y_DP * ctx.resources.displayMetrics.density).toInt() + navigationBarHeight()
    }

    private var captureLoadingView: View? = null
    private var captureLoadingParams: WindowManager.LayoutParams? = null
    private var captureScanAnimator: ValueAnimator? = null
    private var captureCardEnterAnimator: AnimatorSet? = null
    private var isScreenCaptureInProgress = false
    private var screenCaptureTriggerBtn: View? = null
    private var screenCaptureJob: Job? = null
    /** 本次截屏临时文件；未完成入账则删除，避免 cache 堆积。 */
    private var pendingScreenCaptureFile: java.io.File? = null

    private val pendingBills = mutableListOf<JSONObject>()

    private val aiAssistant = AiAssistant(ctx)
    private var formController: AccountingFormController? = null
    private var voiceHandler: VoiceInputHandler? = null

    fun isShowing(): Boolean = overlayView != null

    fun handleExternalAiResult(result: JSONObject, showSaveOnly: Boolean = false) {
        handleAiResult(result)
    }

    fun showAiInputPanel(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            Utils.toast(ctx, ctx.getString(R.string.toast_ai_overlay_permission))
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
            return false
        }
        val tapVoiceHandler = VoiceInputHandler(
            ctx, aiAssistant, handleAiResult,
            onBeforeRecording = { (ctx as? OverlayService)?.enterMicrophoneMode() ?: true },
            onAfterRecording = { (ctx as? OverlayService)?.exitMicrophoneMode() }
        )
        aiAssistant.voiceInputBtnSetup = { btn ->
            tapVoiceHandler.setupVoiceButton(btn)
        }
        aiAssistant.showInputPanel(
            hideStreamText = true,
            onResult = handleAiResult
        )
        return true
    }

    fun startScreenCaptureFromTap() {
        startScreenCaptureRecognition()
    }

    private val handleAiResult: (JSONObject) -> Unit = { resultJson ->
        val sourceKind = resultJson.optString("source_kind", "")
        val isVisualReviewDraft = sourceKind == "screen_capture" || sourceKind == "receipt_image"
        val requiresReview = resultJson.optBoolean("requires_review", false) || isVisualReviewDraft
        if (resultJson.has("bills")) {
            val billsArray = resultJson.getJSONArray("bills")
            // 用户在识别完成页明确选了入账方式时以选择为准，不再走「多笔不弹窗」的静默保存
            val explicitBatch = resultJson.optBoolean(AiAssistant.EXTRA_BATCH_SAVE_ALL, false)
            val explicitQueue = resultJson.optBoolean(AiAssistant.EXTRA_FORCE_BILL_QUEUE, false)
            if (explicitBatch) {
                // 「一键全部入账」：不弹记账表单，直接整批落库
                saveBillsDirectly(resultJson)
            } else if (!explicitQueue && Prefs.isMultiBillNotSync(ctx) && !requiresReview) {
                val aiBookName = resultJson.optString("book_name", "").trim()
                for (i in 0 until billsArray.length()) {
                    saveJsonToLocal(billsArray.getJSONObject(i), aiBookName)
                }
                Utils.toast(ctx, ctx.getString(R.string.toast_recognized_saved, billsArray.length()))
                if (overlayView != null) removeOverlay()
            } else {
                if (overlayView != null && formController != null) {
                    formController?.fillDataToUi(resultJson, showToast = true, forceMultiMode = true)
                } else {
                    showOverlay(resultJson, showSaveOnly = true)
                }
            }
        } else {
            if (overlayView != null && formController != null) {
                formController?.fillDataToUi(resultJson, showToast = true)
                formController?.setCurrency(resultJson.optString("currency", "CNY"))
            } else {
                showOverlay(resultJson, showSaveOnly = false)
            }
        }
    }

    /**
     * 「一键全部入账」：整批账单直接落库，全程不展示记账表单。
     *
     * 之前这里会先 showOverlay 建出悬浮窗再让表单去保存，用户会看到表单闪一下；
     * 现在先收起可能开着的悬浮窗，再由 [BatchBillInserter] 独立完成写入。
     */
    private fun saveBillsDirectly(resultJson: JSONObject) {
        // 用户是在悬浮窗里发起的识别：直接收起，别把表单留在屏幕上
        if (overlayView != null) removeOverlay()

        val billsArray = resultJson.optJSONArray("bills")
        if (billsArray == null || billsArray.length() == 0) return

        val availableBooks = BookAccountManager.getBookAccounts(ctx)
        val fallbackBookName = BookAccountManager.resolveWritableBook(
            ctx,
            BookAccountManager.getSelectedBook(ctx, availableBooks)
        )
        val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)

        CoroutineScope(Dispatchers.IO).launch {
            val savedBills = BatchBillInserter.insertBills(
                ctx = ctx,
                json = resultJson,
                billsArray = billsArray,
                fallbackBookName = fallbackBookName,
                isAssetFeatureEnabled = assetFeatureEnabled
            )
            withContext(Dispatchers.Main) {
                Utils.toast(ctx, ctx.getString(R.string.toast_batch_saved_bills, savedBills.size))
                // 只有图片/小票这类「一次付款」的来源才追问合并，文本来源不打扰
                if (BatchBillInserter.canMergeAsOnePayment(resultJson, savedBills)) {
                    MergePaymentDialog.show(ctx, savedBills) { /* 一键入账本就无界面，收尾即可 */ }
                }
            }
        }
    }

    fun showOverlay(prefill: JSONObject? = null, showSaveOnly: Boolean = false): Boolean {
        if (overlayView != null) return true
        if (isRemovingOverlay) {
            Utils.toast(ctx, ctx.getString(R.string.toast_overlay_closing))
            return false
        }
        Logger.i(ctx, "OverlayManager", "Showing Overlay")

        val themeContext = android.view.ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            Utils.toast(ctx, ctx.getString(R.string.toast_overlay_permission_required))
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
            return false
        }

        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            Logger.e(ctx, "OverlayManager", "showOverlay failed: WindowManager unavailable")
            Utils.toast(ctx, ctx.getString(R.string.toast_overlay_wm_unavailable))
            return false
        }
        overlayView = LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_window, null)
        overlayView?.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = ctx.resources.displayMetrics.density * 16
        }

        overlayParams = WindowManager.LayoutParams().apply {
            width = (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = overlayFinalY()
            windowAnimations = 0
        }

        setupLogic(overlayView!!, prefill, showSaveOnly)

        val view = overlayView!!
        val enterOffsetPx = OVERLAY_ENTER_TRANSLATION_DP * ctx.resources.displayMetrics.density

        // addView 前设置初始态，避免首帧闪烁
        overlayParams!!.y = overlayFinalY()
        view.translationY = enterOffsetPx
        view.alpha = 0f
        view.scaleX = OVERLAY_ENTER_SCALE
        view.scaleY = OVERLAY_ENTER_SCALE

        try {
            windowManager?.addView(view, overlayParams)
        } catch (e: Exception) {
            Logger.e(ctx, "OverlayManager", "showOverlay addView failed: ${e.message}")
            overlayView = null
            overlayParams = null
            Utils.toast(ctx, ctx.getString(R.string.toast_overlay_show_failed))
            return false
        }

        startOverlayEnterAnimation(view)
        overlayShownAtUptimeMs = android.os.SystemClock.uptimeMillis()

        overlayView?.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                val elapsed = android.os.SystemClock.uptimeMillis() - overlayShownAtUptimeMs
                if (elapsed < OVERLAY_OUTSIDE_TOUCH_GRACE_MS) {
                    return@setOnTouchListener true
                }
                removeOverlay(isSaved = false)
                true
            } else {
                false
            }
        }
        return true
    }

    private fun setupLogic(view: View, prefill: JSONObject?, showSaveOnly: Boolean) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (formController?.handleBackPressed() == true) {
                    true
                } else {
                    removeOverlay(isSaved = false)
                    true
                }
            } else {
                false
            }
        }
        view.requestFocus()

        formController = AccountingFormController(
            ctx = ctx,
            rootView = view,
            onCloseRequest = { isSaved -> removeOverlay(isSaved) }
        )

        if (prefill != null) {
            val forceMulti = prefill.has("bills")
            formController?.fillDataToUi(prefill, showToast = false, forceMultiMode = forceMulti)
        }

        voiceHandler = VoiceInputHandler(
            ctx, aiAssistant, handleAiResult,
            onBeforeRecording = { (ctx as? OverlayService)?.enterMicrophoneMode() ?: true },
            onAfterRecording = { (ctx as? OverlayService)?.exitMicrophoneMode() }
        )
        aiAssistant.voiceInputBtnSetup = { btn ->
            voiceHandler?.setupVoiceButton(btn)
        }
        voiceHandler?.setupVoiceButton(formController!!.btnVoice)

        val btnAiScreenshot = view.findViewById<android.widget.ImageView>(R.id.btn_ai_screenshot)
        val btnAiImage = view.findViewById<android.widget.ImageView>(R.id.btn_ai_image)
        val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val hasShizuku = Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)
        val canUseScreenAccounting = Prefs.isShowScreenAccounting(ctx) && (hasAccessibility || hasShizuku)
        btnAiScreenshot?.visibility = if (canUseScreenAccounting) View.VISIBLE else View.GONE
        btnAiScreenshot?.setOnClickListener { btn ->
            // 按钮弹跳反馈
            btn.animate().scaleX(0.72f).scaleY(0.72f).setDuration(90L).withEndAction {
                btn.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }.start()
            startScreenCaptureRecognition(btn)
        }

        if (Prefs.isShowAiImage(ctx)) {
            btnAiImage?.visibility = View.VISIBLE
            btnAiImage?.setOnClickListener {
                overlayView?.visibility = View.INVISIBLE
                ImagePickerActivity.onImagesPicked = { uris ->
                    ImagePickerActivity.onImagesPicked = null
                    ImagePickerActivity.onPickCancelled = null
                    overlayView?.visibility = View.VISIBLE
                    Logger.d(ctx, "StreamPreview", "onImagesPicked: count=${uris.size}")
                    aiAssistant.analyzeImages(uris, handleAiResult)
                }
                ImagePickerActivity.onPickCancelled = {
                    ImagePickerActivity.onImagesPicked = null
                    ImagePickerActivity.onPickCancelled = null
                    overlayView?.visibility = View.VISIBLE
                }
                val intent = android.content.Intent(ctx, ImagePickerActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                ctx.startActivity(intent)
            }
        } else {
            btnAiImage?.visibility = View.GONE
        }

        formController!!.layoutAiTextEntry.setOnClickListener {
            aiAssistant.showInputPanel(
                hideStreamText = true,
                onResult = handleAiResult
            )
        }
    }

    private fun startScreenCaptureRecognition(triggerBtn: View? = null) {
        if (isScreenCaptureInProgress) {
            Utils.toast(ctx, ctx.getString(R.string.toast_screen_recognizing))
            return
        }

        isScreenCaptureInProgress = true
        screenCaptureTriggerBtn = triggerBtn
        triggerBtn?.isEnabled = false
        triggerBtn?.animate()?.alpha(0.45f)?.setDuration(120L)?.start()

        val screenModel = AiModelSlots.resolveVisionModel(ctx).trim()
        Logger.d(ctx, "OverlayManager", "Screen capture recognition clicked. model=$screenModel, isMulti=true")
        if (screenModel.isEmpty()) {
            Logger.e(ctx, "OverlayManager", "Screen capture aborted: no vision model configured")
            Utils.toast(ctx, ctx.getString(R.string.toast_select_vision_model))
            finishScreenCaptureFlow(restoreOverlay = true)
            return
        }

        val isProbeCachedSuccess = Prefs.isScreenModelVisionSupported(ctx, screenModel)
        if (isProbeCachedSuccess) {
            Logger.d(ctx, "OverlayManager", "Using cached vision probe support. model=$screenModel")
        }

        // 探测未缓存时才显示准备层；真正 screencap 前会立刻摘掉，避免拍进弹层。
        if (!isProbeCachedSuccess) {
            showScreenCaptureLoadingOverlay(ctx.getString(R.string.preparing_screen_capture))
            updateScreenCaptureLoadingHint(ctx.getString(R.string.checking_model_capability))
        }

        setOverlayVisible(false)

        screenCaptureJob?.cancel()
        screenCaptureJob = CoroutineScope(Dispatchers.IO).launch {
            val modelToProbe = ensureVisionModelAvailable(screenModel)
            if (modelToProbe == null) {
                withContext(Dispatchers.Main) {
                    Logger.e(ctx, "OverlayManager", "Screen capture aborted: model=$screenModel missing from current cache")
                    Prefs.setScreenModelVisionSupported(ctx, screenModel, false)
                    hideScreenCaptureLoadingOverlay()
                    Utils.toast(ctx, ctx.getString(R.string.toast_vision_model_invalid))
                    finishScreenCaptureFlow(restoreOverlay = true)
                }
                return@launch
            }
            // 刷新列表后解析结果可能换了型号，旧的探测结果不再沿用。
            val probeCachedOk = modelToProbe == screenModel && isProbeCachedSuccess
            val support = if (probeCachedOk) {
                true
            } else {
                runCatching { AIService.probeVisionInputSupport(ctx, modelToProbe) }
                    .onSuccess { ok ->
                        Prefs.setScreenModelVisionSupported(ctx, modelToProbe, ok)
                    }
                    .getOrElse {
                        Prefs.setScreenModelVisionSupported(ctx, modelToProbe, false)
                        false
                    }
            }
            withContext(Dispatchers.Main) {
                Logger.d(ctx, "OverlayManager", "Screen capture vision support probe result: model=$modelToProbe, support=$support")
                if (!support) {
                    hideScreenCaptureLoadingOverlay()
                    setOverlayVisible(true)
                    Utils.toast(ctx, ctx.getString(R.string.toast_model_no_vision))
                    finishScreenCaptureFlow(restoreOverlay = false)
                    return@withContext
                }
                if (Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)) {
                    Logger.d(ctx, "OverlayManager", "Using in-place Shizuku screencap flow")
                    startShizukuScreenCaptureRecognition()
                    return@withContext
                }
                if (KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Logger.d(ctx, "OverlayManager", "Using accessibility screencap flow")
                    startAccessibilityScreenCaptureRecognition()
                    return@withContext
                }
                // 系统截屏授权仍需要可见提示；Shizuku/无障碍路径绝不在截屏瞬间盖层。
                if (captureLoadingView == null) {
                    showScreenCaptureLoadingOverlay(ctx.getString(R.string.requesting_capture_permission))
                    updateScreenCaptureLoadingHint(ctx.getString(R.string.confirm_in_system_dialog))
                }
                launchSystemScreenCaptureActivity()
            }
        }
    }

    /**
     * 截图前确认解析出的视觉模型仍在实时列表缓存中。
     * 厂商换代导致缓存过期时先刷新一次模型列表再解析；仍不可用则返回 null（沿用原有失效拦截）。
     */
    private suspend fun ensureVisionModelAvailable(current: String): String? {
        val cache = Prefs.getAiModelsCache(ctx).map { it.trim() }.filter { it.isNotEmpty() }
        if (cache.isEmpty() || current in cache) return current
        runCatching {
            val preset = AiProviderRegistry.resolvePreset(ctx)
            val key = Prefs.getAiProviderKey(ctx, preset.id)
            if (key.isNotBlank()) {
                val models = AIService.fetchModelsForProvider(ctx, preset, key)
                    .map { it.trim() }.filter { it.isNotEmpty() }
                if (models.isNotEmpty()) Prefs.setAiModelsCache(ctx, models)
            }
        }
        val refreshed = AiModelSlots.resolveVisionModel(ctx).trim()
        val refreshedCache = Prefs.getAiModelsCache(ctx).map { it.trim() }.filter { it.isNotEmpty() }
        return refreshed.takeIf { it.isNotEmpty() && (refreshedCache.isEmpty() || it in refreshedCache) }
    }

    private fun launchSystemScreenCaptureActivity() {
        ScreenCaptureActivity.onScreenshotCaptured = { uri ->
            removeScreenCaptureLoadingOverlayImmediate()
            finishScreenCaptureFlow(restoreOverlay = false)
            beginScreenCaptureAccounting(uri)
        }
        ScreenCaptureActivity.onRecognitionError = { message -> handleScreenCaptureError(message) }
        ScreenCaptureActivity.onRecognitionCancelled = { handleScreenCaptureCancelled() }
        // Activity 自己会画授权/等待 UI；先摘掉悬浮加载层，避免 Shizuku 静默截屏时叠拍两层。
        removeScreenCaptureLoadingOverlayImmediate()
        val intent = android.content.Intent(ctx, ScreenCaptureActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Logger.i(ctx, "OverlayManager", "Launching ScreenCaptureActivity")
        runCatching { ctx.startActivity(intent) }
            .onFailure {
                Logger.e(ctx, "OverlayManager", "Launching ScreenCaptureActivity failed: ${it.message}")
                hideScreenCaptureLoadingOverlay()
                setOverlayVisible(true)
                Utils.toast(ctx, ctx.getString(R.string.toast_capture_start_failed))
                finishScreenCaptureFlow(restoreOverlay = false)
            }
    }

    private fun startShizukuScreenCaptureRecognition() {
        screenCaptureJob?.cancel()
        screenCaptureJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // 截屏是瞬间事：先清干净画面，再 screencap；识别 loading 交给 AiAssistant。
                awaitCleanFrameForCapture()
                val bytes = ShizukuShell.execBytes("screencap -p")
                if (bytes == null || bytes.isEmpty()) {
                    Logger.e(ctx, "OverlayManager", "Shizuku screencap returned empty bytes")
                    withContext(Dispatchers.Main) {
                        Logger.d(ctx, "OverlayManager", "Falling back to MediaProjection capture")
                        if (captureLoadingView == null) {
                            showScreenCaptureLoadingOverlay(ctx.getString(R.string.requesting_capture_permission))
                            updateScreenCaptureLoadingHint(ctx.getString(R.string.confirm_in_system_dialog))
                        }
                        launchSystemScreenCaptureActivity()
                    }
                    return@launch
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Logger.d(ctx, "OverlayManager", "Shizuku screencap decode returned null")
                    withContext(Dispatchers.Main) {
                        Logger.d(ctx, "OverlayManager", "Falling back to MediaProjection capture after decode failure")
                        if (captureLoadingView == null) {
                            showScreenCaptureLoadingOverlay(ctx.getString(R.string.requesting_capture_permission))
                            updateScreenCaptureLoadingHint(ctx.getString(R.string.confirm_in_system_dialog))
                        }
                        launchSystemScreenCaptureActivity()
                    }
                    return@launch
                }

                Logger.d(ctx, "OverlayManager", "Shizuku screencap succeeded. bytes=${bytes.size}, size=${bitmap.width}x${bitmap.height}")
                val uri = bitmapToTempUri(bitmap)
                withContext(Dispatchers.Main) {
                    finishScreenCaptureFlow(restoreOverlay = false)
                    if (uri != null) {
                        beginScreenCaptureAccounting(uri)
                    } else {
                        Utils.toast(ctx, ctx.getString(R.string.toast_capture_failed))
                    }
                }
            } catch (e: Exception) {
                Logger.e(ctx, "OverlayManager", "Shizuku in-place screen recognition failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Logger.d(ctx, "OverlayManager", "Falling back to MediaProjection capture after exception")
                    if (captureLoadingView == null) {
                        showScreenCaptureLoadingOverlay(ctx.getString(R.string.requesting_capture_permission))
                        updateScreenCaptureLoadingHint(ctx.getString(R.string.confirm_in_system_dialog))
                    }
                    launchSystemScreenCaptureActivity()
                }
            }
        }
    }

    private fun startAccessibilityScreenCaptureRecognition() {
        screenCaptureJob?.cancel()
        screenCaptureJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                awaitCleanFrameForCapture()
                KeepAliveAccessibilityService.takeScreenshotCompat { bitmap ->
                    if (bitmap == null) {
                        Logger.d(ctx, "OverlayManager", "Accessibility screencap returned null")
                        CoroutineScope(Dispatchers.Main).launch {
                            Logger.d(ctx, "OverlayManager", "Falling back to Shizuku or MediaProjection capture")
                            if (Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)) {
                                startShizukuScreenCaptureRecognition()
                            } else {
                                if (captureLoadingView == null) {
                                    showScreenCaptureLoadingOverlay(ctx.getString(R.string.requesting_capture_permission))
                                    updateScreenCaptureLoadingHint(ctx.getString(R.string.confirm_in_system_dialog))
                                }
                                launchSystemScreenCaptureActivity()
                            }
                        }
                        return@takeScreenshotCompat
                    }

                    Logger.d(ctx, "OverlayManager", "Accessibility screencap succeeded. size=${bitmap.width}x${bitmap.height}")
                    val uri = bitmapToTempUri(bitmap)
                    CoroutineScope(Dispatchers.Main).launch {
                        finishScreenCaptureFlow(restoreOverlay = false)
                        if (uri != null) {
                            beginScreenCaptureAccounting(uri)
                        } else {
                            Utils.toast(ctx, ctx.getString(R.string.toast_capture_failed))
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(ctx, "OverlayManager", "Accessibility screencap failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Logger.d(ctx, "OverlayManager", "Falling back to Shizuku or MediaProjection capture after exception")
                    if (Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)) {
                        startShizukuScreenCaptureRecognition()
                    } else {
                        if (captureLoadingView == null) {
                            showScreenCaptureLoadingOverlay(ctx.getString(R.string.requesting_capture_permission))
                            updateScreenCaptureLoadingHint(ctx.getString(R.string.confirm_in_system_dialog))
                        }
                        launchSystemScreenCaptureActivity()
                    }
                }
            }
        }
    }

    /** 截屏前瞬时清层：不等退出动画，再短等合成器落地。 */
    private suspend fun awaitCleanFrameForCapture() {
        withContext(Dispatchers.Main) {
            removeScreenCaptureLoadingOverlayImmediate()
            hideOverlayViewsForCaptureImmediate()
        }
        delay(CAPTURE_SETTLE_MS)
    }

    private fun hideOverlayViewsForCaptureImmediate() {
        val view = overlayView ?: return
        cancelOverlayAnimations(view)
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.INVISIBLE
    }

    private fun removeScreenCaptureLoadingOverlayImmediate() {
        stopCaptureLoadingAnimation()
        val view = captureLoadingView ?: return
        val wm = windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        captureLoadingView = null
        captureLoadingParams = null
        view.animate().cancel()
        view.findViewById<View>(R.id.layout_capture_card)?.animate()?.cancel()
        runCatching { wm?.removeView(view) }
    }

    /** 截屏成功 → toast → 扫光反馈 → 识别；未进入记账则删临时截屏。 */
    private fun beginScreenCaptureAccounting(uri: android.net.Uri) {
        pendingScreenCaptureFile = screenCaptureFileFromUri(uri)
        Utils.toast(ctx, ctx.getString(R.string.toast_screenshot_success))
        playCaptureDoneHint {
            aiAssistant.analyzeScreenshot(
                imageUri = uri,
                onResult = { json ->
                    // 已进入记账：保留截屏文件（可在存储清理里看到）；仅清引用
                    pendingScreenCaptureFile = null
                    handleAiResult(json)
                },
                onAbandoned = {
                    // 未进入记账（取消/失败/无账单）：删掉本次截屏
                    discardPendingScreenCapture()
                }
            )
        }
    }

    private fun screenCaptureFileFromUri(uri: android.net.Uri): java.io.File? {
        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        val file = java.io.File(path)
        return file.takeIf { it.exists() && it.parentFile?.name == "screen_captures" }
    }

    private fun discardPendingScreenCapture() {
        val file = pendingScreenCaptureFile ?: return
        pendingScreenCaptureFile = null
        if (file.exists()) {
            val ok = runCatching { file.delete() }.getOrDefault(false)
            Logger.d(ctx, "OverlayManager", "Discard screen capture cache: ${file.name}, deleted=$ok")
        }
    }

    /** 截完反馈：一道柔和扫光从上到下划过，不挡触摸。 */
    private fun playCaptureDoneHint(onFinished: () -> Unit) {
        try {
            playCaptureScanHintInternal(onFinished)
        } catch (e: Exception) {
            Logger.e(ctx, "OverlayManager", "playCaptureDoneHint failed: ${e.message}")
            onFinished()
        }
    }

    private fun playCaptureScanHintInternal(onFinished: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            onFinished()
            return
        }
        val wm = windowManager ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        if (wm == null) {
            onFinished()
            return
        }

        val density = ctx.resources.displayMetrics.density
        val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
        val bandH = (96f * density).toInt().coerceAtLeast(1)

        val root = FrameLayout(ctx).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        // 半透明渐变扫光带：中间亮、上下淡，看起来像扫过去而不是硬线。
        val scanBand = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0xAA7C9FF8.toInt(), 0x66FFFFFF, 0x00FFFFFF)
            )
            alpha = 0f
            translationY = -bandH.toFloat()
        }
        root.addView(
            scanBand,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                bandH
            ).apply { gravity = Gravity.TOP }
        )

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }

        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            Logger.e(ctx, "OverlayManager", "playCaptureDoneHint addView failed: ${e.message}")
            onFinished()
            return
        }

        runCatching { root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }

        val ease = PathInterpolator(0.33f, 0f, 0.2f, 1f)
        ValueAnimator.ofFloat(-bandH.toFloat(), screenH).apply {
            duration = CAPTURE_SCAN_DURATION_MS
            interpolator = ease
            addUpdateListener { anim ->
                val y = anim.animatedValue as Float
                val p = anim.animatedFraction
                scanBand.translationY = y
                // 进出时淡入淡出，中段保持可见
                scanBand.alpha = when {
                    p < 0.12f -> (p / 0.12f).coerceIn(0f, 1f)
                    p > 0.82f -> ((1f - p) / 0.18f).coerceIn(0f, 1f)
                    else -> 1f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runCatching { wm.removeView(root) }
                    onFinished()
                }
            })
            start()
        }
    }

    private fun handleScreenCaptureError(message: String) {
        Logger.d(ctx, "OverlayManager", "Screen capture recognition error callback: $message")
        hideScreenCaptureLoadingOverlay()
        setOverlayVisible(true)
        markOverlayRestoredNow()
        finishScreenCaptureFlow(restoreOverlay = false)
        Utils.toast(ctx, message.ifBlank { ctx.getString(R.string.toast_capture_failed) })
    }

    private fun handleScreenCaptureCancelled() {
        Logger.d(ctx, "OverlayManager", "Screen capture recognition cancelled")
        hideScreenCaptureLoadingOverlay()
        setOverlayVisible(true)
        markOverlayRestoredNow()
        finishScreenCaptureFlow(restoreOverlay = false)
        Utils.toast(ctx, ctx.getString(R.string.toast_capture_canceled))
    }

    private fun markOverlayRestoredNow() {
        // 保留空实现，供调用方兼容
    }

    private fun setOverlayVisible(visible: Boolean) {
        Logger.d(ctx, "OverlayManager", "setOverlayVisible($visible)")
        val view = overlayView ?: return
        cancelOverlayAnimations(view)
        if (visible) {
            overlayParams?.y = overlayFinalY()
            runCatching { overlayParams?.let { windowManager?.updateViewLayout(view, it) } }
            view.visibility = View.VISIBLE
            if (view.alpha >= 0.98f && view.scaleX >= 0.995f) {
                view.alpha = 1f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                return
            }
            val restoreOffset = OVERLAY_RESTORE_TRANSLATION_DP * ctx.resources.displayMetrics.density
            view.alpha = 0f
            view.translationY = restoreOffset
            view.scaleX = OVERLAY_HIDDEN_SCALE
            view.scaleY = OVERLAY_HIDDEN_SCALE
            currentViewAnimator = view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(OVERLAY_VISIBILITY_DURATION_MS)
                .setInterpolator(overlaySoftInterpolator)
                .withLayer()
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentViewAnimator = null
                        currentAnimator = null
                    }
                })
            currentViewAnimator?.start()
        } else {
            currentViewAnimator = view.animate()
                .alpha(0f)
                .translationY(OVERLAY_RESTORE_TRANSLATION_DP * ctx.resources.displayMetrics.density)
                .scaleX(OVERLAY_HIDDEN_SCALE)
                .scaleY(OVERLAY_HIDDEN_SCALE)
                .setDuration(OVERLAY_VISIBILITY_DURATION_MS)
                .setInterpolator(overlayExitInterpolator)
                .withLayer()
                .setListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        currentViewAnimator = null
                        currentAnimator = null
                        if (!cancelled) view.visibility = View.INVISIBLE
                    }
                })
            currentViewAnimator?.start()
        }
    }

    private fun showScreenCaptureLoadingOverlay(status: String) {
        captureLoadingView?.let {
            updateScreenCaptureLoadingStatus(status)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            Logger.d(ctx, "OverlayManager", "Skip screen capture loading overlay: missing overlay permission")
            return
        }
        val wm = windowManager ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        if (wm == null || captureLoadingView != null) return

        val themeContext = android.view.ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val loadingView = LayoutInflater.from(themeContext).inflate(R.layout.activity_screen_capture, null)
        loadingView.findViewById<TextView>(R.id.tv_capture_status)?.text = status
        loadingView.findViewById<TextView>(R.id.tv_capture_hint)?.text = ctx.getString(R.string.keep_page_still)

        captureLoadingParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }

        captureLoadingView = loadingView
        try {
            wm.addView(loadingView, captureLoadingParams)
        } catch (e: Exception) {
            Logger.e(ctx, "OverlayManager", "showScreenCaptureLoadingOverlay addView failed: ${e.message}")
            captureLoadingView = null
            captureLoadingParams = null
            return
        }

        loadingView.isFocusable = true
        loadingView.isFocusableInTouchMode = true
        loadingView.requestFocus()
        loadingView.setOnTouchListener { _, _ -> true }
        loadingView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                cancelScreenCaptureByUser()
                true
            } else {
                false
            }
        }

        startCaptureLoadingAnimation(loadingView)
    }

    private fun updateScreenCaptureLoadingStatus(status: String) {
        captureLoadingView?.findViewById<TextView>(R.id.tv_capture_status)?.text = status
    }

    private fun updateScreenCaptureLoadingHint(hint: String) {
        captureLoadingView?.findViewById<TextView>(R.id.tv_capture_hint)?.text = hint
    }

    private fun finishScreenCaptureFlow(restoreOverlay: Boolean) {
        if (!isScreenCaptureInProgress) return
        isScreenCaptureInProgress = false
        screenCaptureJob = null
        if (restoreOverlay) {
            setOverlayVisible(true)
        } else {
            // 不恢复悬浮窗时清理旧视图，避免 showOverlay 因 overlayView!=null 跳过重建
            overlayView?.let { v ->
                cancelOverlayAnimations(v)
                runCatching { windowManager?.removeView(v) }
            }
            overlayView = null
            overlayParams = null
        }
        screenCaptureTriggerBtn?.isEnabled = true
        screenCaptureTriggerBtn?.animate()?.alpha(1f)?.setDuration(120L)?.start()
        screenCaptureTriggerBtn = null
        ScreenCaptureActivity.onScreenshotCaptured = null
        ScreenCaptureActivity.onRecognitionError = null
        ScreenCaptureActivity.onRecognitionCancelled = null
    }

    private fun cancelScreenCaptureByUser() {
        if (!isScreenCaptureInProgress) return
        Logger.d(ctx, "OverlayManager", "Screen capture interrupted by back press")
        screenCaptureJob?.cancel()
        hideScreenCaptureLoadingOverlay()
        setOverlayVisible(true)
        markOverlayRestoredNow()
        finishScreenCaptureFlow(restoreOverlay = false)
        Utils.toast(ctx, ctx.getString(R.string.toast_capture_canceled))
    }

    private fun hideScreenCaptureLoadingOverlay() {
        stopCaptureLoadingAnimation()
        val view = captureLoadingView ?: return
        val wm = windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        captureLoadingView = null
        captureLoadingParams = null
        val captureCard = view.findViewById<View>(R.id.layout_capture_card)
        view.animate().cancel()
        captureCard?.animate()?.cancel()
        view.animate()
            .alpha(0f)
            .setDuration(CAPTURE_CARD_EXIT_DURATION_MS)
            .setInterpolator(overlayExitInterpolator)
            .withLayer()
            .withEndAction { runCatching { wm?.removeView(view) } }
            .start()
        captureCard?.animate()
            ?.scaleX(0.985f)
            ?.scaleY(0.985f)
            ?.translationY(8f * ctx.resources.displayMetrics.density)
            ?.setDuration(CAPTURE_CARD_EXIT_DURATION_MS)
            ?.setInterpolator(overlayExitInterpolator)
            ?.start()
    }

    private fun startCaptureLoadingAnimation(root: View) {
        stopCaptureLoadingAnimation()
        val captureCard = root.findViewById<View>(R.id.layout_capture_card) ?: return
        val scanLine   = root.findViewById<View>(R.id.view_scan_line) ?: return

        root.alpha = 0f
        root.animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(overlaySoftInterpolator)
            .withLayer()
            .start()

        captureCard.translationY = 18f * ctx.resources.displayMetrics.density

        val scaleX = ObjectAnimator.ofFloat(captureCard, "scaleX", 0.94f, 1f)
        val scaleY = ObjectAnimator.ofFloat(captureCard, "scaleY", 0.94f, 1f)
        val alpha  = ObjectAnimator.ofFloat(captureCard, "alpha", 0f, 1f)
        val translationY = ObjectAnimator.ofFloat(captureCard, "translationY", captureCard.translationY, 0f)
        captureCardEnterAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha, translationY)
            duration = CAPTURE_CARD_ENTER_DURATION_MS
            interpolator = overlaySoftInterpolator
            start()
        }

        // 扫描线：translationY 从 -屏幕高 到 +屏幕高 循环
        val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
        captureScanAnimator = ValueAnimator.ofFloat(-screenH, screenH).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedFraction
                scanLine.translationY = it.animatedValue as Float
                scanLine.alpha = (0.25f + 0.75f * kotlin.math.sin(progress * Math.PI).toFloat())
                    .coerceIn(0.25f, 1f)
            }
            start()
        }
    }

    private fun stopCaptureLoadingAnimation() {
        captureCardEnterAnimator?.cancel()
        captureCardEnterAnimator = null
        captureScanAnimator?.cancel()
        captureScanAnimator = null
    }

    private fun bitmapToTempUri(bitmap: Bitmap): android.net.Uri? {
        val dir = java.io.File(ctx.cacheDir, "screen_captures").apply { mkdirs() }
        val file = java.io.File(dir, "screenshot_${System.currentTimeMillis()}.jpg")
        return try {
            // 与聊天附件/图片记账共用同一套压缩参数（见 AiImageCompressor），截屏也保持可读分辨率
            if (!AiImageCompressor.compressBitmapToFile(bitmap, file)) return null
            android.net.Uri.fromFile(file)
        } catch (e: Exception) {
            Logger.e(ctx, "OverlayManager", "bitmapToTempUri failed: ${e.message}")
            null
        }
    }

    private fun processNextPendingBill() {
        if (pendingBills.isEmpty()) {
            removeOverlay(isSaved = true)
            return
        }

        val next = pendingBills.removeAt(0)
        if (overlayView != null && formController != null) {
            formController?.fillDataToUi(next, showToast = true)
            formController?.setCurrency(next.optString("currency", "CNY"))
            if (pendingBills.isNotEmpty()) {
                Utils.toast(ctx, ctx.getString(R.string.toast_remaining_bills, pendingBills.size))
            } else {
                Utils.toast(ctx, ctx.getString(R.string.toast_last_bill))
            }
        } else {
            showOverlay(next, showSaveOnly = false)
        }
    }

    private fun saveJsonToLocal(obj: JSONObject, aiBookName: String = "") {
        val typeIndex = obj.optInt("type", 0)
        val amount = obj.optDouble("amount", 0.0)
        val asset1 = obj.optString("asset_name", "")

        var categoryName = com.taostudio.tapaccounting.logic.CategoryNameNormalizer.normalizeForStorage(obj.optString("category_name", ""))
        var toAssetId: Long? = null
        if (typeIndex == 2 || typeIndex == 3) {
            val asset2 = obj.optString("to_asset_name", "")
            categoryName = if (typeIndex == 2) "转账到$asset2" else "还款到$asset2"
        }

        val timeStr = obj.optString(
            "time",
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        )
        val parsedTime = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(timeStr)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val remark = obj.optString("remarks", "")
        val recognizedCurrency = obj.optString("currency", "CNY").ifBlank { "CNY" }
        val fee = obj.optDouble("fee", 0.0).coerceAtLeast(0.0)

        CoroutineScope(Dispatchers.IO).launch {
            try {
            val db = AppDatabase.getDatabase(ctx)
            val availableBooks = BookAccountManager.getBookAccounts(ctx)
            val fallbackBookName = BookAccountManager.resolveWritableBook(
                ctx,
                BookAccountManager.getSelectedBook(ctx, availableBooks)
            )
            val resolvedBookName = resolveAccountingBookForSave(
                billBookName = obj.optString("book_name", ""),
                batchBookName = aiBookName,
                availableBooks = availableBooks,
                fallbackBookName = fallbackBookName
            )
            val asset1Obj = db.assetDao().getAssetByName(asset1)
            var toAssetObj: com.taostudio.tapaccounting.data.local.entity.Asset? = null
            if (typeIndex == 2 || typeIndex == 3) {
                val asset2 = obj.optString("to_asset_name", "")
                toAssetObj = db.assetDao().getAssetByName(asset2)
                toAssetId = toAssetObj?.id
            }
            val effectiveCurrency = if (typeIndex == Bill.TYPE_TRANSFER) {
                asset1Obj?.currency?.takeIf { it.isNotBlank() } ?: recognizedCurrency
            } else {
                recognizedCurrency
            }

            val exchangeRate = when {
                typeIndex == 2 && toAssetObj != null && amount > 0.0 ->
                    com.taostudio.tapaccounting.logic.BillAssetImpactService.estimateExchangeRateToTarget(
                        amount,
                        effectiveCurrency,
                        toAssetObj.currency
                    )
                effectiveCurrency == "CNY" -> 1.0
                else -> com.taostudio.tapaccounting.logic.BillAssetImpactService.estimateExchangeRateToCny(effectiveCurrency)
            }

            val bill = Bill(
                amount = amount,
                type = typeIndex,
                currency = effectiveCurrency,
                exchangeRate = exchangeRate,
                fee = fee,
                accountName = asset1,
                accountId = asset1Obj?.id,
                toAccountId = toAssetId,
                categoryName = categoryName,
                time = parsedTime,
                remark = remark,
                bookName = resolvedBookName
            )
            com.taostudio.tapaccounting.logic.BillMutationService.insertBillAndApplyImpact(db, bill)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(ctx, "OverlayManager", "AI 多单落库失败：${e.message}")
            }
        }
    }

    /** OverlayService 销毁时调用：释放管理器级常驻资源（removeOverlay 只处理单次表单的资源） */
    fun destroy() {
        formController?.destroy()
        formController = null
        aiAssistant.shutdown()
    }

    fun removeOverlay(isSaved: Boolean = true) {
        // 若仍挂着「未进入记账」的截屏，关掉悬浮层时一并删掉
        discardPendingScreenCapture()
        hideScreenCaptureLoadingOverlay()
        finishScreenCaptureFlow(restoreOverlay = false)
        voiceHandler?.release()
        voiceHandler = null

        if (overlayView == null || isRemovingOverlay) {
            formController?.destroy()
            formController = null
            overlayParams = null
            return
        }

        isRemovingOverlay = true
        Logger.d(ctx, "OverlayManager", "Removing Overlay with animation")
        // P2-25: 清理静态回调，避免悬挂
        runCatching { ImagePickerActivity.clearCallbacks() }

        // 立即锁定表单，阻止用户继续交互
        formController?.destroy()
        formController = null
        val view = overlayView!!
        cancelOverlayAnimations(view)

        val exitOffset = OVERLAY_EXIT_TRANSLATION_DP * ctx.resources.displayMetrics.density
        // 退场改为“下滑 + 淡出 + 轻缩放”，可感知更强，同时保持稳定
        currentViewAnimator = view.animate()
            .alpha(0f)
            .translationY(exitOffset)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(OVERLAY_EXIT_DURATION_MS)
            .setInterpolator(overlayExitInterpolator)
            .withLayer()
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    currentViewAnimator = null
                    currentAnimator = null
                    view.visibility = View.INVISIBLE
                    runCatching { windowManager?.removeView(view) }
                    overlayView = null
                    overlayParams = null
                    isRemovingOverlay = false
                    if (!cancelled) {
                        handlePostRemove(isSaved)
                    }
                }
            })
        currentViewAnimator?.start()
    }

    private fun startOverlayEnterAnimation(view: View) {
        cancelOverlayAnimations(view)
        currentViewAnimator = view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(OVERLAY_ENTER_DURATION_MS)
            .setInterpolator(overlayEnterInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    currentViewAnimator = null
                    currentAnimator = null
                }
            })
        currentViewAnimator?.start()
    }

    private fun cancelOverlayAnimations(view: View) {
        currentAnimator?.cancel()
        currentAnimator = null
        currentViewAnimator?.setListener(null)
        currentViewAnimator?.cancel()
        currentViewAnimator = null
        view.animate().setListener(null)
        view.animate().cancel()
        view.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun handlePostRemove(isSaved: Boolean) {
        if (!isSaved) {
            if (pendingBills.isNotEmpty()) {
                pendingBills.clear()
                Utils.toast(ctx, ctx.getString(R.string.toast_multi_bill_recognition_canceled))
            }
            return
        }
        if (pendingBills.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                processNextPendingBill()
            }, 350)
        }
    }
}
