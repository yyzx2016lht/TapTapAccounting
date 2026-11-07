package com.taostudio.tapaccounting.capturebridge

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.ImagePickerActivity
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.Utils
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.bridge.RuleLearningManager
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.ImportItemStatus
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.logic.AccountingFormController
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * 敲敲记账悬浮窗内的「待记无感联动控制器」：
 * - 未安装插件或无待记时：完全隐身（View.GONE），对普通用户 0 影响；
 * - 有待记时：自动将最新一笔带入记账悬浮窗，顶部用细上下文条展示待记笔数与当前一笔；
 * - 支持在同一个悬浮窗内切换「待记 / 新账」两种草稿，并展开多笔速清面板。
 */
class OverlayPendingCaptureController(
    private val ctx: Context,
    private val rootFloatingView: View,
    private val formController: AccountingFormController,
    private val onCloseOverlay: (isSaved: Boolean) -> Unit,
    private val onPickerWindowVisibility: (hidden: Boolean) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private val formBody: View? = rootFloatingView.findViewById(R.id.layout_form_body)
    private val amountRow: View? = rootFloatingView.findViewById(R.id.layout_amount_row)
    private val contextContainer: View? = rootFloatingView.findViewById(R.id.layout_capture_context)
    private val capturePanel: LinearLayout? = rootFloatingView.findViewById(R.id.layout_capture_panel)
    private val sourceDot: View? = rootFloatingView.findViewById(R.id.view_capture_source_dot)
    private val tvCaptureCount: TextView? = rootFloatingView.findViewById(R.id.tv_capture_count)
    private val tvCaptureSummary: TextView? = rootFloatingView.findViewById(R.id.tv_capture_summary)
    private val btnModePending: TextView? = rootFloatingView.findViewById(R.id.btn_capture_mode_pending)
    private val btnModeNew: TextView? = rootFloatingView.findViewById(R.id.btn_capture_mode_new)

    private val splitToggleRow: View? = rootFloatingView.findViewById(R.id.layout_split_toggle_row)
    private val switchSplitBill: androidx.appcompat.widget.SwitchCompat? = rootFloatingView.findViewById(R.id.switch_split_bill)
    private val tvSplitHint: TextView? = rootFloatingView.findViewById(R.id.tv_split_hint)
    private val btnSplitPreview: TextView? = rootFloatingView.findViewById(R.id.btn_split_preview)
    private val tvCategory: TextView? = rootFloatingView.findViewById(R.id.tv_category)
    private val layoutCategory: View? = rootFloatingView.findViewById(R.id.layout_category)
    private val tvCategorySourceBadge: View? = rootFloatingView.findViewById(R.id.tv_category_source_badge)
    private val etRemark: EditText? = rootFloatingView.findViewById(R.id.et_remark)
    private val etAmount: EditText? = rootFloatingView.findViewById(R.id.et_amount)
    private val btnSave: TextView? = rootFloatingView.findViewById(R.id.btn_save)
    private val tvSaveDisabledHint: TextView? = rootFloatingView.findViewById(R.id.tv_save_disabled_hint)

    private var isSplitMode: Boolean = false
    private var previousCategoryText: String? = null
    private val splitItemIds = mutableSetOf<String>()

    private enum class FormMode { PENDING, NEW_BILL }

    private var mode: FormMode = FormMode.NEW_BILL
    private var pendingItems: MutableList<CapturePluginClient.PendingCaptureItem> = mutableListOf()
    private var currentIndex: Int = 0
    private var isBatchDrawerOpen: Boolean = false
    private var isBusy: Boolean = false
    private val noteSyncJobs = mutableMapOf<String, Job>()
    private val batchRowViews = mutableMapOf<String, View>()
    private val batchCategoryChips = mutableMapOf<String, TextView>()
    private var batchCommitButton: TextView? = null
    private var batchSubHint: TextView? = null
    private var imeWasVisible = false

    /** 待记模式草稿（切到新账/批量面板时暂存）。 */
    private var pendingDraft: AccountingFormController.DraftSnapshot? = null
    private var pendingDraftItemId: String? = null
    private var hasPendingDraft: Boolean = false
    /** 新账模式草稿（切回待记时暂存）。 */
    private var newBillDraft: AccountingFormController.DraftSnapshot? = null

    init {
        tvCaptureCount?.setOnClickListener { openBatchPanelFromBar() }
        btnModePending?.setOnClickListener { switchToPendingMode() }
        btnModeNew?.setOnClickListener { switchToNewBillMode() }
        formController.setOnRemarkChangedListener { note -> onSingleRemarkChanged(note) }
        formController.onImeClosed = { onRemarkImeClosed() }
        formController.setOnCategoryChangedListener { categoryName -> onSingleCategoryChanged(categoryName) }

        switchSplitBill?.setOnCheckedChangeListener { _, isChecked ->
            onSplitToggleChanged(isChecked)
        }
        btnSplitPreview?.setOnClickListener {
            triggerSplitPreview()
        }
        layoutCategory?.setOnTouchListener { _, event ->
            if (isSplitMode && event.action == MotionEvent.ACTION_UP) {
                Utils.toast(ctx, "已开启拆分记账，分类将在拆单预览中逐条设置")
                val note = etRemark?.text?.toString()?.trim().orEmpty()
                if (note.isNotBlank()) {
                    triggerSplitPreview()
                } else {
                    etRemark?.requestFocus()
                }
                true
            } else {
                false
            }
        }
        btnSave?.setOnTouchListener { _, event ->
            if (isSplitMode && event.action == MotionEvent.ACTION_UP) {
                triggerSplitPreview()
                true
            } else {
                false
            }
        }

        etRemark?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoClassifySinglePending()
        }
        installImeHideWatcher()
        renderContextBar()
    }

    /**
     * 当悬浮窗打开时调用。
     * @param hasExplicitPrefill 调用方已经传了特定 prefill（比如从插件点“悬浮窗细改”跳过来），此时不再重复拉取覆盖。
     * @param startInBatchMode 是否直接展开多笔批量速清面板。
     */
    fun loadIfNeeded(hasExplicitPrefill: Boolean, startInBatchMode: Boolean = false, splitRequested: Boolean = false) {
        if (hasExplicitPrefill) {
            if (splitRequested && switchSplitBill != null) {
                val cid = formController.currentCaptureId()
                if (cid != null) splitItemIds.add(cid)
                splitToggleRow?.visibility = View.VISIBLE
                switchSplitBill.isChecked = true
            }
            return
        }
        if (!CapturePluginClient.isPluginInstalled(ctx)) return

        scope.launch {
            val items = CapturePluginClient.listPendingCaptures(ctx, limit = 30)
            if (items.isEmpty()) {
                pendingItems.clear()
                clearPendingContext()
                return@launch
            }
            pendingItems = items.toMutableList()
            currentIndex = 0
            mode = FormMode.PENDING
            hasPendingDraft = false
            pendingDraft = null
            pendingDraftItemId = null
            if (startInBatchMode && pendingItems.size > 1) {
                showContextBarAnimated()
                openBatchDrawer()
            } else {
                applyItemToSingleForm(0)
            }
            applyAiEntriesForMode()
        }
    }

    fun destroy() {
        formController.setOnRemarkChangedListener(null)
        formController.onImeClosed = null
        formController.setOnCategoryChangedListener(null)
        layoutCategory?.setOnTouchListener(null)
        btnSave?.setOnTouchListener(null)
        noteSyncJobs.values.forEach { it.cancel() }
        scope.cancel()
    }

    private fun applyItemToSingleForm(index: Int) {
        if (pendingItems.isEmpty()) {
            clearPendingContext()
            return
        }
        formController.hideAmountKeypad()
        currentIndex = index.coerceIn(0, pendingItems.lastIndex)
        isBatchDrawerOpen = false
        mode = FormMode.PENDING
        pendingDraft = null
        pendingDraftItemId = null
        hasPendingDraft = true
        hideBatchPanel()
        setSingleFormVisible(true)
        showContextBarAnimated()

        val item = pendingItems[currentIndex]
        val prefill = CapturePluginClient.toPrefillJson(item)
        formController.updateCaptureContext(item.id, UUID.randomUUID().toString())
        formController.fillDataToUi(prefill, showToast = false, forceMultiMode = false)

        val canSplit = (item.billType == LocalBillType.TYPE_EXPENSE || item.billType == LocalBillType.TYPE_INCOME)
        if (canSplit) {
            splitToggleRow?.visibility = View.VISIBLE
            val isSplit = item.id in splitItemIds
            switchSplitBill?.isChecked = isSplit
            if (isSplit) {
                onSplitToggleChanged(true)
            }
        } else {
            splitToggleRow?.visibility = View.GONE
            switchSplitBill?.isChecked = false
            if (isSplitMode) {
                onSplitToggleChanged(false)
            }
        }
        renderContextBar()
    }

    /** 待记全部处理完后收起上下文条，回到普通新账。 */
    private fun clearPendingContext() {
        isBatchDrawerOpen = false
        mode = FormMode.NEW_BILL
        hasPendingDraft = false
        pendingDraft = null
        pendingDraftItemId = null
        currentIndex = 0
        noteSyncJobs.values.forEach { it.cancel() }
        noteSyncJobs.clear()
        splitToggleRow?.visibility = View.GONE
        if (isSplitMode) {
            onSplitToggleChanged(false)
            switchSplitBill?.isChecked = false
        }
        contextContainer?.visibility = View.GONE
        hideBatchPanel()
        setSingleFormVisible(true)
        formController.updateCaptureContext(null, null)
        formController.restoreDraft(newBillDraft)
        applyAiEntriesForMode()
        renderContextBar()
    }

    private fun setSingleFormVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        amountRow?.visibility = v
        formBody?.visibility = v
    }

    // ════════════════════════════════════════════════════════
    //  细上下文条 + 待记/新账模式切换
    // ════════════════════════════════════════════════════════

    private fun currentItem(): CapturePluginClient.PendingCaptureItem? {
        if (pendingItems.isEmpty()) return null
        currentIndex = currentIndex.coerceIn(0, pendingItems.lastIndex)
        return pendingItems[currentIndex]
    }

    private fun renderContextBar() {
        val container = contextContainer ?: return
        if (pendingItems.isEmpty()) {
            container.visibility = View.GONE
            hideBatchPanel()
            return
        }
        container.visibility = View.VISIBLE
        val item = currentItem()
        tvCaptureCount?.text = "待记 ${pendingItems.size} 笔"
        val dotColor = when (item?.source) {
            CaptureSource.ALIPAY -> Color.parseColor("#1677FF")
            CaptureSource.WECHAT -> Color.parseColor("#07C160")
            else -> ctx.getColor(R.color.brand_primary)
        }
        sourceDot?.backgroundTintList = ColorStateList.valueOf(dotColor)
        tvCaptureSummary?.text = if (mode == FormMode.PENDING && item != null) {
            buildString {
                append(item.formattedTime())
                append(" · ")
                append(item.formattedAmount())
                item.merchant?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(it.take(10))
                }
            }
        } else {
            "普通记账中"
        }
        updateModeSwitchUi()
    }

    private fun updateModeSwitchUi() {
        styleModeSegment(btnModePending, mode == FormMode.PENDING)
        styleModeSegment(btnModeNew, mode == FormMode.NEW_BILL)
    }

    private fun styleModeSegment(tv: TextView?, selected: Boolean) {
        tv ?: return
        tv.setTextColor(
            if (selected) ctx.getColor(R.color.brand_primary) else Color.parseColor("#64748B")
        )
        tv.setBackgroundResource(if (selected) R.drawable.bg_capture_mode_segment else 0)
    }

    private fun showContextBarAnimated() {
        val container = contextContainer ?: return
        val wasVisible = container.visibility == View.VISIBLE
        renderContextBar()
        if (wasVisible) {
            container.alpha = 1f
            container.translationY = 0f
            return
        }
        container.alpha = 0f
        container.translationY = dp(4).toFloat()
        container.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateFormSwap() {
        val views = listOfNotNull(amountRow, formBody)
        views.forEach { view ->
            view.animate().cancel()
            view.alpha = 0.35f
            view.translationY = dp(5).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        tvCaptureSummary?.animate()?.cancel()
        tvCaptureSummary?.alpha = 0.35f
        tvCaptureSummary?.animate()
            ?.alpha(1f)
            ?.setDuration(160L)
            ?.start()
    }

    private fun snapshotPendingDraftIfNeeded() {
        if (mode != FormMode.PENDING || !hasPendingDraft) return
        val item = currentItem() ?: return
        if (pendingDraftItemId != null && pendingDraftItemId != item.id) {
            pendingDraft = null
            pendingDraftItemId = null
            hasPendingDraft = false
        }
        if (hasPendingDraft && pendingDraft == null) {
            pendingDraft = formController.snapshotDraft()
            pendingDraftItemId = item.id
        }
    }

    /** 待记模式不需要整张账单的智能识别；只保留图片/截图的识图拆单入口。 */
    private fun applyAiEntriesForMode() {
        val pending = mode == FormMode.PENDING
        formController.btnAiIcon.visibility = if (pending) View.GONE else View.VISIBLE
        formController.btnVoice.visibility = when {
            pending -> View.GONE
            Prefs.isShowAiVoice(ctx) -> View.VISIBLE
            else -> View.GONE
        }
        formController.layoutAiTextEntry.visibility = if (pending) View.GONE else View.VISIBLE
    }

    private fun switchToNewBillMode() {
        if (isBusy || mode == FormMode.NEW_BILL) return
        formController.hideAmountKeypad()
        snapshotPendingDraftIfNeeded()
        mode = FormMode.NEW_BILL
        isBatchDrawerOpen = false
        splitToggleRow?.visibility = View.GONE
        if (isSplitMode) {
            onSplitToggleChanged(false)
            switchSplitBill?.isChecked = false
        }
        hideBatchPanel()
        setSingleFormVisible(true)
        formController.updateCaptureContext(null, null)
        formController.restoreDraft(newBillDraft)
        renderContextBar()
        applyAiEntriesForMode()
        animateFormSwap()
    }

    private fun switchToPendingMode() {
        if (isBusy || mode == FormMode.PENDING || pendingItems.isEmpty()) return
        formController.hideAmountKeypad()
        newBillDraft = formController.snapshotDraft()
        mode = FormMode.PENDING
        isBatchDrawerOpen = false
        val current = currentItem()
        val canSplit = current != null && (current.billType == LocalBillType.TYPE_EXPENSE || current.billType == LocalBillType.TYPE_INCOME)
        if (canSplit) {
            splitToggleRow?.visibility = View.VISIBLE
            switchSplitBill?.isChecked = current?.id in splitItemIds
        } else {
            splitToggleRow?.visibility = View.GONE
        }
        hideBatchPanel()
        setSingleFormVisible(true)
        restorePendingDraftOrItem()
        renderContextBar()
        applyAiEntriesForMode()
        animateFormSwap()
    }

    private fun restorePendingDraftOrItem() {
        val item = currentItem() ?: run {
            clearPendingContext()
            return
        }
        val draft = pendingDraft
        if (draft != null && pendingDraftItemId == item.id) {
            formController.updateCaptureContext(item.id, UUID.randomUUID().toString())
            formController.restoreDraft(draft)
            pendingDraft = null
            pendingDraftItemId = null
            hasPendingDraft = true
            renderContextBar()
        } else {
            applyItemToSingleForm(currentIndex)
        }
    }

    /** 点“待记 N 笔”：任意模式下都打开速清面板，不关闭悬浮窗、不清空待记。 */
    private fun openBatchPanelFromBar() {
        if (isBusy || pendingItems.isEmpty()) return
        if (mode == FormMode.NEW_BILL) {
            formController.hideAmountKeypad()
            newBillDraft = formController.snapshotDraft()
            mode = FormMode.PENDING
            // 新账草稿先存着；这里直接进批量面板，不改写表单内容。
            hasPendingDraft = false
        }
        if (isBatchDrawerOpen) return
        openBatchDrawer()
    }

    /** 从批量面板“细改/分类胶囊”进入单笔；当前笔优先恢复未提交草稿，避免清空。 */
    private fun editItemFromBatch(index: Int) {
        val target = pendingItems.getOrNull(index) ?: return
        if (index == currentIndex && pendingDraftItemId == target.id && pendingDraft != null) {
            returnToSinglePendingForm()
        } else {
            applyItemToSingleForm(index)
        }
    }

    private fun returnToSinglePendingForm() {
        if (pendingItems.isEmpty()) {
            clearPendingContext()
            return
        }
        formController.hideAmountKeypad()
        mode = FormMode.PENDING
        isBatchDrawerOpen = false
        hideBatchPanel()
        setSingleFormVisible(true)
        restorePendingDraftOrItem()
        renderContextBar()
        animateFormSwap()
    }

    private fun hideBatchPanel() {
        capturePanel?.removeAllViews()
        capturePanel?.visibility = View.GONE
    }

    private fun onSingleRemarkChanged(note: String) {
        if (mode != FormMode.PENDING || isBatchDrawerOpen) return
        val item = currentItem() ?: return
        // 悬浮窗被外部 prefill 复用时，表单已不属于当前待记，不能把备注写回旧 capture。
        if (formController.currentCaptureId() != item.id) return
        if (isSplitMode) {
            updateSplitSaveGuard()
        }
        scheduleSingleNoteSync(item, note)
    }

    /** 关闭输入法即触发一次自动分类，不用按回车。 */
    private fun onRemarkImeClosed() {
        if (mode != FormMode.PENDING || isBatchDrawerOpen || isBusy) return
        autoClassifySinglePending()
    }

    private fun onSingleCategoryChanged(categoryName: String) {
        if (mode != FormMode.PENDING) return
        val item = currentItem() ?: return
        if (formController.currentCaptureId() != item.id) return
        val cleanCat = categoryName.trim().takeIf { it.isNotEmpty() }
        item.categoryName = cleanCat
        item.categorySource = if (cleanCat != null) "MANUAL" else null
        item.categoryRuleId = null
        if (pendingDraft != null && pendingDraftItemId == item.id) {
            val draftCategory = pendingDraft?.categoryName.orEmpty()
            pendingDraft = pendingDraft?.copy(
                categoryName = cleanCat.orEmpty(),
                categorySource = if (cleanCat != null) "MANUAL" else null,
                categoryRuleId = null,
            )
        }
        scope.launch {
            CapturePluginClient.updateCaptureDraft(
                context = ctx,
                captureId = item.id,
                categoryName = cleanCat.orEmpty(),
                categorySource = if (cleanCat != null) "MANUAL" else null,
            )
            if (isBatchDrawerOpen) {
                renderBatchDrawer()
            }
        }
    }

    private fun scheduleSingleNoteSync(
        item: CapturePluginClient.PendingCaptureItem,
        note: String,
    ) {
        noteSyncJobs[item.id]?.cancel()
        noteSyncJobs[item.id] = scope.launch {
            delay(280L)
            val cleanNote = note.trim()
            val res = CapturePluginClient.updateCaptureDraft(ctx, item.id, note = cleanNote)
            if (!res.ok) return@launch
            item.note = cleanNote.takeIf { it.isNotEmpty() }
            val previousCategory = item.categoryName.orEmpty()
            val formCategory = formController.currentCategoryName()
            if (res.categoryName != item.categoryName || res.categorySource != item.categorySource) {
                item.categoryName = res.categoryName
                item.categorySource = res.categorySource
                item.categoryRuleId = res.categoryRuleId
                if (formCategory == previousCategory &&
                    mode == FormMode.PENDING && !isBatchDrawerOpen &&
                    pendingItems.getOrNull(currentIndex)?.id == item.id
                ) {
                    formController.applyCategoryName(res.categoryName, res.categorySource, res.categoryRuleId)
                }
            }
            if (pendingDraft != null && pendingDraftItemId == item.id) {
                val draftCategory = pendingDraft?.categoryName.orEmpty()
                pendingDraft = pendingDraft?.copy(
                    remark = cleanNote,
                    categoryName = if (draftCategory == previousCategory) {
                        res.categoryName.orEmpty()
                    } else {
                        draftCategory
                    },
                    categorySource = if (draftCategory == previousCategory) {
                        res.categorySource
                    } else {
                        pendingDraft?.categorySource
                    },
                    categoryRuleId = if (draftCategory == previousCategory) {
                        res.categoryRuleId
                    } else {
                        pendingDraft?.categoryRuleId
                    },
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  多笔待记速清 & 一键智能归类入账面板
    // ════════════════════════════════════════════════════════

    private fun openBatchDrawer() {
        if (pendingItems.isEmpty()) return
        formController.hideAmountKeypad()
        snapshotPendingDraftIfNeeded()
        isBatchDrawerOpen = true
        setSingleFormVisible(false)
        capturePanel?.visibility = View.VISIBLE
        renderBatchDrawer()
    }

    private fun renderBatchDrawer() {
        capturePanel?.removeAllViews()
        batchRowViews.clear()
        batchCategoryChips.clear()
        if (pendingItems.isEmpty()) {
            clearPendingContext()
            return
        }

        val totalYuan = pendingItems.sumOf { it.amountCents } / 100.0
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        // 顶部标题栏
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val tvTitle = TextView(ctx).apply {
            text = String.format(Locale.CHINA, "待记速清 · %d 笔 (¥%.2f)", pendingItems.size, totalYuan)
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvTitle)

        header.addView(
            smallChipButton(
                text = "返回单笔",
                textColor = Color.parseColor("#334155"),
                bgColor = Color.parseColor("#EEF2F6"),
            ) {
                returnToSinglePendingForm()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        )
        header.addView(
            smallChipButton(
                text = "记新账",
                textColor = Color.parseColor("#475569"),
                bgColor = Color.parseColor("#EEF2F6"),
            ) {
                switchToNewBillMode()
            }
        )
        panel.addView(header)

        val tvSubHint = TextView(ctx).apply {
            text = "写备注会实时匹配本地分类规则；离开输入框会自动归类。没备注的账单保持待归类。"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(8))
        }
        batchSubHint = tvSubHint
        panel.addView(tvSubHint)

        // 列表滚动区（限高避免撑满全屏）
        val maxScrollHeight = (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cappedSpec = MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cappedSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
        }

        val listLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val editTexts = mutableListOf<EditText>()
        pendingItems.forEachIndexed { idx, item ->
            val itemCard = buildBatchItemRow(idx, item, editTexts)
            batchRowViews[item.id] = itemCard
            listLayout.addView(itemCard)
        }

        // 绑定键盘“下一项”连跳
        for (i in editTexts.indices) {
            val currentEt = editTexts[i]
            val nextEt = editTexts.getOrNull(i + 1)
            currentEt.imeOptions = if (nextEt != null) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
            currentEt.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT && nextEt != null) {
                    nextEt.requestFocus()
                    nextEt.setSelection(nextEt.text?.length ?: 0)
                    true
                } else {
                    false
                }
            }
        }

        scroll.addView(listLayout)
        panel.addView(scroll)

        // 底部主操作区：只保留一键入账；缺分类在入账流程里自动兜底。
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val categorizedCount = pendingItems.count {
            !it.categoryName.isNullOrBlank() && it.amountCents > 0L
        }
        val btnCommitAll = TextView(ctx).apply {
            text = if (isBusy) "自动归类中…" else "✓ 一键入账 (${categorizedCount}笔)"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rippleBg(ctx.getColor(R.color.brand_primary), dp(11))
            isEnabled = !isBusy
            setOnClickListener {
                commitAllPendingBills()
            }
        }
        batchCommitButton = btnCommitAll

        footer.addView(
            btnCommitAll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        panel.addView(footer)

        capturePanel?.addView(panel)
        capturePanel?.visibility = View.VISIBLE
        renderContextBar()
    }

    private fun buildBatchItemRow(
        index: Int,
        item: CapturePluginClient.PendingCaptureItem,
        editTexts: MutableList<EditText>,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(Color.parseColor("#F8FAFC"), dp(12), dp(1), Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val topLine = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val isAlipay = item.source == CaptureSource.ALIPAY
        topLine.addView(TextView(ctx).apply {
            text = item.sourceLabel()
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isAlipay) "#1677FF" else "#07C160"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = roundedBg(
                fillColor = Color.parseColor(if (isAlipay) "#EFF6FF" else "#ECFDF5"),
                radiusPx = dp(6),
            )
        })

        val assetLabel = item.displayAsset() ?: "账户待选"
        topLine.addView(TextView(ctx).apply {
            text = "  ${item.formattedTime()} · $assetLabel"
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val isRefund = item.subType == LocalBillType.SUBTYPE_REFUND || item.needsRefundLink
        val isIncome = item.billType == LocalBillType.TYPE_INCOME
        val amtPrefix = when {
            isIncome || isRefund -> "+"
            item.billType == LocalBillType.TYPE_TRANSFER -> ""
            else -> "-"
        }
        topLine.addView(TextView(ctx).apply {
            text = "$amtPrefix${item.formattedAmount()}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isIncome || isRefund) "#059669" else "#0F172A"))
        })
        card.addView(topLine)

        // 第二行：商户 + 实时可刷新分类胶囊 + 单记/不计
        val midLine = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        midLine.addView(TextView(ctx).apply {
            text = item.merchant?.takeIf { it.isNotBlank() } ?: "未知商户"
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        fun styleCatBadge(tv: TextView, cat: String?, source: String? = null) {
            if (item.id in splitItemIds) {
                tv.text = "🔀 逐条分类"
                tv.setTextColor(Color.parseColor("#2563EB"))
                tv.background = rippleBg(
                    fillColor = Color.parseColor("#EFF6FF"),
                    radiusPx = dp(7),
                    strokeWidthPx = dp(1),
                    strokeColor = Color.parseColor("#BFDBFE"),
                )
                return
            }
            val hasCat = !cat.isNullOrBlank()
            val sourcePrefix = when (source?.uppercase()) {
                "MANUAL" -> "手动 · "
                "RULE" -> "规则 · "
                "AI" -> "AI · "
                else -> ""
            }
            tv.text = if (hasCat) "🏷️ $sourcePrefix$cat" else "待归类"
            tv.setTextColor(Color.parseColor(if (hasCat) "#059669" else "#D97706"))
            tv.background = rippleBg(
                fillColor = Color.parseColor(if (hasCat) "#ECFDF5" else "#FFFBEB"),
                radiusPx = dp(7),
                strokeWidthPx = dp(1),
                strokeColor = Color.parseColor(if (hasCat) "#A7F3D0" else "#FDE68A"),
            )
        }

        val catChip = smallChipButton(
            text = "",
            textColor = Color.parseColor("#059669"),
            bgColor = Color.parseColor("#ECFDF5"),
        ) {
            editItemFromBatch(index)
        }
        styleCatBadge(catChip, item.categoryName, item.categorySource)
        batchCategoryChips[item.id] = catChip
        midLine.addView(
            catChip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        )

        midLine.addView(
            smallChipButton(
                text = "细改",
                textColor = ctx.getColor(R.color.brand_primary_dark),
                bgColor = ctx.getColor(R.color.brand_primary_soft),
            ) {
                editItemFromBatch(index)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            }
        )

        if (item.billType == LocalBillType.TYPE_EXPENSE || item.billType == LocalBillType.TYPE_INCOME) {
            midLine.addView(
                smallChipButton(
                    text = "拆分",
                    textColor = ctx.getColor(R.color.brand_primary_dark),
                    bgColor = ctx.getColor(R.color.brand_primary_soft),
                ) {
                    editItemFromBatch(index)
                    switchSplitBill?.isChecked = true
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
            )
        }

        midLine.addView(
            smallChipButton(
                text = "不计",
                textColor = Color.parseColor("#DC2626"),
                bgColor = Color.parseColor("#FEF2F2"),
            ) {
                ignoreItem(index)
            }
        )
        card.addView(midLine)

        // 第三行：内联备注输入框（输入停止 280ms 后同步插件并实时回刷分类标签）
        val etNote = EditText(ctx).apply {
            setText(item.note.orEmpty())
            hint = "写备注，命中关键词会自动归类"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBg(Color.WHITE, dp(8), dp(1), Color.parseColor("#CBD5E1"))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val newNote = s?.toString()?.trim().orEmpty()
                    item.note = newNote.takeIf { it.isNotEmpty() }
                    if (pendingDraft != null && pendingDraftItemId == item.id) {
                        pendingDraft = pendingDraft?.copy(remark = newNote)
                    }
                    scheduleNoteSync(item, newNote) { newCat ->
                        styleCatBadge(catChip, newCat)
                    }
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) autoClassifyPendingItem(item)
            }
        }
        editTexts.add(etNote)
        card.addView(etNote)

        batchRowViews[item.id] = card
        return card
    }

    /** 悬浮窗批量行失去焦点/收起键盘后，只在仍缺分类时补一次 AI。 */
    private fun autoClassifyPendingItem(item: CapturePluginClient.PendingCaptureItem) {
        if (isBusy || item.id in splitItemIds) return
        if (!item.note.isNullOrBlank() && item.categoryName.isNullOrBlank() &&
            item.billType != LocalBillType.TYPE_TRANSFER
        ) {
            scope.launch {
                classifyPendingItems(listOf(item), showToast = false)
            }
        }
    }

    private fun installImeHideWatcher() {
        rootFloatingView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            rootFloatingView.getWindowVisibleDisplayFrame(rect)
            val height = rootFloatingView.rootView.height
            val visible = height > 0 && height - rect.bottom > dp(120)
            if (imeWasVisible && !visible) {
                (rootFloatingView.findFocus() as? EditText)?.clearFocus()
            }
            imeWasVisible = visible
        }
    }

    private fun autoClassifySinglePending() {
        if (mode != FormMode.PENDING || isBatchDrawerOpen || isBusy) return
        val item = currentItem() ?: return
        if (item.id in splitItemIds) return
        // 用输入框里的最新备注，而不是等 debounce 落库后的旧 note。
        val note = etRemark?.text?.toString()?.trim().orEmpty()
        if (note.isBlank() || !item.categoryName.isNullOrBlank()) return
        if (item.billType == LocalBillType.TYPE_TRANSFER) return
        item.note = note
        scope.launch {
            val updatedCount = classifyPendingItems(listOf(item), showToast = false)
            if (updatedCount <= 0) return@launch
            val updated = pendingItems.firstOrNull { it.id == item.id } ?: return@launch
            if (mode == FormMode.PENDING && !isBatchDrawerOpen &&
                currentItem()?.id == updated.id
            ) {
                formController.applyCategoryName(
                    updated.categoryName,
                    updated.categorySource,
                    updated.categoryRuleId,
                )
            }
        }
    }

    private fun styleBatchCategoryChip(tv: TextView, cat: String?, source: String?) {
        val hasCat = !cat.isNullOrBlank()
        val sourcePrefix = when (source?.uppercase()) {
            "MANUAL" -> "手动 · "
            "RULE" -> "规则 · "
            "AI" -> "AI · "
            else -> ""
        }
        tv.text = if (hasCat) "🏷️ $sourcePrefix$cat" else "待归类"
        tv.setTextColor(Color.parseColor(if (hasCat) "#059669" else "#D97706"))
        tv.background = rippleBg(
            fillColor = Color.parseColor(if (hasCat) "#ECFDF5" else "#FFFBEB"),
            radiusPx = dp(7),
            strokeWidthPx = dp(1),
            strokeColor = Color.parseColor(if (hasCat) "#A7F3D0" else "#FDE68A"),
        )
    }

    private suspend fun classifyPendingItems(
        items: List<CapturePluginClient.PendingCaptureItem>,
        showToast: Boolean,
    ): Int {
        val targets = items.filter {
            it.id !in splitItemIds &&
                !it.note.isNullOrBlank() &&
                it.categoryName.isNullOrBlank() &&
                it.billType != LocalBillType.TYPE_TRANSFER
        }
        if (targets.isEmpty()) return 0
        if (Prefs.getAiKey(ctx).isBlank()) {
            if (showToast) Utils.toast(ctx, "请先在敲敲记账设置中配置 AI API Key")
            return 0
        }
        val res = withContext(Dispatchers.IO) {
            CaptureImportService.classifyNotes(ctx, buildCaptureInputArray(targets))
        }
        if (!res.optBoolean("ok", false)) {
            if (showToast) Utils.toast(ctx, res.optString("message", "自动归类未完成"))
            return 0
        }
        val drafts = res.optJSONArray("drafts") ?: JSONArray()
        var updatedCount = 0
        for (i in 0 until drafts.length()) {
            val d = drafts.optJSONObject(i) ?: continue
            val cid = d.optString("captureId", "")
            val target = targets.firstOrNull { it.id == cid } ?: continue
            val current = pendingItems.firstOrNull { it.id == cid } ?: continue
            if (current.note?.trim() != target.note?.trim()) continue
            val cat = d.optString("categoryName", "").trim().takeIf { it.isNotEmpty() }
                ?: continue
            current.categoryName = cat
            current.categorySource = "AI"
            CapturePluginClient.updateCaptureDraft(
                context = ctx,
                captureId = current.id,
                note = current.note,
                assetName = current.assetName,
                categoryName = cat,
                categorySource = "AI",
            )
            batchCategoryChips[current.id]?.let { styleBatchCategoryChip(it, cat, "AI") }
            updatedCount++
        }
        if (showToast) {
            Utils.toast(
                ctx,
                if (updatedCount == 0) "AI 暂无法判断，账单保持待归类"
                else "✨ 已自动归类 $updatedCount 笔",
            )
        }
        if (!isBusy) updateBatchFooter()
        return updatedCount
    }

    private fun scheduleNoteSync(
        item: CapturePluginClient.PendingCaptureItem,
        note: String,
        onCategoryUpdated: (String?) -> Unit,
    ) {
        noteSyncJobs[item.id]?.cancel()
        noteSyncJobs[item.id] = scope.launch {
            delay(280L)
            val cleanNote = note.trim()
            val res = CapturePluginClient.updateCaptureDraft(ctx, item.id, note = cleanNote)
            if (!res.ok) return@launch
            item.note = cleanNote.takeIf { it.isNotEmpty() }
            val previousCategory = item.categoryName.orEmpty()
            if (res.categoryName != item.categoryName) {
                item.categoryName = res.categoryName
                onCategoryUpdated(res.categoryName)
            }
            if (pendingDraft != null && pendingDraftItemId == item.id) {
                val draftCategory = pendingDraft?.categoryName.orEmpty()
                pendingDraft = pendingDraft?.copy(
                    remark = cleanNote,
                    categoryName = if (draftCategory == previousCategory) {
                        res.categoryName.orEmpty()
                    } else {
                        draftCategory
                    },
                )
            }
        }
    }

    private fun ignoreItem(index: Int) {
        val target = pendingItems.getOrNull(index) ?: return
        scope.launch {
            CapturePluginClient.ignoreCapture(ctx, target.id)
            val wasCurrent = index == currentIndex
            pendingItems.removeAt(index)
            if (wasCurrent) {
                pendingDraft = null
                pendingDraftItemId = null
                hasPendingDraft = false
            }
            if (pendingItems.isEmpty()) {
                Utils.toast(ctx, "所有待记已处理完毕")
                clearPendingContext()
            } else {
                currentIndex = when {
                    index < currentIndex -> currentIndex - 1
                    currentIndex > pendingItems.lastIndex -> pendingItems.lastIndex
                    else -> currentIndex
                }
                if (isBatchDrawerOpen) {
                    renderBatchDrawer()
                } else {
                    applyItemToSingleForm(currentIndex)
                }
            }
        }
    }

    /**
     * 一键入账：
     * 1. 有备注、缺分类的先自动跑一次 AI；
     * 2. 没备注、缺分类的不弹窗，留在待记并 Toast 汇总；
     * 3. 有备注但 AI 仍分不出的，才弹“逐笔补分类”。
     */
    private fun commitAllPendingBills() {
        if (isBusy || pendingItems.isEmpty()) return
        isBusy = true
        updateBatchFooter()

        scope.launch {
            var recordedAny = false
            var resultCardShown = false
            var noteMissingForPrompt: List<CapturePluginClient.PendingCaptureItem> = emptyList()
            var noNoteSkipped: List<CapturePluginClient.PendingCaptureItem> = emptyList()
            try {
                noteSyncJobs.values.forEach { it.join() }

                val aiTargets = pendingItems.filter {
                    it.id !in splitItemIds &&
                        !it.note.isNullOrBlank() &&
                        it.categoryName.isNullOrBlank() &&
                        it.billType != LocalBillType.TYPE_TRANSFER
                }
                if (aiTargets.isNotEmpty()) {
                    classifyPendingItems(aiTargets, showToast = false)
                }

                val categorized = pendingItems.filter {
                    it.id !in splitItemIds &&
                        !it.categoryName.isNullOrBlank() &&
                        it.amountCents > 0L
                }
                val allMissing = pendingItems.filter {
                    it.id !in splitItemIds &&
                        it.categoryName.isNullOrBlank() &&
                        it.billType != LocalBillType.TYPE_TRANSFER
                }
                noNoteSkipped = allMissing.filter { it.note.isNullOrBlank() }
                noteMissingForPrompt = allMissing.filter { !it.note.isNullOrBlank() }

                val billsArray = JSONArray()
                categorized.forEach { item ->
                    val finalRemark = item.note?.takeIf { it.isNotBlank() } ?: ""
                    billsArray.put(
                        JSONObject().apply {
                            put("captureId", item.id)
                            put("type", item.billType ?: LocalBillType.TYPE_EXPENSE)
                            put("subType", item.subType ?: LocalBillType.SUBTYPE_NORMAL)
                            put("needsRefundLink", item.needsRefundLink)
                            put("amountCents", item.amountCents)
                            put("currency", item.currency.ifBlank { "CNY" })
                            put("paidAt", item.paidAt)
                            putOpt("assetName", item.displayAsset())
                            putOpt("toAssetName", item.toAssetName)
                            putOpt("categoryName", item.categoryName)
                            put("remarks", finalRemark)
                            putOpt("note", item.note)
                            putOpt("bookName", item.bookName)
                        }
                    )
                }

                val recordedIds = mutableListOf<String>()
                var firstError: String? = null
                if (billsArray.length() > 0) {
                    val requestId = UUID.randomUUID().toString()
                    val importRes = withContext(Dispatchers.IO) {
                        CaptureImportService.importBills(ctx, billsArray, requestId)
                    }
                    val resultsArr = importRes.optJSONArray("results") ?: JSONArray()
                    for (i in 0 until resultsArr.length()) {
                        val r = resultsArr.optJSONObject(i) ?: continue
                        val cid = r.optString("captureId", "")
                        val st = r.optString("status", "")
                        if (st == ImportItemStatus.CREATED || st == ImportItemStatus.DUPLICATE) {
                            recordedIds.add(cid)
                        } else if (firstError == null) {
                            firstError = r.optString("error", "").takeIf { it.isNotBlank() }
                        }
                    }
                }

                if (recordedIds.isNotEmpty()) {
                    recordedAny = true
                    val recordedSet = recordedIds.toSet()
                    val recordedItems = categorized.filter { it.id in recordedSet }
                    removePendingRows(recordedSet)

                    val askEntries = recordedItems.map {
                        RuleLearningManager.RuleLearnEntry(
                            note = it.note.orEmpty(),
                            categoryName = it.categoryName.orEmpty(),
                            billType = it.billType ?: 0,
                        )
                    }
                    val asked = com.taostudio.tapaccounting.logic.RuleAskCoordinator
                        .showIfNeeded(ctx, askEntries)
                    if (!asked) {
                        resultCardShown = true
                        showBatchResultCard(recordedItems) {
                            if (noteMissingForPrompt.isNotEmpty()) {
                                promptMissingCategories(noteMissingForPrompt)
                            } else if (pendingItems.isEmpty()) {
                                onCloseOverlay(true)
                            }
                        }
                    } else if (noteMissingForPrompt.isNotEmpty()) {
                        promptMissingCategories(noteMissingForPrompt)
                    }
                } else if (categorized.isEmpty()) {
                    if (noteMissingForPrompt.isNotEmpty()) {
                        promptMissingCategories(noteMissingForPrompt)
                    } else if (noNoteSkipped.isEmpty()) {
                        Utils.toast(ctx, "没有已分类的账单可入账")
                    }
                } else {
                    val reason = when {
                        firstError == "missing_category" -> "请先写备注让 AI 归类，或手动选择分类"
                        firstError?.startsWith("asset_not_found:") == true ->
                            "账户「${firstError.removePrefix("asset_not_found:")}」在敲敲记账中不存在"
                        !firstError.isNullOrBlank() -> firstError
                        else -> "请先补齐分类"
                    }
                    Utils.toast(ctx, "暂未入账：$reason")
                    if (noteMissingForPrompt.isNotEmpty()) {
                        promptMissingCategories(noteMissingForPrompt)
                    }
                }

                if (noNoteSkipped.isNotEmpty()) {
                    toastNoNoteSkipped(noNoteSkipped)
                }
            } catch (e: Exception) {
                Utils.toast(ctx, "批量入账失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                isBusy = false
                updateBatchFooter()
                if (recordedAny && pendingItems.isEmpty() && !resultCardShown) {
                    onCloseOverlay(true)
                }
            }
        }
    }

    private fun removePendingRows(ids: Set<String>) {
        if (ids.isEmpty()) return
        val previousCurrentId = pendingItems.getOrNull(currentIndex)?.id
        ids.forEach { id ->
            batchRowViews.remove(id)?.let { row ->
                (row.parent as? ViewGroup)?.removeView(row)
            }
            batchCategoryChips.remove(id)
        }
        pendingItems.removeAll { it.id in ids }
        if (pendingItems.isEmpty()) {
            pendingDraft = null
            pendingDraftItemId = null
            hasPendingDraft = false
        } else {
            val retainedIndex = previousCurrentId
                ?.let { id -> pendingItems.indexOfFirst { it.id == id } }
                ?: -1
            currentIndex = retainedIndex.takeIf { it >= 0 } ?: 0
            if (previousCurrentId == null || previousCurrentId in ids) {
                pendingDraft = null
                pendingDraftItemId = null
                hasPendingDraft = false
            }
        }
        updateBatchFooter()
    }

    private fun updateBatchFooter() {
        val count = pendingItems.count {
            it.id !in splitItemIds &&
                !it.categoryName.isNullOrBlank() &&
                it.amountCents > 0L
        }
        batchCommitButton?.apply {
            text = if (isBusy) "自动归类并入账中…" else "✓ 一键入账 (${count}笔)"
            isEnabled = !isBusy
        }
        batchSubHint?.text = if (pendingItems.isEmpty()) {
            "没有待记了"
        } else {
            "写备注会实时匹配本地分类规则；离开输入框会自动归类。没备注的账单保持待归类。"
        }
    }

    private fun toastNoNoteSkipped(items: List<CapturePluginClient.PendingCaptureItem>) {
        if (items.isEmpty()) return
        val preview = items.take(3).joinToString("、") { item ->
            buildString {
                append(item.formattedAmount())
                item.merchant?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(8)) }
            }
        }
        val suffix = if (items.size > 3) " 等 ${items.size} 笔" else ""
        Utils.toast(ctx, "${items.size} 笔没备注，未入账：$preview$suffix")
    }

    /** 一键入账后“有备注但仍缺分类”的弹窗；没备注的不会进来。 */
    private fun promptMissingCategories(items: List<CapturePluginClient.PendingCaptureItem>) {
        val targets = items.filter { !it.note.isNullOrBlank() }
        if (targets.isEmpty()) {
            updateBatchFooter()
            return
        }
        val preview = buildString {
            targets.take(6).forEach { item ->
                append("· ").append(item.formattedAmount()).append("  ")
                append(item.merchant?.takeIf { it.isNotBlank() } ?: "未知商户")
                item.note?.takeIf { it.isNotBlank() }?.let {
                    append("（").append(it.take(12)).append("）")
                }
                append('\n')
            }
            if (targets.size > 6) append("… 还有 ${targets.size - 6} 笔")
        }
        AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
            .setTitle("${targets.size} 笔有备注但暂未分出分类")
            .setMessage("以下待记仍保留在列表里，需逐笔补分类：\n\n$preview")
            .setPositiveButton("逐笔补分类") { _, _ ->
                promptMissingCategoryAt(targets, 0, 0)
            }
            .setNegativeButton("稍后") { _, _ -> updateBatchFooter() }
            .setOnCancelListener { updateBatchFooter() }
            .show()
    }

    private fun promptMissingCategoryAt(
        items: List<CapturePluginClient.PendingCaptureItem>,
        index: Int,
        addedCount: Int,
    ) {
        if (index >= items.size) {
            if (addedCount > 0) {
                Utils.toast(ctx, "已补 $addedCount 笔分类，可再次点“一键入账”")
            } else {
                Utils.toast(ctx, "未补分类，缺分类账单仍保留待记")
            }
            updateBatchFooter()
            return
        }
        val item = items[index]
        if (pendingItems.none { it.id == item.id }) {
            promptMissingCategoryAt(items, index + 1, addedCount)
            return
        }

        OverlayDialogs.showGridCategoryPicker(
            ctx = ctx,
            currentSelectionText = item.categoryName.orEmpty(),
            type = item.billType ?: LocalBillType.TYPE_EXPENSE,
            allowClear = false,
            onCancel = {
                promptMissingCategoryAt(items, index + 1, addedCount)
            },
        ) { selected ->
            if (selected.isBlank()) {
                promptMissingCategoryAt(items, index + 1, addedCount)
            } else {
                scope.launch {
                    val res = CapturePluginClient.updateCaptureDraft(
                        context = ctx,
                        captureId = item.id,
                        note = item.note,
                        assetName = item.assetName,
                        categoryName = selected,
                        categorySource = "MANUAL",
                    )
                    if (res.ok) {
                        item.categoryName = res.categoryName ?: selected
                        item.categorySource = "MANUAL"
                        batchCategoryChips[item.id]?.let { chip ->
                            styleBatchCategoryChip(chip, item.categoryName, "MANUAL")
                        }
                        promptMissingCategoryAt(items, index + 1, addedCount + 1)
                    } else {
                        Utils.toast(ctx, "补分类失败，该笔保留待归类")
                        promptMissingCategoryAt(items, index + 1, addedCount)
                    }
                }
            }
        }
    }

    private fun buildCaptureInputArray(items: List<CapturePluginClient.PendingCaptureItem>): JSONArray =
        JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("captureId", item.id)
                        putOpt("note", item.note)
                        putOpt("billType", item.billType)
                    }
                )
            }
        }

    private fun roundedBg(
        fillColor: Int,
        radiusPx: Int,
        strokeWidthPx: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = radiusPx.toFloat()
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }

    private fun rippleBg(
        fillColor: Int,
        radiusPx: Int,
        strokeWidthPx: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): RippleDrawable {
        val content = roundedBg(fillColor, radiusPx, strokeWidthPx, strokeColor)
        val mask = roundedBg(Color.WHITE, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#1A0F172A")), content, mask)
    }

    private fun smallChipButton(
        text: String,
        textColor: Int,
        bgColor: Int,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rippleBg(bgColor, dp(8))
        setOnClickListener { onClick() }
    }

    // ════════════════════════════════════════════════════════
    //  拆分记账：状态联动、AI 拆解预览与确认入账
    // ════════════════════════════════════════════════════════

    data class ParsedSplitItem(
        var name: String,
        var amountCents: Long,
        var categoryName: String?,
        var inferred: Boolean = false,
    )

    private fun onSplitToggleChanged(isChecked: Boolean) {
        isSplitMode = isChecked
        val item = currentItem() ?: return
        if (isChecked) {
            splitItemIds.add(item.id)
        } else {
            splitItemIds.remove(item.id)
        }

        if (isChecked) {
            btnSplitPreview?.visibility = View.VISIBLE
            btnSave?.text = "拆分记账"
            previousCategoryText = tvCategory?.text?.toString()
            tvCategory?.text = "逐条分类"
            tvCategorySourceBadge?.visibility = View.GONE
            etRemark?.hint = "必填：输入明细，如「可乐 3 元，西瓜 15 元」"
            updateSplitSaveGuard()
        } else {
            btnSplitPreview?.visibility = View.GONE
            btnSave?.text = ctx.getString(R.string.save_and_record)
            val restoredCat = item.categoryName?.takeIf { it.isNotBlank() }
                ?: previousCategoryText?.takeIf { it.isNotBlank() && it != "逐条分类" }
                ?: ctx.getString(R.string.select_category)
            tvCategory?.text = restoredCat
            formController.applyCategoryName(
                categoryName = item.categoryName.orEmpty(),
                source = item.categorySource,
                ruleId = item.categoryRuleId,
            )
            etRemark?.hint = ctx.getString(R.string.hint_remark)
            formController.updateSaveButtonState()
        }
    }

    private fun updateSplitSaveGuard() {
        if (!isSplitMode) return
        val note = etRemark?.text?.toString()?.trim().orEmpty()
        if (note.isEmpty()) {
            btnSave?.alpha = 0.45f
            tvSaveDisabledHint?.visibility = View.VISIBLE
            tvSaveDisabledHint?.text = "开启拆分需填写明细备注"
        } else {
            btnSave?.alpha = 1.0f
            tvSaveDisabledHint?.visibility = View.GONE
        }
    }

    private fun triggerSplitPreview() {
        if (isBusy) return
        val item = currentItem() ?: return
        val amountStr = etAmount?.text?.toString()?.trim().orEmpty()
        val money = amountStr.toDoubleOrNull() ?: (item.amountCents / 100.0)
        val totalCents = (money * 100.0).roundToLong()
        if (totalCents <= 0L) {
            Utils.toast(ctx, "付款金额无效，无法拆分")
            etAmount?.requestFocus()
            return
        }
        val note = etRemark?.text?.toString()?.trim().orEmpty()
        if (note.isEmpty()) {
            Utils.toast(ctx, "备注为必填，请输入待拆分的明细或账单内容")
            etRemark?.requestFocus()
            return
        }
        val billType = item.billType ?: LocalBillType.TYPE_EXPENSE
        if (billType != LocalBillType.TYPE_EXPENSE && billType != LocalBillType.TYPE_INCOME) {
            Utils.toast(ctx, "转账/还款/退款不支持文字拆分")
            return
        }

        btnSplitPreview?.text = "拆分中…"
        btnSplitPreview?.isEnabled = false
        isBusy = true
        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("captureId", item.id)
                    put("note", note)
                    put("totalCents", totalCents)
                    put("billType", billType)
                }
                val previewJson = withContext(Dispatchers.IO) {
                    CaptureImportService.previewSplit(ctx, payload)
                }
                if (!previewJson.optBoolean("ok", false)) {
                    val err = previewJson.optString("error", "split_failed")
                    val msg = previewJson.optString("message", "AI 拆分失败，请改备注")
                    Utils.toast(ctx, humanizeSplitError(err, msg))
                    return@launch
                }

                val itemsArr = previewJson.optJSONArray("items") ?: JSONArray()
                val parsedItems = mutableListOf<ParsedSplitItem>()
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    val cents = obj.optLong("amountCents", 0L)
                    val cat = obj.optString("categoryName", "").trim().takeIf { it.isNotEmpty() }
                    val inferred = obj.optBoolean("inferred", false)
                    if (name.isNotEmpty()) {
                        parsedItems.add(ParsedSplitItem(name, cents, cat, inferred))
                    }
                }

                if (parsedItems.isEmpty()) {
                    Utils.toast(ctx, "未能从备注中识别出明细条目，请改备注")
                    return@launch
                }

                // 若拆出只有 1 条且金额相等则平滑降级为单笔
                if (parsedItems.size == 1 && parsedItems[0].amountCents == totalCents) {
                    switchSplitBill?.isChecked = false
                    val single = parsedItems[0]
                    etRemark?.setText(single.name)
                    if (!single.categoryName.isNullOrBlank()) {
                        formController.applyCategoryName(single.categoryName, "AI", null)
                        item.categoryName = single.categoryName
                    }
                    splitItemIds.remove(item.id)
                    Utils.toast(ctx, "识别为单笔消费，已自动转为普通记账")
                    return@launch
                }

                val fallbackCategory = previewJson.optString("fallbackCategoryName", "其他")
                    .takeUnless { it.isBlank() || it == "null" } ?: "其他"

                showSplitPreviewDialog(item, totalCents, parsedItems, fallbackCategory)
            } catch (e: Exception) {
                Utils.toast(ctx, "拆分异常：${e.message ?: e.javaClass.simpleName}")
            } finally {
                btnSplitPreview?.text = "拆单预览"
                btnSplitPreview?.isEnabled = true
                isBusy = false
            }
        }
    }

    /** 当前悬浮窗是否处于待记模式（有待记数据时自动进入）。 */
    fun isPendingMode(): Boolean = mode == FormMode.PENDING

    /** 图片拆单：选择图片，把当前待记拆成多条明细。 */
    fun startImageSplitFromPicker() {
        if (mode != FormMode.PENDING || isBusy) return
        if (switchSplitBill?.isChecked != true) switchSplitBill?.isChecked = true
        onPickerWindowVisibility(true)
        ImagePickerActivity.launch(
            context = ctx,
            maxItems = 5,
            onPicked = { uris ->
                onPickerWindowVisibility(false)
                splitCurrentItemWithImages(uris)
            },
            onCancelled = {
                onPickerWindowVisibility(false)
                Utils.toast(ctx, "已取消选图，待记仍保留")
            },
        )
    }

    /** 截图/选图统一入口：按图片识别明细，再走现有拆单确认面板。 */
    fun splitCurrentItemWithImage(uri: Uri) {
        splitCurrentItemWithImages(listOf(uri))
    }

    fun splitCurrentItemWithImages(uris: List<Uri>) {
        if (mode != FormMode.PENDING || isBusy) return
        if (uris.isEmpty()) return
        val item = currentItem() ?: return
        val amountStr = etAmount?.text?.toString()?.trim().orEmpty()
        val money = amountStr.toDoubleOrNull() ?: (item.amountCents / 100.0)
        val totalCents = (money * 100.0).roundToLong()
        if (totalCents <= 0L) {
            Utils.toast(ctx, "付款金额无效，无法拆分")
            etAmount?.requestFocus()
            return
        }
        val billType = item.billType ?: LocalBillType.TYPE_EXPENSE
        if (billType != LocalBillType.TYPE_EXPENSE && billType != LocalBillType.TYPE_INCOME) {
            Utils.toast(ctx, "转账/还款/退款不支持图片拆分")
            return
        }
        if (switchSplitBill?.isChecked != true) switchSplitBill?.isChecked = true

        btnSplitPreview?.text = "识别中…"
        btnSplitPreview?.isEnabled = false
        isBusy = true
        scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    CaptureBridgeClient(ctx).previewImageSplitMulti(
                        captureId = item.id,
                        totalCents = totalCents,
                        billType = billType,
                        imageUris = uris,
                    )
                }
                if (!preview.ok) {
                    Utils.toast(ctx, humanizeSplitError(preview.error, preview.message))
                    return@launch
                }
                val parsedItems = preview.items.map {
                    ParsedSplitItem(it.name, it.amountCents, it.categoryName, it.inferred)
                }.toMutableList()
                if (parsedItems.isEmpty()) {
                    Utils.toast(ctx, "未能从图片识别出明细条目，请换一张图")
                    return@launch
                }
                if (parsedItems.size == 1 && parsedItems[0].amountCents == totalCents) {
                    switchSplitBill?.isChecked = false
                    val single = parsedItems[0]
                    etRemark?.setText(single.name)
                    if (!single.categoryName.isNullOrBlank()) {
                        formController.applyCategoryName(single.categoryName, "AI", null)
                        item.categoryName = single.categoryName
                    }
                    splitItemIds.remove(item.id)
                    Utils.toast(ctx, "识别为单笔消费，已自动转为普通记账")
                    return@launch
                }
                val fallbackCategory = preview.fallbackCategoryName
                    ?.takeUnless { it.isBlank() || it == "null" } ?: "其他"
                showSplitPreviewDialog(item, totalCents, parsedItems, fallbackCategory)
            } catch (e: Exception) {
                Utils.toast(ctx, "图片拆分异常：${e.message ?: e.javaClass.simpleName}")
            } finally {
                btnSplitPreview?.text = "拆单预览"
                btnSplitPreview?.isEnabled = true
                isBusy = false
            }
        }
    }

    private fun showSplitPreviewDialog(
        item: CapturePluginClient.PendingCaptureItem,
        totalCents: Long,
        items: MutableList<ParsedSplitItem>,
        fallbackCategory: String,
    ) {
        val dialogView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(Color.WHITE, dp(18), dp(1), Color.parseColor("#E2E8F0"))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val titleText = TextView(ctx).apply {
            text = "拆分记账预览"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleText)
        dialogView.addView(header)

        val tvSub = TextView(ctx).apply {
            text = "${item.sourceLabel()} · 应付总额: ¥${String.format(Locale.CHINA, "%.2f", totalCents / 100.0)}"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(8))
        }
        dialogView.addView(tvSub)

        val maxScrollHeight = (ctx.resources.displayMetrics.heightPixels * 0.40f).toInt()
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val capped = MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            }
        }
        val itemsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(itemsContainer)
        dialogView.addView(scroll)

        val summaryText = TextView(ctx).apply {
            textSize = 12.5f
            setPadding(0, dp(8), 0, dp(4))
        }
        dialogView.addView(summaryText)

        val actionsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        var btnAddAdjustment: View? = null

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnCancelDialog = smallChipButton("改备注", Color.parseColor("#64748B"), Color.parseColor("#F1F5F9")) {}
        btnCancelDialog.textSize = 13f
        btnCancelDialog.setPadding(dp(14), dp(10), dp(14), dp(10))

        val btnConfirm = TextView(ctx).apply {
            text = "确认入账"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rippleBg(ctx.getColor(R.color.brand_primary), dp(10))
        }
        footer.addView(btnCancelDialog, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply { marginEnd = dp(8) })
        footer.addView(btnConfirm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        dialogView.addView(footer)

        lateinit var updateSummary: () -> Unit
        lateinit var renderItems: () -> Unit

        updateSummary = {
            val curSum = items.sumOf { it.amountCents }
            val diff = totalCents - curSum
            btnAddAdjustment?.visibility = if (diff > 0L) View.VISIBLE else View.GONE
            val sumYuan = curSum / 100.0
            val totalYuan = totalCents / 100.0
            val diffYuan = Math.abs(diff) / 100.0

            when {
                diff == 0L -> {
                    summaryText.text = String.format(Locale.CHINA, "已拆合计 ¥%.2f / 付款 ¥%.2f (已配平)", sumYuan, totalYuan)
                    summaryText.setTextColor(Color.parseColor("#059669"))
                    btnConfirm.alpha = 1.0f
                }
                diff > 0L -> {
                    summaryText.text = String.format(Locale.CHINA, "已拆合计 ¥%.2f / 付款 ¥%.2f (还差 ¥%.2f)", sumYuan, totalYuan, diffYuan)
                    summaryText.setTextColor(Color.parseColor("#D97706"))
                    btnConfirm.alpha = 0.5f
                }
                else -> {
                    summaryText.text = String.format(Locale.CHINA, "已拆合计 ¥%.2f / 付款 ¥%.2f (超出 ¥%.2f)", sumYuan, totalYuan, diffYuan)
                    summaryText.setTextColor(Color.parseColor("#DC2626"))
                    btnConfirm.alpha = 0.5f
                }
            }
            btnConfirm.text = "确认入账 (${items.size}笔)"
        }

        val btnAddRow = smallChipButton("+ 手动加一项", Color.parseColor("#334155"), Color.parseColor("#F1F5F9")) {
            items.add(ParsedSplitItem("", 0L, fallbackCategory, false))
            renderItems()
        }
        actionsRow.addView(
            btnAddRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        )
        btnAddAdjustment = smallChipButton("+ 补差额调整项", ctx.getColor(R.color.brand_primary_dark), ctx.getColor(R.color.brand_primary_soft)) {
            val curSum = items.sumOf { it.amountCents }
            val diff = totalCents - curSum
            if (diff > 0L) {
                items.add(ParsedSplitItem("差额调整", diff, fallbackCategory, true))
                renderItems()
            }
        }
        actionsRow.addView(btnAddAdjustment)
        dialogView.addView(actionsRow, dialogView.indexOfChild(summaryText) + 1)

        renderItems = {
            itemsContainer.removeAllViews()
            items.forEachIndexed { index, subItem ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }

                val etName = EditText(ctx).apply {
                    setText(subItem.name)
                    hint = "名称"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#0F172A"))
                    setHintTextColor(Color.parseColor("#94A3B8"))
                    isSingleLine = true
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = roundedBg(Color.parseColor("#F8FAFC"), dp(6), dp(1), Color.parseColor("#CBD5E1"))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            if (index < items.size) {
                                items[index].name = s?.toString()?.trim().orEmpty()
                            }
                        }
                    })
                }
                row.addView(etName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { marginEnd = dp(6) })

                val etAmt = EditText(ctx).apply {
                    val amtYuan = subItem.amountCents / 100.0
                    if (amtYuan > 0) setText(String.format(Locale.CHINA, "%.2f", amtYuan))
                    hint = "金额"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#0F172A"))
                    setHintTextColor(Color.parseColor("#94A3B8"))
                    isSingleLine = true
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = roundedBg(Color.parseColor("#F8FAFC"), dp(6), dp(1), Color.parseColor("#CBD5E1"))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            val cents = (s?.toString()?.toDoubleOrNull()?.times(100.0))?.roundToLong() ?: 0L
                            if (index < items.size) {
                                items[index].amountCents = cents
                                updateSummary()
                            }
                        }
                    })
                }
                row.addView(etAmt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { marginEnd = dp(6) })

                val hasCat = !subItem.categoryName.isNullOrBlank()
                val catChip = smallChipButton(
                    text = subItem.categoryName?.takeIf { it.isNotBlank() } ?: "选分类",
                    textColor = Color.parseColor(if (hasCat) "#059669" else "#D97706"),
                    bgColor = Color.parseColor(if (hasCat) "#ECFDF5" else "#FFFBEB"),
                ) {
                    OverlayDialogs.showGridCategoryPicker(
                        ctx = ctx,
                        currentSelectionText = subItem.categoryName.orEmpty(),
                        type = item.billType ?: LocalBillType.TYPE_EXPENSE,
                        allowClear = false,
                        onCancel = {},
                    ) { chosenCat ->
                        if (chosenCat.isNotBlank() && index < items.size) {
                            items[index].categoryName = chosenCat
                            renderItems()
                        }
                    }
                }
                row.addView(catChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })

                val btnDel = TextView(ctx).apply {
                    text = "✕"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener {
                        if (items.size > 1 && index < items.size) {
                            items.removeAt(index)
                            renderItems()
                        } else {
                            Utils.toast(ctx, "至少保留一项明细")
                        }
                    }
                }
                row.addView(btnDel)
                itemsContainer.addView(row)
            }
            updateSummary()
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
            .setView(dialogView)
            .create()

        header.addView(
            TextView(ctx).apply {
                text = "✕"
                textSize = 15f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { dialog.dismiss() }
            }
        )

        btnCancelDialog.setOnClickListener {
            dialog.dismiss()
            etRemark?.requestFocus()
        }

        btnConfirm.setOnClickListener {
            val curSum = items.sumOf { it.amountCents }
            val diff = totalCents - curSum
            if (diff != 0L) {
                if (diff > 0L) {
                    Utils.toast(ctx, "金额未配平，还差 ¥${String.format(Locale.CHINA, "%.2f", diff / 100.0)}，可点击「补差额调整项」")
                } else {
                    Utils.toast(ctx, "金额超出付款 ¥${String.format(Locale.CHINA, "%.2f", -diff / 100.0)}，禁止保存")
                }
                return@setOnClickListener
            }
            if (items.any { it.amountCents <= 0L }) {
                Utils.toast(ctx, "所有明细金额必须大于 0")
                return@setOnClickListener
            }
            if (items.any { it.name.isBlank() }) {
                Utils.toast(ctx, "请补齐所有明细的名称")
                return@setOnClickListener
            }
            if (items.any { it.categoryName.isNullOrBlank() }) {
                Utils.toast(ctx, "有条目缺少分类，请先选择分类")
                return@setOnClickListener
            }

            btnConfirm.isEnabled = false
            btnConfirm.text = "入账中…"
            scope.launch {
                try {
                    val groupItems = JSONArray()
                    items.forEach { sub ->
                        groupItems.put(
                            JSONObject().apply {
                                put("name", sub.name)
                                put("amountCents", sub.amountCents)
                                put("categoryName", sub.categoryName)
                            }
                        )
                    }
                    val payload = JSONObject().apply {
                        put("captureId", item.id)
                        put("type", item.billType ?: LocalBillType.TYPE_EXPENSE)
                        put("subType", item.subType ?: LocalBillType.SUBTYPE_NORMAL)
                        put("needsRefundLink", item.needsRefundLink)
                        put("totalCents", totalCents)
                        put("currency", item.currency.ifBlank { "CNY" })
                        put("paidAt", item.paidAt)
                        putOpt("assetName", item.displayAsset())
                        putOpt("bookName", item.bookName)
                        put("items", groupItems)
                    }
                    val requestId = UUID.randomUUID().toString()
                    val importRes = withContext(Dispatchers.IO) {
                        CaptureImportService.importBillGroup(ctx, payload, requestId)
                    }
                    if (!importRes.optBoolean("ok", false)) {
                        val err = importRes.optString("error", "import_failed")
                        val msg = importRes.optString("message", "入账失败")
                        Utils.toast(ctx, "拆分入账失败：${humanizeSplitError(err, msg)}")
                        btnConfirm.isEnabled = true
                        btnConfirm.text = "确认入账 (${items.size}笔)"
                        return@launch
                    }

                    val ruleEntries = items.map {
                        RuleLearningManager.RuleLearnEntry(
                            note = it.name,
                            categoryName = it.categoryName.orEmpty(),
                            billType = item.billType ?: LocalBillType.TYPE_EXPENSE,
                        )
                    }
                    val asked = com.taostudio.tapaccounting.logic.RuleAskCoordinator
                        .showIfNeeded(ctx, ruleEntries)
                    if (!asked) {
                        RuleLearningManager.scheduleSplit(ctx, item.id, ruleEntries)
                        showSplitResultCard(items)
                    }

                    CapturePluginClient.markCapturesRecorded(ctx, listOf(item.id))
                    Utils.toast(ctx, "✓ 已拆成 ${items.size} 笔入账成功")
                    dialog.dismiss()

                    pendingItems.removeAll { it.id == item.id }
                    if (pendingItems.isEmpty()) {
                        pendingDraft = null
                        pendingDraftItemId = null
                        hasPendingDraft = false
                        clearPendingContext()
                        onCloseOverlay(true)
                    } else {
                        currentIndex = currentIndex.coerceIn(0, pendingItems.lastIndex)
                        applyItemToSingleForm(currentIndex)
                    }
                } catch (e: Exception) {
                    Utils.toast(ctx, "入账异常：${e.message ?: e.javaClass.simpleName}")
                    btnConfirm.isEnabled = true
                    btnConfirm.text = "确认入账 (${items.size}笔)"
                }
            }
        }

        renderItems()

        if (ctx !is Activity) {
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE
            )
        }
        dialog.show()
    }

    /** 拆分入账结果卡：逐条展示明细 + 分类；ASK 模式已经弹过规则询问时不再重复。 */
    private fun showSplitResultCard(items: List<ParsedSplitItem>) {
        if (items.isEmpty()) return
        val message = items.take(10).joinToString("\n") { item ->
            val cat = item.categoryName?.takeIf { it.isNotBlank() } ?: "未分类"
            "· ${item.name.take(16)}  ${String.format(Locale.CHINA, "¥%.2f", item.amountCents / 100.0)}  $cat"
        } + if (items.size > 10) "\n… 还有 ${items.size - 10} 条" else ""
        val dialog = AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
            .setTitle("已拆成 ${items.size} 笔")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .create()
        if (ctx !is Activity) {
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE
            )
        }
        dialog.show()
    }

    /** 批量入账结果卡：逐笔展示金额 + 分类；ASK 模式已经弹过规则询问时不再重复。 */
    private fun showBatchResultCard(
        items: List<CapturePluginClient.PendingCaptureItem>,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (items.isEmpty()) {
            onDismiss?.invoke()
            return
        }
        val message = items.take(10).joinToString("\n") { item ->
            val cat = item.categoryName?.takeIf { it.isNotBlank() } ?: "未分类"
            "· ${String.format(Locale.CHINA, "¥%.2f", item.amountCents / 100.0)}  $cat"
        } + if (items.size > 10) "\n… 还有 ${items.size - 10} 笔" else ""
        val dialog = AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
            .setTitle("本次入账 ${items.size} 笔")
            .setMessage(message)
            .setPositiveButton("知道了") { _, _ -> onDismiss?.invoke() }
            .setOnCancelListener { onDismiss?.invoke() }
            .create()
        if (ctx !is Activity) {
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE
            )
        }
        dialog.show()
    }

    private fun humanizeSplitError(error: String?, message: String?): String {
        val code = error?.trim().orEmpty()
        val fallback = message?.trim().orEmpty()
        return when {
            code == "split_empty" -> fallback.ifEmpty { "没从备注里拆出条目，请把备注写成「苹果30 雪梨40」" }
            code == "split_total_exceeds" -> fallback.ifEmpty { "明细合计超过付款，请改备注" }
            code == "split_total_mismatch" -> fallback.ifEmpty { "拆分合计与付款不一致，请重新确认" }
            code == "split_amount_missing" -> fallback.ifEmpty { "有多项缺金额，请补金额或改备注" }
            code == "split_amount_invalid" -> fallback.ifEmpty { "有条目金额无效，请改备注" }
            code == "split_unsupported_type" -> fallback.ifEmpty { "转账/还款/退款不支持文字拆分" }
            code == "ai_not_configured" -> fallback.ifEmpty { "请先在敲敲记账设置中配置 AI API Key" }
            code == "ai_timeout" -> fallback.ifEmpty { "AI 拆分超时，请重试或改备注" }
            code == "split_ai_failed" -> fallback.ifEmpty { "AI 拆分失败，请检查 AI 设置或改备注" }
            code == "missing_category" -> fallback.ifEmpty { "有条目缺少分类，请先选择分类" }
            code.startsWith("category_not_found") -> fallback.ifEmpty { "分类在敲敲记账中不存在" }
            else -> fallback.ifEmpty { code }
        }
    }
}
