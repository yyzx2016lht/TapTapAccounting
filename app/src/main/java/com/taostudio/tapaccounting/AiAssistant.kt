package com.taostudio.tapaccounting

import android.app.Activity
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AiAssistant(private val ctx: Context) {

    private var currentDialog: AlertDialog? = null
    private var tvThinkingLog: TextView? = null
    private var tvRecordedTextPreview: TextView? = null
    private var progressAiLoading: View? = null
    private var btnExpandPreview: TextView? = null
    /** 多条账单识别完成后「一键全部入账 / 逐条确认入账」的按钮容器 */
    private var layoutMultiRecordActions: View? = null
    private var btnRecordAllAtOnce: TextView? = null
    private var btnRecordOneByOne: TextView? = null
    private var analyzeJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 待记图片记账：结果面板只提供“确认入账并合并”，不逐条编辑。 */
    private var captureMode = false
    /** capture 模式用于在结果页显示“付款/识别合计/差额”。 */
    private var captureExpectedTotalCents: Long? = null
    /** capture 的币种；入账强制使用它，预览也按它显示。 */
    private var captureExpectedCurrency: String = "CNY"

    /** P1-29: 宿主销毁时取消协程，避免 scope 永不 cancel；同时移除常驻小圆。 */
    fun shutdown() {
        analyzeJob?.cancel()
        pendingCollapsedResult = null
        pendingCollapsedOnResult = null
        removeMiniBubble()
        isPinned = false
        currentDialog?.setOnDismissListener(null)
        runCatching { currentDialog?.dismiss() }
        currentDialog = null
        scope.cancel()
    }
    private var lastReceiptImageUri: Uri? = null
    private var currentHideStreamText: Boolean = false

    // ── F08: AI 面板收起为侧边常驻小圆 ──────────────────────
    /** 用户点过「缩小」后，点外面/返回/失焦都只收成小圆，不彻底关闭。 */
    private var isPinned: Boolean = false
    /** 常驻小圆；非 null 时面板一定处于 dialog.hide() 的收起状态。 */
    private var miniBubble: View? = null
    private var miniBubbleWindowManager: WindowManager? = null
    private var miniBubbleBadge: View? = null

    /** 从收起态展开前：宿主可暂时隐藏压在面板上面的普通表单。 */
    var onPanelExpanding: (() -> Unit)? = null
    /** 面板收进小圆后：closeOverlayForm=true 表示本次是用户主动收起（pin/点外面/返回）。 */
    var onPanelCollapsed: ((closeOverlayForm: Boolean) -> Unit)? = null
    /** 面板彻底关闭后：宿主恢复之前被临时隐藏的普通表单。 */
    var onPanelClosed: (() -> Unit)? = null

    /** Dialog.isCancelable() 不在公开 SDK 里，这里同步记录一份。 */
    private var panelCancelable = true

    /** 收起期间返回的单笔结果：先挂起亮角标，点小圆回来再交给表单。 */
    private var pendingCollapsedResult: JSONObject? = null
    private var pendingCollapsedOnResult: ((JSONObject) -> Unit)? = null

    private var outsideTouchDown = false
    private var bubbleTouchDownRawX = 0f
    private var bubbleTouchDownRawY = 0f
    private var bubbleTouchStartX = 0
    private var bubbleTouchStartY = 0
    private var bubbleTouchDownAtMs = 0L
    private var bubbleTouchMoved = false
    private var bubbleDropShown = false
    private var bubbleDropArmed = false
    private var bubbleVelocity: android.view.VelocityTracker? = null

    // 外部可注入语音按钮绑定逻辑
    var voiceInputBtnSetup: ((View) -> Unit)? = null

    // 外部可注入图片选择逻辑
    var imagePicker: (() -> Unit)? = null

    companion object {
        const val MODE_INPUT = 0
        const val MODE_RECORDING = 1
        const val MODE_LOADING = 2
        const val MODE_CANCEL = 3

        private const val VOICE_PLACEHOLDER_TEXT = "正在解析语音..."

        /** 多账单结果里的入账方式标记：整批直接入库，不经逐条表单。 */
        const val EXTRA_BATCH_SAVE_ALL = "batch_save_all"
        /** 多账单结果里的入账方式标记：强制走逐条确认队列（忽略「多笔不弹窗」设置）。 */
        const val EXTRA_FORCE_BILL_QUEUE = "force_bill_queue"
        /** 图片记账顶层字段：画面本身是否为「一次消费的凭证」（一张小票/一个支付成功页/一个订单详情）。 */
        const val KEY_SINGLE_PAYMENT = "single_payment"

        /** F08: 收起后常驻胶囊的尺寸（dp），和待记胶囊保持一致。 */
        private const val MINI_BUBBLE_W_DP = 104
        private const val MINI_BUBBLE_H_DP = 50
        private const val MINI_BUBBLE_EDGE_MARGIN_DP = 6
        /** 没保存过位置时胶囊距离屏幕顶部的比例。 */
        private const val MINI_BUBBLE_DEFAULT_Y_RATIO = 0.42f
        private const val MINI_BUBBLE_ANIM_MS = 140L

    }

    fun showInputPanel(
        defaultText: String? = null,
        mode: Int = MODE_INPUT,
        hideStreamText: Boolean = true,
        onResult: (JSONObject) -> Unit
    ) {
        if (!ensureOverlayPermission()) return
        captureMode = false
        val finalMode = mode
        currentHideStreamText = hideStreamText

        // 已有面板则复用：收起态先展开，绝不创建第二个面板
        val existing = currentDialog
        if (existing != null) {
            if (miniBubble != null) {
                val hadPendingResult = pendingCollapsedResult != null
                expandFromBubble()
                if (hadPendingResult) return
                // 如果正在后台识别或正处于多笔结果确认界面，外部若没有给具体文字，则保留当前状态不强切为 INPUT
                val isBusyOrShowingMultiActions = analyzeJob?.isActive == true ||
                    layoutMultiRecordActions?.visibility == View.VISIBLE
                if (defaultText.isNullOrEmpty() && isBusyOrShowingMultiActions) {
                    return
                }
                updatePanelState(finalMode, defaultText)
                if (finalMode == MODE_LOADING && !defaultText.isNullOrEmpty() && defaultText != VOICE_PLACEHOLDER_TEXT) {
                    startAnalysis(defaultText, onResult)
                }
                return
            }
            if (existing.isShowing) {
                updatePanelState(finalMode, defaultText)
                if (finalMode == MODE_LOADING && !defaultText.isNullOrEmpty() && defaultText != VOICE_PLACEHOLDER_TEXT) {
                    startAnalysis(defaultText, onResult)
                }
                return
            }
            // show 失败等原因留下的失效引用：清掉再重建
            existing.setOnDismissListener(null)
            runCatching { existing.dismiss() }
            currentDialog = null
        }

        stopTapIfNeeded()

        val (dialog, view) = createDialog(cancelable = true)
        currentDialog = dialog

        val btnClose = view.findViewById<View>(R.id.btn_close)
        val btnIdentify = view.findViewById<View>(R.id.btn_dialog_identify)
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)

        disableSelectionActionModeIfService(etInput)

        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)
        progressAiLoading = view.findViewById(R.id.progress_ai_loading)
        btnExpandPreview = view.findViewById(R.id.btn_expand_preview)
        layoutMultiRecordActions = view.findViewById(R.id.layout_multi_record_actions)
        btnRecordAllAtOnce = view.findViewById(R.id.btn_record_all_at_once)
        btnRecordOneByOne = view.findViewById(R.id.btn_record_one_by_one)

        btnClose.setOnClickListener { dismiss() }

        view.findViewById<View>(R.id.btn_dialog_voice)?.let { voiceInputBtnSetup?.invoke(it) }

        btnIdentify.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Utils.toast(ctx, ctx.getString(R.string.toast_input_content))
                return@setOnClickListener
            }
            updatePanelState(MODE_LOADING, ctx.getString(R.string.analyzing_semantic))
            startAnalysis(text, onResult)
        }

        updatePanelState(finalMode, defaultText)
        if (finalMode == MODE_INPUT && !defaultText.isNullOrEmpty() && defaultText != VOICE_PLACEHOLDER_TEXT) {
            etInput.setText(defaultText)
            etInput.setSelection(defaultText.length)
        } else if (finalMode == MODE_LOADING && !defaultText.isNullOrEmpty() && defaultText != VOICE_PLACEHOLDER_TEXT) {
            startAnalysis(defaultText, onResult)
        }
    }

    /** 小圆或已 pin 的面板仍然存在，OverlayService 不能按 idle 退出。 */
    fun isPinnedPanelActive(): Boolean = isPinned || miniBubble != null

    /**
     * 面板收起为侧边小圆，用 hide() 保留 dialog 和输入框里的文字。
     *
     * @param closeOverlayForm 用户主动 pin/点外面/返回时为 true，顺带收起普通记账表单；
     *                         识别结果自动收起时传 false，避免把刚填好的表单关掉。
     */
    fun collapseToBubble(closeOverlayForm: Boolean = true) {
        val dialog = currentDialog ?: return
        if (!canUseFloatingBubble()) return
        isPinned = true
        hideIme(dialog)
        if (dialog.isShowing) {
            runCatching { dialog.hide() }
        }
        if (!showMiniBubble()) {
            runCatching { dialog.show() }
            return
        }
        onPanelCollapsed?.invoke(closeOverlayForm)
    }

    /** 从小圆恢复面板；原 view 还在，文字和识别结果都不会丢。 */
    fun expandFromBubble() {
        val dialog = currentDialog
        removeMiniBubble()
        if (dialog == null) {
            isPinned = false
            return
        }
        try {
            // hide() 只把 decor 置为 GONE，show() 会重新置为 VISIBLE
            dialog.show()
        } catch (e: Exception) {
            Logger.e(ctx, "AiAssistant", "expandFromBubble failed: ${e.message}")
            dialog.setOnDismissListener(null)
            runCatching { dialog.dismiss() }
            currentDialog = null
            isPinned = false
            return
        }
        // 面板恢复可见后再隐藏表单，保证面板不会压在 INVISIBLE 的表单下面
        onPanelExpanding?.invoke()
        val pendingResult = pendingCollapsedResult
        if (pendingResult != null) {
            val callback = pendingCollapsedOnResult
            pendingCollapsedResult = null
            pendingCollapsedOnResult = null
            callback?.invoke(pendingResult)
            // 结果交给表单后重新收回小圆，保持连续记账
            finishAfterResult(notifyBadge = false)
            return
        }
        val inputLayout = dialog.findViewById<View>(R.id.layout_input)
        if (inputLayout?.visibility == View.VISIBLE) {
            dialog.findViewById<EditText>(R.id.et_ai_input)?.requestFocus()
        }
    }

    /** 收起期间拿到的结果先存起来，避免在面板背后弹出记账表单。 */
    private fun holdResultForCollapsedPanel(result: JSONObject, onResult: (JSONObject) -> Unit) {
        pendingCollapsedResult = result
        pendingCollapsedOnResult = onResult
        showBubbleBadge()
    }

    private fun updatePanelState(mode: Int, text: String? = null) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutInput = view.findViewById<View>(R.id.layout_input)
        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val layoutResult = view.findViewById<View>(R.id.layout_result)
        val btnClose = view.findViewById<View>(R.id.btn_close)
        val btnPin = view.findViewById<View>(R.id.btn_pin)

        when (mode) {
            MODE_INPUT -> {
                layoutInput.visibility = View.VISIBLE
                layoutLoading.visibility = View.GONE
                layoutResult.visibility = View.GONE
                dialog.setCancelable(true)
                btnClose.visibility = View.VISIBLE
                if (!text.isNullOrEmpty() && text != VOICE_PLACEHOLDER_TEXT) {
                    val etInput = layoutInput.findViewById<EditText>(R.id.et_ai_input)
                    etInput?.setText(text)
                    etInput?.setSelection(text.length)
                }
            }

            MODE_RECORDING -> {
                layoutInput.visibility = View.GONE
                layoutLoading.visibility = View.VISIBLE
                layoutResult.visibility = View.GONE
                tvThinkingLog?.text = if (!text.isNullOrEmpty()) ctx.getString(R.string.listening_to, text) else ctx.getString(R.string.listening)
                tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
                tvRecordedTextPreview?.visibility = View.GONE
                progressAiLoading?.visibility = View.VISIBLE
                btnExpandPreview?.visibility = View.GONE
                layoutMultiRecordActions?.visibility = View.GONE
                dialog.setCancelable(false)
                btnClose.visibility = View.GONE
            }

            MODE_CANCEL -> {
                layoutInput.visibility = View.GONE
                layoutLoading.visibility = View.VISIBLE
                layoutResult.visibility = View.GONE
                tvThinkingLog?.text = ctx.getString(R.string.release_to_cancel_overlay)
                tvThinkingLog?.setTextColor(android.graphics.Color.RED)
                tvRecordedTextPreview?.visibility = View.GONE
                progressAiLoading?.visibility = View.VISIBLE
                btnExpandPreview?.visibility = View.GONE
                layoutMultiRecordActions?.visibility = View.GONE
                dialog.setCancelable(false)
                btnClose.visibility = View.GONE
            }

            MODE_LOADING -> {
                layoutInput.visibility = View.GONE
                layoutLoading.visibility = View.VISIBLE
                layoutResult.visibility = View.GONE
                tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
                tvThinkingLog?.text = text ?: ctx.getString(R.string.processing)
                tvRecordedTextPreview?.visibility = View.GONE
                progressAiLoading?.visibility = View.VISIBLE
                btnExpandPreview?.visibility = View.GONE
                layoutMultiRecordActions?.visibility = View.GONE
                dialog.setCancelable(true)
                btnClose.visibility = View.VISIBLE
            }
        }
        panelCancelable = mode == MODE_INPUT || mode == MODE_LOADING
        // 语音录制中不提供收起入口，避免录音状态被挂到小圆上
        btnPin?.visibility =
            if (canUseFloatingBubble() && mode != MODE_RECORDING && mode != MODE_CANCEL) View.VISIBLE else View.GONE
    }

    private fun updateLoadingText(text: String) {
        tvThinkingLog?.let { label ->
            if (label.text?.toString() != text) {
                label.text = text
            }
        }
    }

    private fun updateLoadingPreview(text: String?) {
        tvRecordedTextPreview?.let { preview ->
            if (text.isNullOrBlank()) {
                preview.visibility = View.GONE
                preview.text = ""
            } else {
                preview.visibility = View.VISIBLE
                preview.background = null
                preview.gravity = Gravity.CENTER
                preview.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
                if (preview.text?.toString() != text) {
                    preview.text = text
                }
            }
        }
    }

    private fun overlayHiddenStreamStatus(): String =
        ctx.getString(R.string.overlay_hidden_stream_status)

    private fun buildOverlayStreamingPreview(raw: String, previous: String): String? {
        val result = StreamingBillPreview.formatOverlayPreview(raw, previous) { remark, category, amount, currency ->
            formatOverlayBillPreviewLine(remark, category, amount, currency)
        }
        Logger.d(ctx, "StreamPreview", "buildOverlay: rawLen=${raw.length}, result=${result?.take(80)}")
        return result
    }

    private fun applyOverlayStreamProgress(
        status: String,
        streamedRaw: StringBuilder,
        streamState: OverlayStreamUiState
    ) {
        if (status.startsWith("AI_STREAM_TEXT::")) {
            val delta = status.removePrefix("AI_STREAM_TEXT::")
            if (delta.isBlank()) return
            streamState.started = true
            streamedRaw.append(delta)
            Logger.d(ctx, "StreamPreview", "rawLen=${streamedRaw.length}, delta=${delta.take(30)}")
            val candidate = buildOverlayStreamingPreview(streamedRaw.toString(), streamState.lastPreview) ?: return
            if (!StreamingBillPreview.shouldUpdateUi(streamState.lastPreview, candidate, streamState.lastUpdateMs)) {
                return
            }
            streamState.lastPreview = candidate
            streamState.lastUpdateMs = android.os.SystemClock.elapsedRealtime()
            updateLoadingText(ctx.getString(R.string.organizing_bills))
            updateLoadingPreview(candidate)
            if (!streamState.spinnerHidden) {
                progressAiLoading?.visibility = View.GONE
                streamState.spinnerHidden = true
            }
            return
        }
        if (!StreamingBillPreview.shouldApplyNonStreamProgress(streamState.started)) return
        updateLoadingText(status)
    }

    private class OverlayStreamUiState(
        var started: Boolean = false,
        var lastPreview: String = "",
        var lastUpdateMs: Long = 0L,
        var spinnerHidden: Boolean = false
    )

    private fun bindLoadingPanelViews(view: View) {
        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)
        progressAiLoading = view.findViewById(R.id.progress_ai_loading)
        btnExpandPreview = view.findViewById(R.id.btn_expand_preview)
        layoutMultiRecordActions = view.findViewById(R.id.layout_multi_record_actions)
        btnRecordAllAtOnce = view.findViewById(R.id.btn_record_all_at_once)
        btnRecordOneByOne = view.findViewById(R.id.btn_record_one_by_one)
    }

    private fun presentAccountingResult(
        result: JSONObject,
        sourceText: String,
        onResult: (JSONObject) -> Unit
    ) {
        if (captureMode) {
            presentCaptureAccountingResult(result, onResult)
            return
        }
        if (result.has("bills")) {
            val bills = result.getJSONArray("bills")
            for (i in 0 until bills.length()) {
                bills.getJSONObject(i).put("original_text_from_user", sourceText)
            }
            if (bills.length() == 1) {
                val bill = bills.getJSONObject(0)
                if (miniBubble != null) {
                    holdResultForCollapsedPanel(bill, onResult)
                    return
                }
                // 先回调再收尾，避免收尾时把「未进入记账」当成放弃而删截屏
                onResult(bill)
                finishAfterResult()
                return
            }
            updateLoadingText(ctx.getString(R.string.recognition_complete_count, bills.length()))
            progressAiLoading?.visibility = View.GONE
            updateLoadingPreview(formatOverlayFinalBillPreview(bills))
            btnExpandPreview?.apply {
                visibility = if (bills.length() > 8) View.VISIBLE else View.GONE
                text = ctx.getString(R.string.expand_all)
                var expanded = false
                setOnClickListener {
                    expanded = !expanded
                    updateLoadingPreview(formatOverlayFinalBillPreview(bills, expanded))
                    text = if (expanded) ctx.getString(R.string.collapse) else ctx.getString(R.string.expand_all)
                }
            }
            layoutMultiRecordActions?.visibility = View.VISIBLE
            btnRecordAllAtOnce?.text = ctx.getString(R.string.record_all_at_once)
            btnRecordAllAtOnce?.setOnClickListener {
                // 整批直存；落库后由表单侧按图片来源判断要不要问一次「是否合并」
                result.put(EXTRA_BATCH_SAVE_ALL, true)
                onResult(result)
                finishAfterResult(notifyBadge = false)
            }
            btnRecordOneByOne?.visibility = View.VISIBLE
            btnRecordOneByOne?.setOnClickListener {
                // 逐条走记账表单确认
                result.put(EXTRA_FORCE_BILL_QUEUE, true)
                onResult(result)
                finishAfterResult(notifyBadge = false)
            }
            // 多笔结果需要用户选入账方式，收起时只亮角标，点小圆再回来操作
            if (miniBubble != null) showBubbleBadge()
        } else {
            result.put("original_text_from_user", sourceText)
            if (miniBubble != null) {
                holdResultForCollapsedPanel(result, onResult)
                return
            }
            onResult(result)
            finishAfterResult()
        }
    }

    /**
     * 待记图片记账的结果页：只展示识别明细和一个「确认入账并合并」按钮。
     *
     * 不提供整批直存/逐条确认；取消（X/点外面/返回）只关闭面板，待记仍为 PENDING。
     */
    private fun presentCaptureAccountingResult(
        result: JSONObject,
        onResult: (JSONObject) -> Unit
    ) {
        val bills = result.optJSONArray("bills") ?: JSONArray().put(result)
        if (bills.length() == 0) {
            Utils.toast(ctx, "没有从图片识别到可入账的明细")
            dismiss()
            return
        }
        // 预览也按 capture 的币种显示，避免 AI 输出的币种与最终入账不一致。
        for (i in 0 until bills.length()) {
            bills.optJSONObject(i)?.put("currency", captureExpectedCurrency)
        }
        progressAiLoading?.visibility = View.GONE
        updateLoadingText(ctx.getString(R.string.recognized_bills_count, bills.length()))
        updateLoadingPreview(
            buildList {
                captureTotalPreviewLine(bills)?.let { add(it) }
                add(formatOverlayFinalBillPreview(bills))
            }.joinToString("\n")
        )
        btnExpandPreview?.apply {
            visibility = if (bills.length() > 8) View.VISIBLE else View.GONE
            text = ctx.getString(R.string.expand_all)
            var expanded = false
            setOnClickListener {
                expanded = !expanded
                updateLoadingPreview(
                    buildList {
                        captureTotalPreviewLine(bills)?.let { add(it) }
                        add(formatOverlayFinalBillPreview(bills, expanded))
                    }.joinToString("\n")
                )
                text = if (expanded) {
                    ctx.getString(R.string.collapse)
                } else {
                    ctx.getString(R.string.expand_all)
                }
            }
        }
        val isExceeded = (captureTotalDiffCents(bills) ?: 0L) < 0L
        layoutMultiRecordActions?.visibility = View.VISIBLE
        btnRecordOneByOne?.visibility = View.GONE
        btnRecordAllAtOnce?.apply {
            text = ctx.getString(R.string.confirm_and_merge)
            isEnabled = !isExceeded
            alpha = if (isExceeded) 0.45f else 1f
            setOnClickListener {
                if (isExceeded) {
                    Utils.toast(ctx, "识别合计超出待记付款金额，无法入账")
                    return@setOnClickListener
                }
                onResult(result)
                finishAfterResult(notifyBadge = false)
            }
        }
        if (miniBubble != null) showBubbleBadge()
    }

    private fun captureTotalDiffCents(bills: JSONArray): Long? {
        val totalCents = captureExpectedTotalCents?.takeIf { it > 0L } ?: return null
        var recognizedCents = 0L
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            val amount = bill.optDouble("amount", Double.NaN)
            if (!amount.isNaN() && amount > 0.0) {
                recognizedCents += Math.round(amount * 100.0)
            }
        }
        return totalCents - recognizedCents
    }

    /** capture 模式的结果页顶部：付款 / 识别合计 / 差额（补“其他”或超出拒绝）。 */
    private fun captureTotalPreviewLine(bills: JSONArray): String? {
        val totalCents = captureExpectedTotalCents?.takeIf { it > 0L } ?: return null
        val diff = captureTotalDiffCents(bills) ?: return null
        var recognizedCents = 0L
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            val amount = bill.optDouble("amount", Double.NaN)
            if (!amount.isNaN() && amount > 0.0) {
                recognizedCents += Math.round(amount * 100.0)
            }
        }
        val currency = captureExpectedCurrency
        val totalText = formatMoneyWithSymbol(totalCents / 100.0, currency)
        val recognizedText = formatMoneyWithSymbol(recognizedCents / 100.0, currency)
        return when {
            diff == 0L -> "付款 $totalText · 识别合计 $recognizedText"
            diff > 0L -> "付款 $totalText · 识别合计 $recognizedText · 差额 ${formatMoneyWithSymbol(diff / 100.0, currency)} 将补入「其他」"
            else -> "付款 $totalText · 识别合计 $recognizedText · 已超出，无法入账"
        }
    }

    private fun formatOverlayFinalBillPreview(bills: JSONArray, expanded: Boolean = false): String {
        val lines = mutableListOf<String>()
        val limit = if (expanded) bills.length() else 8
        for (i in 0 until minOf(bills.length(), limit)) {
            val bill = bills.optJSONObject(i) ?: continue
            val remark = bill.optString("remarks", bill.optString("remark", "")).ifBlank { ctx.getString(R.string.unnamed_bill) }
            val category = bill.optString("category_name", "")
            val amount = bill.optDouble("amount", Double.NaN).takeUnless { it.isNaN() }
            val currency = bill.optString("currency", "")
            lines += formatOverlayBillPreviewLine(
                remark = remark,
                category = category,
                amount = amount,
                currency = currency
            )
        }
        lines += ctx.getString(R.string.recognized_bills_count, bills.length())
        return lines.joinToString("\n")
    }

    private fun formatOverlayBillPreviewLine(
        remark: String,
        category: String,
        amount: Double?,
        currency: String
    ): String {
        val amountText = amount?.let { formatMoneyWithSymbol(it, currency) }.orEmpty()
        val categoryText = category.ifBlank { ctx.getString(R.string.category_recognizing) }
        return listOf(remark, categoryText, amountText)
            .filter { it.isNotBlank() }
            .joinToString("  ")
    }

    private fun formatMoneyWithSymbol(amount: Double, currency: String): String {
        val code = currency.uppercase(Locale.ROOT)
        val symbol = when (code) {
            "CNY", "RMB", "CNH" -> "￥"
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "JPY" -> "¥"
            "HKD" -> "HK$"
            "TWD" -> "NT$"
            "AUD" -> "A$"
            "CAD" -> "C$"
            "SGD" -> "S$"
            "PLN" -> "zł"
            else -> code.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
        }
        val value = if (amount % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", amount)
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
        return "$symbol$value"
    }

    private fun startAnalysis(
        text: String,
        onResult: (JSONObject) -> Unit,
        visualReviewSource: String? = null,
        visualDraftText: String = ""
    ) {
        Logger.d(ctx, "StreamPreview", "startAnalysis called, text=${text.take(50)}, hideStream=$currentHideStreamText")
        analyzeJob?.cancel()
        pendingCollapsedResult = null
        pendingCollapsedOnResult = null
        val hideStream = currentHideStreamText
        analyzeJob = scope.launch {
            try {
                val streamState = OverlayStreamUiState()
                val streamedRaw = StringBuilder()
                val result = AIService.analyzeAccounting(ctx, text, onProgress = { status ->
                    Handler(Looper.getMainLooper()).post {
                        if (currentDialog?.isShowing == true) {
                            if (hideStream) {
                                if (status.startsWith("AI_STREAM_TEXT::")) {
                                    applyOverlayStreamProgress(status, streamedRaw, streamState)
                                } else if (!streamState.started) {
                                    updateLoadingText(overlayHiddenStreamStatus())
                                }
                            } else {
                                updateLoadingText(status)
                            }
                        }
                    }
                })
                withContext(Dispatchers.Main) {
                    if (result == null) {
                        Utils.toast(ctx, ctx.getString(R.string.toast_parse_failed))
                        updatePanelState(MODE_INPUT, text)
                        return@withContext
                    }

                    if (visualReviewSource != null) {
                        AIService.markVisualAccountingReviewDraft(
                            root = result,
                            sourceKind = visualReviewSource,
                            naturalSummary = visualDraftText.ifBlank { text },
                            includePaymentMethod = Prefs.isAssetFeatureEnabled(ctx)
                        )
                    }

                    if (result.has("bills")) {
                        val bills = result.getJSONArray("bills")
                        for (i in 0 until bills.length()) {
                            bills.getJSONObject(i).put("original_text_from_user", text)
                        }
                        presentAccountingResult(result, text, onResult)
                    } else {
                        result.put("original_text_from_user", text)
                        if (miniBubble != null) {
                            holdResultForCollapsedPanel(result, onResult)
                        } else {
                            finishAfterResult()
                            onResult(result)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, ctx.getString(R.string.toast_canceled))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, ctx.getString(R.string.toast_ai_request_failed))
                    updatePanelState(MODE_INPUT, text)
                }
            }
        }
    }

    private fun showResult(result: JSONObject, onResult: (JSONObject) -> Unit) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val layoutResult = view.findViewById<View>(R.id.layout_result)
        val btnClose = view.findViewById<View>(R.id.btn_close)

        val tvResTime = view.findViewById<TextView>(R.id.tv_res_time)
        val tvResMoney = view.findViewById<TextView>(R.id.tv_res_money)
        val tvResCate = view.findViewById<TextView>(R.id.tv_res_cate)
        val tvResSummary = view.findViewById<TextView>(R.id.tv_res_summary)
        val tvResAsset = view.findViewById<TextView>(R.id.tv_res_asset)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_fill)
        val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)

        if (result.has("bills")) {
            val bills = result.getJSONArray("bills")
            val count = bills.length()

            tvResMoney.text = ctx.getString(R.string.recognition_complete_count, count)
            tvResMoney.setTextColor(android.graphics.Color.parseColor("#5C6BC0"))

            if (count > 0) {
                val first = bills.getJSONObject(0)
                val firstAmt = first.optDouble("amount", 0.0)
                val firstCat = formatCategoryDisplay(first.optString("category_name", ""))
                val firstRemark = first.optString("remarks", first.optString("remark", "")).ifBlank { ctx.getString(R.string.remark_not_filled) }
                tvResCate.text = ctx.getString(R.string.first_category_label, firstCat.ifBlank { ctx.getString(R.string.pending_confirm) })
                tvResSummary.text = buildMultiBillSummary(bills, firstAmt, firstRemark)
                if (assetFeatureEnabled) {
                    tvResAsset.visibility = View.VISIBLE
                    tvResAsset.text = buildMultiBillAssetSummary(bills)
                } else {
                    tvResAsset.visibility = View.GONE
                }
            } else {
                tvResCate.text = ctx.getString(R.string.recognition_pending_confirm)
                tvResSummary.text = ctx.getString(R.string.confirm_to_continue)
                tvResAsset.visibility = View.GONE
            }
            tvResTime.visibility = View.GONE
        } else {
            val type = result.optInt("type", 0)
            val amt = result.optDouble("amount", 0.0)

            tvResMoney.text = when (type) {
                1 -> "+$amt"
                2, 3 -> "$amt"
                else -> "-$amt"
            }
            tvResMoney.setTextColor(
                android.graphics.Color.parseColor(
                    if (type == 1) "#E91E63" else "#2E7D32"
                )
            )

            val timeStr = result.optString("time", "")
            tvResTime.text = if (timeStr.isNotEmpty()) ctx.getString(R.string.time_label, timeStr) else ctx.getString(R.string.time_now)
            tvResTime.visibility = View.VISIBLE
            val remark = result.optString("remarks", result.optString("remark", "")).ifBlank { ctx.getString(R.string.remark_not_filled) }

            when (type) {
                2 -> {
                    tvResCate.text = ctx.getString(R.string.transfer_to_account_label, result.optString("to_asset_name", "--"))
                    tvResSummary.text = remark
                    tvResAsset.visibility = View.VISIBLE
                    tvResAsset.text = ctx.getString(R.string.from_account) + ": ${result.optString("asset_name", "--")}"
                }

                3 -> {
                    tvResCate.text = ctx.getString(R.string.repay_to_label, result.optString("to_asset_name", "--"))
                    tvResSummary.text = remark
                    tvResAsset.visibility = View.VISIBLE
                    tvResAsset.text = ctx.getString(R.string.payer_label, result.optString("asset_name", "--"))
                }

                else -> {
                    val cat = formatCategoryDisplay(result.optString("category_name", ""))
                    tvResCate.text = ctx.getString(R.string.category_label_fmt, cat.ifBlank { ctx.getString(R.string.pending_confirm) })
                    tvResSummary.text = remark
                    if (assetFeatureEnabled) {
                        val assetName = result.optString("asset_name", "")
                        tvResAsset.visibility = View.VISIBLE
                        tvResAsset.text = ctx.getString(R.string.account_label_fmt, if (assetName.isEmpty()) ctx.getString(R.string.not_recognized) else assetName)
                    } else {
                        tvResAsset.visibility = View.GONE
                    }
                }
            }
        }

        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.VISIBLE
        dialog.setCancelable(true)
        panelCancelable = true
        btnClose.visibility = View.VISIBLE

        btnConfirm.setOnClickListener {
            dismiss()
            onResult(result)
        }
    }

    private fun formatCategoryDisplay(raw: String): String {
        return raw
            .replace("/::/", " - ")
            .replace("/:::/", " - ")
            .replace("::", " - ")
            .trim()
    }

    private fun buildMultiBillSummary(bills: JSONArray, firstAmount: Double, firstRemark: String): String {
        val previewLines = mutableListOf<String>()
        previewLines += ctx.getString(R.string.first_bill_preview, firstRemark, "¥${String.format("%.2f", firstAmount)}")
        val remaining = bills.length() - 1
        if (remaining > 0) {
            previewLines += ctx.getString(R.string.remaining_bills_hint, remaining)
        }
        return previewLines.joinToString("\n")
    }

    private fun buildMultiBillAssetSummary(bills: JSONArray): String {
        val assets = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        for (i in 0 until bills.length()) {
            val bill = bills.getJSONObject(i)
            bill.optString("asset_name", "").takeIf { it.isNotBlank() }?.let { assets += it }
            bill.optString("to_asset_name", "").takeIf { it.isNotBlank() }?.let { targets += it }
        }
        return when {
            targets.isNotEmpty() && assets.isNotEmpty() ->
                ctx.getString(R.string.account_transfer_label, assets.joinToString("、"), targets.joinToString("、"))
            assets.isNotEmpty() ->
                ctx.getString(R.string.account_label_fmt, assets.joinToString("、"))
            else ->
                ctx.getString(R.string.confirm_then_process)
        }
    }

    /** 兼容旧调用：dismiss 现在等价于彻底关闭（含小圆）。 */
    fun dismiss() = closePanel()

    /** 彻底关闭面板并移除小圆；X、ACTION_HIDE_OVERLAY、服务销毁都走这里。 */
    fun closePanel() {
        val wasActive = isPinned || miniBubble != null || currentDialog != null
        pendingCollapsedResult = null
        pendingCollapsedOnResult = null
        isPinned = false
        removeMiniBubble()
        analyzeJob?.cancel()
        analyzeJob = null
        val dialog = currentDialog
        if (dialog == null) {
            onPanelClosed?.invoke()
            if (wasActive) restartTapIfNeeded()
            return
        }
        dialog.dismiss()
        if (currentDialog === dialog) {
            // dismiss 没触发监听（窗口已失效）时兜底清理
            dialog.setOnDismissListener(null)
            currentDialog = null
            restartTapIfNeeded()
        }
        onPanelClosed?.invoke()
    }

    /** 识别结果已交付：pin 过的收成小圆方便继续记，未 pin 的维持原来的关闭行为。 */
    private fun finishAfterResult(notifyBadge: Boolean = true) {
        if (!isPinned) {
            dismiss()
            return
        }
        collapseToBubble(closeOverlayForm = false)
        resetPanelForNextEntry()
        if (notifyBadge) showBubbleBadge()
    }

    /** 单笔结果已交出去，输入框清空，下一次点小圆就是新的记账。 */
    private fun resetPanelForNextEntry() {
        updatePanelState(MODE_INPUT)
        currentDialog?.findViewById<EditText>(R.id.et_ai_input)?.setText("")
        tvRecordedTextPreview?.visibility = View.GONE
        btnExpandPreview?.visibility = View.GONE
        layoutMultiRecordActions?.visibility = View.GONE
    }

    // ── 常驻小圆 ────────────────────────────────────────────
    private fun canUseFloatingBubble(): Boolean {
        // 应用内弹窗（Activity 上下文）不缩小为桌面小圆，只在系统悬浮窗服务（OverlayService）中使用
        if (ctx is Activity) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(ctx)
    }

    private var dismissZoneView: View? = null
    private var isBubbleInDismissZone: Boolean = false

    private fun dropTargetCenterY(): Float =
        ctx.resources.displayMetrics.heightPixels - dp(84).toFloat()

    private fun insideDropTarget(
        params: WindowManager.LayoutParams,
        halfW: Int,
        halfH: Int,
    ): Boolean {
        val cx = params.x + params.width / 2f
        val cy = params.y + params.height / 2f
        return Math.abs(cx - ctx.resources.displayMetrics.widthPixels / 2f) <= halfW &&
            Math.abs(cy - dropTargetCenterY()) <= halfH
    }

    private fun ensureDismissZone(wm: WindowManager) {
        if (dismissZoneView != null) return
        val zone = TextView(ContextThemeWrapper(ctx.applicationContext, R.style.Theme_TapAccounting)).apply {
            text = "✕ 拖到此处关闭"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#D90F172A"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#52FFFFFF"))
            }
            elevation = dp(8).toFloat()
        }
        val params = WindowManager.LayoutParams().apply {
            width = dp(156)
            height = dp(40)
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(64)
            windowAnimations = 0
        }
        runCatching {
            zone.alpha = 0f
            wm.addView(zone, params)
            zone.animate().alpha(1f).setDuration(150L).start()
            dismissZoneView = zone
        }
    }

    private fun setDismissZoneHot(hot: Boolean) {
        val zone = dismissZoneView as? TextView ?: return
        zone.text = if (hot) "松手关闭" else "✕ 拖到此处关闭"
        zone.setTextColor(Color.parseColor(if (hot) "#FFFFFF" else "#E2E8F0"))
        zone.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(if (hot) "#F2EF4444" else "#D90F172A"))
            cornerRadius = dp(20).toFloat()
            setStroke(
                dp(1),
                Color.parseColor(if (hot) "#FFC9C9" else "#52FFFFFF"),
            )
        }
        zone.animate()
            .scaleX(if (hot) 1.06f else 1f)
            .scaleY(if (hot) 1.06f else 1f)
            .setDuration(120L)
            .start()
    }

    private fun removeDismissZone(wm: WindowManager) {
        val zone = dismissZoneView ?: return
        dismissZoneView = null
        isBubbleInDismissZone = false
        zone.animate().cancel()
        runCatching { wm.removeView(zone) }
    }

    private fun showMiniBubble(): Boolean {
        if (miniBubble != null) return true
        if (!canUseFloatingBubble()) return false
        val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        // 和待记胶囊同一套外观：深色药丸 + 左侧圆点 + 文案。
        val width = dp(MINI_BUBBLE_W_DP)
        val height = dp(MINI_BUBBLE_H_DP)
        val bubble = buildMiniBubble()

        val params = WindowManager.LayoutParams().apply {
            this.width = width
            this.height = height
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            // 固定 LEFT：x 就是屏幕左边缘像素值，避免 RTL 下 START 解析到右边
            gravity = Gravity.TOP or Gravity.LEFT
            x = initialBubbleX(width)
            y = initialBubbleY(height)
            windowAnimations = 0
        }
        bubble.setOnTouchListener { _, event -> handleBubbleTouch(bubble, params, wm, event) }
        try {
            wm.addView(bubble, params)
        } catch (e: Exception) {
            Logger.e(ctx, "AiAssistant", "showMiniBubble addView failed: ${e.message}")
            miniBubbleBadge = null
            return false
        }
        miniBubble = bubble
        miniBubbleWindowManager = wm
        bubble.alpha = 0f
        bubble.scaleX = 0.86f
        bubble.scaleY = 0.86f
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MINI_BUBBLE_ANIM_MS)
            .start()
        return true
    }

    private fun buildMiniBubble(): View {
        val appCtx = ctx.applicationContext
        val bubble = android.widget.FrameLayout(appCtx).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = "AI 记账，点按展开，向下拖动可关闭"
        }
        val pill = android.widget.LinearLayout(appCtx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#38FFFFFF"))
            }
            elevation = dp(8).toFloat()
        }
        val dot = View(appCtx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#7B61FF"))
            }
        }
        pill.addView(dot, android.widget.LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginEnd = dp(8)
        })
        val label = TextView(appCtx).apply {
            text = "AI 记账"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        pill.addView(label)
        val badge = View(appCtx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#34D399"))
                setStroke(dp(1), Color.parseColor("#0F172A"))
            }
            visibility = View.GONE
        }
        pill.addView(badge, android.widget.LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginStart = dp(6)
        })
        miniBubbleBadge = badge
        bubble.addView(
            pill,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36),
                Gravity.CENTER,
            ),
        )
        return bubble
    }

    private fun removeMiniBubble() {
        val bubble = miniBubble
        val wm = miniBubbleWindowManager
        miniBubble = null
        miniBubbleWindowManager = null
        miniBubbleBadge = null
        if (wm != null) {
            removeDismissZone(wm)
        }
        if (bubble != null) {
            bubble.animate().cancel()
            runCatching { wm?.removeView(bubble) }
        }
    }

    private fun showBubbleBadge() {
        miniBubbleBadge?.visibility = View.VISIBLE
    }

    private fun initialBubbleX(width: Int): Int {
        val margin = dp(MINI_BUBBLE_EDGE_MARGIN_DP)
        val right = (ctx.resources.displayMetrics.widthPixels - width - margin).coerceAtLeast(margin)
        return if (Prefs.getAiPanelBubbleSide(ctx) == Prefs.AI_BUBBLE_SIDE_LEFT) margin else right
    }

    private fun initialBubbleY(height: Int): Int {
        val maxY = maxBubbleY(height)
        val saved = Prefs.getAiPanelBubbleY(ctx)
        if (saved in 0..maxY) return saved
        return (ctx.resources.displayMetrics.heightPixels * MINI_BUBBLE_DEFAULT_Y_RATIO)
            .toInt()
            .coerceIn(0, maxY)
    }

    private fun maxBubbleX(width: Int): Int =
        (ctx.resources.displayMetrics.widthPixels - width).coerceAtLeast(0)

    private fun maxBubbleY(height: Int): Int =
        (ctx.resources.displayMetrics.heightPixels - height).coerceAtLeast(0)

    private fun handleBubbleTouch(
        bubble: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        event: MotionEvent
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bubbleTouchDownRawX = event.rawX
                bubbleTouchDownRawY = event.rawY
                bubbleTouchStartX = params.x
                bubbleTouchStartY = params.y
                bubbleTouchDownAtMs = SystemClock.uptimeMillis()
                bubbleTouchMoved = false
                bubbleDropShown = false
                bubbleDropArmed = false
                bubbleVelocity?.recycle()
                bubbleVelocity = android.view.VelocityTracker.obtain().also {
                    it.addMovement(event)
                }
                bubble.animate().cancel()
                bubble.animate().scaleX(0.94f).scaleY(0.94f)
                    .setDuration(90L).start()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                bubbleVelocity?.addMovement(event)
                val dx = event.rawX - bubbleTouchDownRawX
                val dy = event.rawY - bubbleTouchDownRawY
                if (!bubbleTouchMoved) {
                    val slop = ViewConfiguration.get(ctx).scaledTouchSlop
                    bubbleTouchMoved = Math.hypot(dx.toDouble(), dy.toDouble()) > slop
                    if (bubbleTouchMoved) {
                        bubble.animate().cancel()
                        bubble.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                if (bubbleTouchMoved) {
                    val margin = dp(MINI_BUBBLE_EDGE_MARGIN_DP)
                    params.x = (bubbleTouchStartX + dx.toInt())
                        .coerceIn(margin, (maxBubbleX(params.width) - margin).coerceAtLeast(margin))
                    params.y = (bubbleTouchStartY + dy.toInt())
                        .coerceIn(dp(24), (maxBubbleY(params.height) - dp(24)).coerceAtLeast(dp(24)))
                    runCatching { wm.updateViewLayout(bubble, params) }

                    val near = params.y - bubbleTouchStartY > dp(16) ||
                        insideDropTarget(params, dp(98) + dp(6), dp(46) + dp(6))
                    if (near != bubbleDropShown) {
                        bubbleDropShown = near
                        if (near) ensureDismissZone(wm) else removeDismissZone(wm)
                    }
                    if (!near) {
                        if (bubbleDropArmed) {
                            bubbleDropArmed = false
                            setDismissZoneHot(false)
                        }
                        bubble.scaleX = 1f
                        bubble.scaleY = 1f
                    } else {
                        val armed = if (bubbleDropArmed) {
                            insideDropTarget(params, dp(98), dp(46))
                        } else {
                            insideDropTarget(params, dp(88), dp(34))
                        }
                        if (armed != bubbleDropArmed) {
                            bubbleDropArmed = armed
                            setDismissZoneHot(armed)
                            bubble.performHapticFeedback(
                                if (armed) HapticFeedbackConstants.LONG_PRESS
                                else HapticFeedbackConstants.CONTEXT_CLICK,
                            )
                        }
                        bubble.scaleX = if (bubbleDropArmed) 0.84f else 0.94f
                        bubble.scaleY = bubble.scaleX
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val tracker = bubbleVelocity
                bubbleVelocity = null
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                val releasedArmed = bubbleTouchMoved && bubbleDropShown &&
                    insideDropTarget(params, dp(98), dp(46))
                removeDismissZone(wm)
                bubbleDropShown = false
                bubbleDropArmed = false
                if (!bubbleTouchMoved) {
                    if (SystemClock.uptimeMillis() - bubbleTouchDownAtMs <
                        ViewConfiguration.getLongPressTimeout()
                    ) {
                        // 轻点展开；长按不当作关闭
                        expandFromBubble()
                    }
                    return true
                }
                if (releasedArmed) {
                    val targetX = ctx.resources.displayMetrics.widthPixels / 2f
                    val targetY = dropTargetCenterY()
                    val tx = targetX - (params.x + params.width / 2f)
                    val ty = targetY - (params.y + params.height / 2f)
                    bubble.setOnTouchListener { _, _ -> true }
                    bubble.animate().cancel()
                    bubble.animate()
                        .translationX(tx)
                        .translationY(ty)
                        .scaleX(0.18f)
                        .scaleY(0.18f)
                        .alpha(0f)
                        .setDuration(170L)
                        .withEndAction { closePanel() }
                        .start()
                } else {
                    snapBubbleToEdge(bubble, params, wm, vx)
                    bubble.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(140L).start()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                bubbleVelocity?.recycle()
                bubbleVelocity = null
                removeDismissZone(wm)
                bubbleDropShown = false
                bubbleDropArmed = false
                bubble.animate().scaleX(1f).scaleY(1f).alpha(1f).start()
                return true
            }
        }
        return false
    }

    private fun snapBubbleToEdge(
        bubble: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        velocityX: Float = 0f,
    ) {
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val margin = dp(MINI_BUBBLE_EDGE_MARGIN_DP)
        val fling = dp(420).toFloat()
        val side = when {
            velocityX < -fling -> Prefs.AI_BUBBLE_SIDE_LEFT
            velocityX > fling -> Prefs.AI_BUBBLE_SIDE_RIGHT
            params.x + params.width / 2 < screenWidth / 2 -> Prefs.AI_BUBBLE_SIDE_LEFT
            else -> Prefs.AI_BUBBLE_SIDE_RIGHT
        }
        params.x = if (side == Prefs.AI_BUBBLE_SIDE_LEFT) {
            margin
        } else {
            (screenWidth - params.width - margin).coerceAtLeast(margin)
        }
        params.y = params.y.coerceIn(dp(24), (maxBubbleY(params.height) - dp(24)).coerceAtLeast(dp(24)))
        runCatching { wm.updateViewLayout(bubble, params) }
        Prefs.setAiPanelBubbleSide(ctx, side)
        Prefs.setAiPanelBubbleY(ctx, params.y)
    }

    // ── 面板下滑收起 ────────────────────────────────────────

    private fun installPanelDragGrabber(dialog: AlertDialog, content: View) {
        val grabber = content.findViewById<View>(R.id.ai_panel_grabber) ?: return
        if (!canUseFloatingBubble()) {
            // Activity 内没有悬浮窗权限/走普通对话框时，不提供「收成小圈」手势。
            grabber.visibility = View.GONE
            return
        }
        val win = dialog.window ?: return
        var downY = 0f
        var baseY = 0
        var armed = false
        var tracker: VelocityTracker? = null
        grabber.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    baseY = win.attributes?.y ?: 0
                    armed = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dy = (event.rawY - downY).coerceAtLeast(0f)
                    // 过 64dp 后只走 45% 位移，像拉皮筋。
                    val over = (dy - dp(64)).coerceAtLeast(0f)
                    val shown = dy - over * 0.55f
                    moveDialogWindow(win, (baseY - shown.toInt()).coerceAtLeast(0))
                    content.alpha = (1f - shown / (baseY.coerceAtLeast(dp(140)) * 1.1f))
                        .coerceIn(0.6f, 1f)
                    val nextArmed = dy >= dp(56)
                    if (nextArmed != armed) {
                        armed = nextArmed
                        grabber.performHapticFeedback(
                            if (armed) HapticFeedbackConstants.LONG_PRESS
                            else HapticFeedbackConstants.CONTEXT_CLICK,
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val t = tracker
                    tracker = null
                    t?.addMovement(event)
                    t?.computeCurrentVelocity(1000)
                    val vy = t?.yVelocity ?: 0f
                    t?.recycle()
                    val dy = event.rawY - downY
                    val collapse = event.actionMasked == MotionEvent.ACTION_UP &&
                        (dy > dp(56) || (vy > dp(1100) && dy > dp(24)))
                    if (collapse) {
                        animateDialogCollapse(content, win, baseY)
                    } else {
                        animateDialogY(win, baseY)
                        content.animate().alpha(1f).setDuration(160L).start()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun moveDialogWindow(win: Window, y: Int) {
        val attrs = win.attributes ?: return
        attrs.y = y
        runCatching { win.attributes = attrs }
    }

    private fun animateDialogY(win: Window, targetY: Int) {
        val start = win.attributes?.y ?: return
        ValueAnimator.ofInt(start, targetY).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { moveDialogWindow(win, it.animatedValue as Int) }
            start()
        }
    }

    private fun animateDialogCollapse(content: View, win: Window, restoreY: Int) {
        val start = win.attributes?.y ?: restoreY
        val anim = ValueAnimator.ofInt(start, 0)
        anim.duration = 160L
        anim.interpolator = AccelerateInterpolator()
        anim.addUpdateListener { moveDialogWindow(win, it.animatedValue as Int) }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // 先复位窗口和透明度，下次展开还回到原来的位置。
                moveDialogWindow(win, restoreY)
                content.alpha = 1f
                collapseToBubble()
            }
        })
        content.animate().alpha(0.35f).setDuration(160L).start()
        anim.start()
    }

    private fun hideIme(dialog: AlertDialog) {
        val token = dialog.window?.decorView?.windowToken ?: return
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        runCatching { imm.hideSoftInputFromWindow(token, 0) }
    }

    private fun dp(value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    fun getCurrentInputText(): String {
        val dialog = currentDialog ?: return ""
        val view = dialog.findViewById<View>(android.R.id.content) ?: return ""
        val etInput = view.findViewById<EditText>(R.id.et_ai_input) ?: return ""
        return etInput.text.toString()
    }

    fun analyzeImage(imageUri: Uri, onResult: (JSONObject) -> Unit) {
        analyzeImages(listOf(imageUri), onResult)
    }

    /** 截屏记账入口：默认直出 JSON；开启「截屏沿用图片记账」时走图片记账流程 */
    fun analyzeScreenshot(
        imageUri: Uri,
        onResult: (JSONObject) -> Unit,
        onAbandoned: (() -> Unit)? = null
    ) {
        if (!ensureOverlayPermission()) {
            onAbandoned?.invoke()
            return
        }
        captureMode = false
        lastReceiptImageUri = imageUri
        if (Prefs.isScreenAccountingUseImageFlow(ctx)) {
            analyzeImages(
                imageUris = listOf(imageUri),
                onResult = onResult,
                onAbandoned = onAbandoned,
                sourceKind = "screen_capture"
            )
        } else {
            dispatchImageAccounting(
                imageUris = listOf(imageUri),
                supplementText = "",
                naturalLanguage = false,
                onResult = onResult,
                sourceKind = "screen_capture",
                quickScreenMode = true,
                onAbandoned = onAbandoned
            )
        }
    }

    fun analyzeImages(
        imageUris: List<Uri>,
        onResult: (JSONObject) -> Unit,
        onAbandoned: (() -> Unit)? = null,
        sourceKind: String = "receipt_image",
        captureMode: Boolean = false,
        expectedTotalCents: Long? = null,
        expectedCurrency: String = "CNY"
    ) {
        if (!ensureOverlayPermission()) {
            onAbandoned?.invoke()
            return
        }
        if (imageUris.isEmpty()) {
            onAbandoned?.invoke()
            return
        }
        lastReceiptImageUri = imageUris.first()
        this.captureMode = captureMode
        this.captureExpectedTotalCents = if (captureMode) expectedTotalCents else null
        this.captureExpectedCurrency = expectedCurrency.ifBlank { "CNY" }

        // capture 模式固定直出 JSON：不走“先核对小票文字”或自然语言草稿，结果页只保留确认按钮。
        val needSupplement = Prefs.isReceiptImageDraftConfirmEnabled(ctx) && !captureMode
        val naturalLanguage = Prefs.isImageAccountingNaturalLanguage(ctx) && !captureMode

        if (needSupplement) {
            showSupplementInput(imageUris, naturalLanguage, onResult, onAbandoned, sourceKind)
        } else {
            dispatchImageAccounting(
                imageUris = imageUris,
                supplementText = "",
                naturalLanguage = naturalLanguage,
                onResult = onResult,
                sourceKind = sourceKind,
                onAbandoned = onAbandoned
            )
        }
    }

    /**
     * 弹出补充输入框，用户输入补充文本后分发到对应的图片记账路径。
     */
    private fun showSupplementInput(
        imageUris: List<Uri>,
        naturalLanguage: Boolean,
        onResult: (JSONObject) -> Unit,
        onAbandoned: (() -> Unit)? = null,
        sourceKind: String = "receipt_image"
    ) {
        val (dialog, view) = createDialog(cancelable = true)
        currentDialog = dialog
        var continued = false

        val layoutInput = view.findViewById<View>(R.id.layout_input)
        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val layoutResult = view.findViewById<View>(R.id.layout_result)
        val btnClose = view.findViewById<View>(R.id.btn_close)
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        val btnIdentify = view.findViewById<TextView>(R.id.btn_dialog_identify)

        layoutInput.visibility = View.VISIBLE
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.GONE
        btnClose.visibility = View.VISIBLE
        tvRecordedTextPreview?.visibility = View.GONE
        layoutMultiRecordActions?.visibility = View.GONE
        btnExpandPreview?.visibility = View.GONE

        etInput.hint = ctx.getString(R.string.hint_supplement_info)
        etInput.setText("")
        btnIdentify.text = ctx.getString(R.string.start_recognition)
        btnIdentify.setOnClickListener {
            continued = true
            val supplement = etInput.text.toString().trim()
            dispatchImageAccounting(
                imageUris = imageUris,
                supplementText = supplement,
                naturalLanguage = naturalLanguage,
                onResult = onResult,
                sourceKind = sourceKind,
                onAbandoned = onAbandoned
            )
        }
        btnClose.setOnClickListener { dismiss() }
        installDialogDismissHandler(dialog) {
            if (!continued) onAbandoned?.invoke()
        }

        dialog.show()
    }

    /**
     * 根据输出模式分发到对应的图片记账路径。
     */
    private fun dispatchImageAccounting(
        imageUris: List<Uri>,
        supplementText: String,
        naturalLanguage: Boolean,
        onResult: (JSONObject) -> Unit,
        sourceKind: String = "receipt_image",
        quickScreenMode: Boolean = false,
        onAbandoned: (() -> Unit)? = null
    ) {
        // 安全关闭 showSupplementInput 的旧弹窗：
        // 先移除 dismiss 监听，避免它将 currentDialog 置空，
        // 然后 dismiss 旧弹窗，最后清空引用。
        currentDialog?.setOnDismissListener(null)
        currentDialog?.dismiss()
        currentDialog = null
        isPinned = false
        removeMiniBubble()
        if (naturalLanguage) {
            analyzeImageNaturalLanguage(imageUris, supplementText, onResult, onAbandoned)
        } else {
            analyzeImageDirect(
                imageUris = imageUris,
                supplementText = supplementText,
                onResult = onResult,
                sourceKind = sourceKind,
                quickScreenMode = quickScreenMode,
                onAbandoned = onAbandoned
            )
        }
    }

    private fun analyzeImageDirect(
        imageUris: List<Uri>,
        supplementText: String,
        onResult: (JSONObject) -> Unit,
        sourceKind: String = "receipt_image",
        quickScreenMode: Boolean = false,
        onAbandoned: (() -> Unit)? = null
    ) {
        if (!ensureOverlayPermission()) {
            onAbandoned?.invoke()
            return
        }
        lastReceiptImageUri = imageUris.first()

        val (dialog, view) = createDialog(cancelable = false)
        currentDialog = dialog
        bindLoadingPanelViews(view)
        currentHideStreamText = true
        var deliveredResult = false

        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        disableSelectionActionModeIfService(etInput)
        view.findViewById<View>(R.id.btn_dialog_voice)?.let { voiceInputBtnSetup?.invoke(it) }

        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.VISIBLE
        tvRecordedTextPreview?.visibility = View.GONE
        layoutMultiRecordActions?.visibility = View.GONE
        btnExpandPreview?.visibility = View.GONE
        progressAiLoading?.visibility = View.VISIBLE
        tvThinkingLog?.text = ctx.getString(R.string.generating_from_image)
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
        dialog.setCancelable(true)
        panelCancelable = true

        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            analyzeJob?.cancel()
            dismiss()
        }
        installDialogDismissHandler(dialog) {
            if (!deliveredResult) onAbandoned?.invoke()
        }

        analyzeJob = scope.launch {
            try {
                val payloads = imageUris.mapNotNull { uri ->
                    ReceiptImageInputHelper.readImagePayload(ctx, uri)
                }
                if (payloads.isEmpty()) throw IllegalArgumentException("无法读取图片")
                val streamedRaw = StringBuilder()
                val streamState = OverlayStreamUiState()
                withContext(Dispatchers.Main) {
                    updateLoadingText(ctx.getString(R.string.analyzing_image_transactions))
                }
                val result = if (payloads.size == 1) {
                    AIService.analyzeScreenAccountingByImage(
                        ctx = ctx,
                        imageBase64 = payloads[0].base64,
                        mimeType = payloads[0].mime,
                        sourceKind = sourceKind,
                        supplementText = supplementText,
                        quickScreenMode = quickScreenMode,
                        onProgress = { status ->
                            Handler(Looper.getMainLooper()).post {
                                Logger.d(ctx, "StreamPreview", "onProgress: status=${status.take(60)}, dialogShowing=${currentDialog?.isShowing}")
                                if (currentDialog?.isShowing != true) return@post
                                if (status.startsWith("AI_STREAM_TEXT::")) {
                                    applyOverlayStreamProgress(status, streamedRaw, streamState)
                                } else if (!streamState.started) {
                                    updateLoadingText(ctx.getString(R.string.analyzing_image_transactions))
                                }
                            }
                        }
                    )
                } else {
                    AIService.analyzeScreenAccountingByImages(
                        ctx = ctx,
                        images = payloads.map { it.base64 to it.mime },
                        sourceKind = sourceKind,
                        supplementText = supplementText,
                        quickScreenMode = quickScreenMode,
                        onProgress = { status ->
                            Handler(Looper.getMainLooper()).post {
                                if (currentDialog?.isShowing != true) return@post
                                if (status.startsWith("AI_STREAM_TEXT::")) {
                                    applyOverlayStreamProgress(status, streamedRaw, streamState)
                                } else if (!streamState.started) {
                                    updateLoadingText(ctx.getString(R.string.analyzing_image_transactions))
                                }
                            }
                        }
                    )
                }
                withContext(Dispatchers.Main) {
                    if (result == null) {
                        Utils.toast(ctx, ctx.getString(R.string.toast_parse_failed))
                        dismiss()
                        return@withContext
                    }
                    if (result.optBoolean("no_bill", false)) {
                        Utils.toast(ctx, result.optString("reply", ctx.getString(R.string.toast_no_bill_found)))
                        dismiss()
                        return@withContext
                    }
                    presentAccountingResult(result, supplementText) { json ->
                        deliveredResult = true
                        onResult(json)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, ctx.getString(R.string.toast_canceled))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, ctx.getString(R.string.toast_image_parse_failed))
                }
            }
        }
    }

    private fun analyzeImageNaturalLanguage(
        imageUris: List<Uri>,
        supplementText: String,
        onResult: (JSONObject) -> Unit,
        onAbandoned: (() -> Unit)? = null
    ) {
        if (!ensureOverlayPermission()) {
            onAbandoned?.invoke()
            return
        }
        lastReceiptImageUri = imageUris.first()

        val (dialog, view) = createDialog(cancelable = false)
        currentDialog = dialog
        bindLoadingPanelViews(view)
        var continued = false

        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        disableSelectionActionModeIfService(etInput)
        view.findViewById<View>(R.id.btn_dialog_voice)?.let { voiceInputBtnSetup?.invoke(it) }

        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.VISIBLE
        tvRecordedTextPreview?.visibility = View.GONE
        layoutMultiRecordActions?.visibility = View.GONE
        btnExpandPreview?.visibility = View.GONE
        progressAiLoading?.visibility = View.VISIBLE
        tvThinkingLog?.text = ctx.getString(R.string.sending_to_vision)
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))

        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            analyzeJob?.cancel()
            dismiss()
        }
        installDialogDismissHandler(dialog) {
            if (!continued) onAbandoned?.invoke()
        }

        analyzeJob = scope.launch {
            try {
                val summary = ReceiptOcrHelper.analyzeImagesByMultimodal(ctx, imageUris, supplementText) { progressMsg ->
                    Handler(Looper.getMainLooper()).post {
                        tvThinkingLog?.text = progressMsg
                    }
                }
                withContext(Dispatchers.Main) {
                    continued = true
                    showReceiptSummary(summary, supplementText, onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, ctx.getString(R.string.toast_canceled))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, ctx.getString(R.string.toast_image_parse_failed))
                }
            }
        }
    }

    private fun showReceiptSummary(summary: String, supplementText: String, onResult: (JSONObject) -> Unit) {
        currentHideStreamText = true  // 确保走流式预览路径
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutInput = view.findViewById<View>(R.id.layout_input)
        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val btnClose = view.findViewById<View>(R.id.btn_close)
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        val btnIdentify = view.findViewById<TextView>(R.id.btn_dialog_identify)
        val btnRetryVision = view.findViewById<View>(R.id.btn_dialog_retry_vision)

        layoutLoading.visibility = View.GONE
        layoutInput.visibility = View.VISIBLE
        btnClose.visibility = View.VISIBLE
        dialog.setCancelable(true)
        panelCancelable = true

        val mergedSummary = ReceiptImageInputHelper.mergeSupplementWithSummary(summary, supplementText)
        etInput.setText(mergedSummary)
        etInput.setSelection(mergedSummary.length)
        etInput.hint = ctx.getString(R.string.verify_receipt_hint)
        // 启用滚动，配合 maxHeight 限制高度
        etInput.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()

        btnIdentify.text = ctx.getString(R.string.generate_bill)
        btnIdentify.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Utils.toast(ctx, ctx.getString(R.string.toast_input_content))
                return@setOnClickListener
            }
            bindLoadingPanelViews(view)
            updatePanelState(MODE_LOADING, ctx.getString(R.string.generating_structured_bill))
            val accountingInput = ReceiptImageInputHelper.buildAccountingInputFromImageDraft(
                text,
                supplementText
            )
            startAnalysis(
                text = accountingInput,
                onResult = onResult,
                visualReviewSource = "receipt_image",
                visualDraftText = accountingInput
            )
        }

        btnRetryVision?.visibility = View.GONE
        // 收起状态下图片转写完成，也需要在小圆上提示可以继续确认识别
        if (miniBubble != null) showBubbleBadge()
    }

    private fun retryReceiptWithVision(imageUri: Uri, onResult: (JSONObject) -> Unit) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.GONE
        tvRecordedTextPreview?.visibility = View.GONE
        tvThinkingLog?.text = ctx.getString(R.string.retrying_with_vision)
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
        dialog.setCancelable(false)
        panelCancelable = false

        analyzeJob?.cancel()
        analyzeJob = scope.launch {
            try {
                val summary = ReceiptOcrHelper.analyzeImageByMultimodal(ctx, imageUri) { progressMsg ->
                    Handler(Looper.getMainLooper()).post {
                        tvThinkingLog?.text = progressMsg
                    }
                }
                withContext(Dispatchers.Main) {
                    showReceiptSummary(summary, "", onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, ctx.getString(R.string.toast_canceled))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    view.findViewById<View>(R.id.layout_loading)?.visibility = View.GONE
                    view.findViewById<View>(R.id.layout_input)?.visibility = View.VISIBLE
                    view.findViewById<View>(R.id.btn_close)?.visibility = View.VISIBLE
                    dialog.setCancelable(true)
                    panelCancelable = true
                    Utils.toast(ctx, ctx.getString(R.string.toast_image_recognition_failed))
                }
            }
        }
    }

    /** 统一 dismiss：业务回调 + 移除小圆 + 恢复双击悬浮 + 清空引用（后设的会覆盖 createDialog 默认）。 */
    private fun installDialogDismissHandler(dialog: AlertDialog, onDismiss: (() -> Unit)? = null) {
        dialog.setOnDismissListener {
            onDismiss?.invoke()
            pendingCollapsedResult = null
            pendingCollapsedOnResult = null
            removeMiniBubble()
            isPinned = false
            restartTapIfNeeded()
            currentDialog = null
            onPanelClosed?.invoke()
        }
    }

    private fun createDialog(cancelable: Boolean): Pair<AlertDialog, View> {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_dialog_ai_input, null)

        // 任何新建面板都从「未 pin、无小圆」开始，避免旧小圆和新面板同屏
        pendingCollapsedResult = null
        pendingCollapsedOnResult = null
        removeMiniBubble()
        isPinned = false
        panelCancelable = cancelable

        val dialog = AlertDialog.Builder(themeContext)
            .setView(view)
            .setCancelable(cancelable)
            .create()
        // 点外面完全由 handleDialogTouch 接管：pin 时收小圆，未 pin 时才关闭
        dialog.setCanceledOnTouchOutside(false)

        installDialogDismissHandler(dialog)

        view.findViewById<View>(R.id.btn_pin)?.apply {
            visibility = if (canUseFloatingBubble()) View.VISIBLE else View.GONE
            setOnClickListener { collapseToBubble() }
        }
        // 返回键：pin 过就收小圆，否则交回系统按 cancelable 处理
        dialog.setOnKeyListener { _, keyCode, event -> handleDialogKey(keyCode, event) }

        dialog.window?.apply {
            val useOverlayWindow =
                ctx !is Activity &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx))
            if (useOverlayWindow) {
                setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                )
            }
            setBackgroundDrawableResource(android.R.color.transparent)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setWindowAnimations(R.style.Animation_TapAccounting_DialogSoft)
            setDimAmount(0.34f)
            setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            attributes.y = (72 * ctx.resources.displayMetrics.density).toInt() + navigationBarHeight()
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        try {
            dialog.show()
        } catch (_: BadTokenException) {
            return dialog to view
        } catch (_: IllegalStateException) {
            return dialog to view
        }
        dialog.window?.let { win ->
            val dm = ctx.resources.displayMetrics
            val maxCardWidth = (360 * dm.density).toInt()
            val widthPx = kotlin.math.min((dm.widthPixels * 0.9f).toInt(), maxCardWidth)
            win.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        // 点外面：只在这里统一判定，键盘弹出/收起引起的窗口变化不参与
        dialog.window?.decorView?.setOnTouchListener { decor, event -> handleDialogTouch(decor, event) }
        // 窗口已经 show，grabber 才能拿到有效的 attributes。
        installPanelDragGrabber(dialog, view)

        return dialog to view
    }

    private fun handleDialogKey(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false
        // 未 pin 时把返回键交回系统，保持原来的关闭行为
        if (!isPinned) return false
        if (event.action == android.view.KeyEvent.ACTION_UP && miniBubble == null) {
            collapseToBubble()
        }
        // DOWN/UP 都吃掉，避免按键分发状态残留
        return true
    }

    /**
     * 面板外点击判定。
     *
     * 不监听窗口焦点：输入法弹出/收起同样会改变窗口大小和焦点，
     * 只有真正的窗外触摸（ACTION_OUTSIDE 或落在 decor 外的 DOWN）才收起/关闭。
     */
    private fun handleDialogTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                onPanelOutsideTouch()
                return true
            }

            MotionEvent.ACTION_DOWN -> {
                outsideTouchDown = !isPointInside(view, event.x, event.y)
                return outsideTouchDown
            }

            MotionEvent.ACTION_MOVE -> return outsideTouchDown

            MotionEvent.ACTION_UP -> {
                val wasOutside = outsideTouchDown
                outsideTouchDown = false
                if (wasOutside) {
                    onPanelOutsideTouch()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                outsideTouchDown = false
                return false
            }
        }
        return false
    }

    private fun isPointInside(view: View, x: Float, y: Float): Boolean {
        if (view.width <= 0 || view.height <= 0) return true
        return x >= 0f && y >= 0f && x <= view.width && y <= view.height
    }

    private fun onPanelOutsideTouch() {
        if (miniBubble != null) return
        val dialog = currentDialog ?: return
        if (isPinned) {
            collapseToBubble()
        } else if (panelCancelable && dialog.isShowing) {
            dismiss()
        }
    }

    private fun navigationBarHeight(): Int {
        val res = ctx.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    private fun stopTapIfNeeded() {
        if (!Prefs.isDoubleTapEnabled(ctx)) return
        val stopIntent = Intent(ctx, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_DOUBLE_TAP
        }
        try {
            OverlayService.startCompat(ctx, stopIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartTapIfNeeded() {
        if (!Prefs.isDoubleTapEnabled(ctx)) return
        val startIntent = Intent(ctx, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_DOUBLE_TAP
        }
        try {
            OverlayService.startCompat(ctx, startIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disableSelectionActionModeIfService(etInput: EditText?) {
        if (ctx is Activity || etInput == null) return
        val blankCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean = false

            override fun onPrepareActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode?,
                item: android.view.MenuItem?
            ): Boolean = false

            override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
        }
        etInput.customInsertionActionModeCallback = blankCallback
        etInput.customSelectionActionModeCallback = blankCallback
    }

    private fun ensureOverlayPermission(): Boolean {
        if (ctx is Activity) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(ctx)) return true

        Utils.toast(ctx, ctx.getString(R.string.toast_overlay_permission))
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
        return false
    }
}
