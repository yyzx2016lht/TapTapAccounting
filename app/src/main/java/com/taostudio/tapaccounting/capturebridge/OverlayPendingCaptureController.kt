package com.taostudio.tapaccounting.capturebridge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.Utils
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

    private enum class FormMode { PENDING, NEW_BILL }

    private var mode: FormMode = FormMode.NEW_BILL
    private var pendingItems: MutableList<CapturePluginClient.PendingCaptureItem> = mutableListOf()
    private var currentIndex: Int = 0
    private var isBatchDrawerOpen: Boolean = false
    private var isBusy: Boolean = false
    private val noteSyncJobs = mutableMapOf<String, Job>()

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
        formController.setOnCategoryChangedListener { categoryName -> onSingleCategoryChanged(categoryName) }
        renderContextBar()
    }

    /**
     * 当悬浮窗打开时调用。
     * @param hasExplicitPrefill 调用方已经传了特定 prefill（比如从插件点“悬浮窗细改”跳过来），此时不再重复拉取覆盖。
     * @param startInBatchMode 是否直接展开多笔批量速清面板。
     */
    fun loadIfNeeded(hasExplicitPrefill: Boolean, startInBatchMode: Boolean = false) {
        if (hasExplicitPrefill) return
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
        }
    }

    fun destroy() {
        formController.setOnRemarkChangedListener(null)
        formController.setOnCategoryChangedListener(null)
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
        contextContainer?.visibility = View.GONE
        hideBatchPanel()
        setSingleFormVisible(true)
        formController.updateCaptureContext(null, null)
        formController.restoreDraft(newBillDraft)
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

    private fun switchToNewBillMode() {
        if (isBusy || mode == FormMode.NEW_BILL) return
        formController.hideAmountKeypad()
        snapshotPendingDraftIfNeeded()
        mode = FormMode.NEW_BILL
        isBatchDrawerOpen = false
        hideBatchPanel()
        setSingleFormVisible(true)
        formController.updateCaptureContext(null, null)
        formController.restoreDraft(newBillDraft)
        renderContextBar()
        animateFormSwap()
    }

    private fun switchToPendingMode() {
        if (isBusy || mode == FormMode.PENDING || pendingItems.isEmpty()) return
        formController.hideAmountKeypad()
        newBillDraft = formController.snapshotDraft()
        mode = FormMode.PENDING
        isBatchDrawerOpen = false
        hideBatchPanel()
        setSingleFormVisible(true)
        restorePendingDraftOrItem()
        renderContextBar()
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
        scheduleSingleNoteSync(item, note)
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
            text = "写备注会实时匹配本地分类规则；未命中项可点下方「智能归类」。没备注的账单保持待归类。"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(8))
        }
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

        // 底部主操作区
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val btnAiClassify = TextView(ctx).apply {
            text = if (isBusy) "归类中…" else "✨ 智能归类"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.brand_primary_dark))
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rippleBg(
                fillColor = ctx.getColor(R.color.brand_primary_soft),
                radiusPx = dp(11),
                strokeWidthPx = dp(1),
                strokeColor = ctx.getColor(R.color.brand_primary_border),
            )
            isEnabled = !isBusy
            setOnClickListener {
                runBatchAiClassification(singleOnly = false)
            }
        }

        val categorizedCount = pendingItems.count {
            !it.categoryName.isNullOrBlank() && it.amountCents > 0L
        }
        val btnCommitAll = TextView(ctx).apply {
            text = if (isBusy) "处理中…" else "✓ 一键入账 (${categorizedCount}笔)"
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

        footer.addView(
            btnAiClassify,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        )
        footer.addView(
            btnCommitAll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
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

        fun styleCatBadge(tv: TextView, cat: String?) {
            val hasCat = !cat.isNullOrBlank()
            tv.text = if (hasCat) "🏷️ $cat" else "待归类"
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
        styleCatBadge(catChip, item.categoryName)
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
        }
        editTexts.add(etNote)
        card.addView(etNote)

        return card
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
     * 轻量智能归类：只把备注与账单类型交给 [CaptureImportService.classifyNotes]，
     * 不再复用整笔记账分析；没备注的条目保持待归类。
     */
    private fun runBatchAiClassification(singleOnly: Boolean) {
        if (isBusy) return
        val scopeTargets = if (singleOnly) {
            listOfNotNull(pendingItems.getOrNull(currentIndex))
        } else {
            pendingItems.toList()
        }
        if (scopeTargets.isEmpty()) return

        if (Prefs.getAiKey(ctx).isBlank()) {
            Utils.toast(ctx, "请先在敲敲记账设置中配置 AI API Key")
            return
        }

        isBusy = true
        if (isBatchDrawerOpen) renderBatchDrawer() else renderContextBar()

        scope.launch {
            try {
                noteSyncJobs.values.forEach { it.join() }
                // 等防抖落库后重新看一遍：本地规则刚命中的、没备注的、转账都不调 AI。
                val targets = scopeTargets.filter {
                    !it.note.isNullOrBlank() &&
                        it.categoryName.isNullOrBlank() &&
                        it.billType != LocalBillType.TYPE_TRANSFER
                }
                if (targets.isEmpty()) {
                    Utils.toast(ctx, "没有可智能归类的备注；没备注的账单保持待归类")
                    return@launch
                }
                val inputArr = buildCaptureInputArray(targets)
                val res = withContext(Dispatchers.IO) {
                    CaptureImportService.classifyNotes(ctx, inputArr)
                }
                if (!res.optBoolean("ok", false)) {
                    Utils.toast(ctx, res.optString("message", "智能归类未完成"))
                    return@launch
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
                    CapturePluginClient.updateCaptureDraft(
                        context = ctx,
                        captureId = current.id,
                        note = current.note,
                        assetName = current.assetName,
                        categoryName = cat,
                        categorySource = "AI",
                    )
                    updatedCount++
                }
                Utils.toast(
                    ctx,
                    if (updatedCount == 0) {
                        "AI 暂无法判断，账单保持待归类"
                    } else {
                        "✨ 已智能归类 $updatedCount 笔"
                    }
                )
            } catch (e: Exception) {
                Utils.toast(ctx, "归类失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                isBusy = false
                when {
                    isBatchDrawerOpen -> renderBatchDrawer()
                    mode == FormMode.PENDING -> applyItemToSingleForm(currentIndex)
                    else -> renderContextBar()
                }
            }
        }
    }

    /**
     * 一键入账：只入账已有分类的待记；缺分类的不静默丢弃，
     * 入账完成后弹窗列出并逐笔补分类，取消/未选则保留 PENDING。
     */
    private fun commitAllPendingBills() {
        if (isBusy || pendingItems.isEmpty()) return
        isBusy = true
        renderBatchDrawer()

        scope.launch {
            try {
                noteSyncJobs.values.forEach { it.join() }
                val categorized = pendingItems.filter {
                    !it.categoryName.isNullOrBlank() && it.amountCents > 0L
                }

                val billsArray = JSONArray()
                categorized.forEach { item ->
                    val finalRemark = item.note?.takeIf { it.isNotBlank() }
                        ?: item.merchant?.takeIf { it.isNotBlank() }
                        ?: ""
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

                    if (recordedIds.isNotEmpty()) {
                        CapturePluginClient.markCapturesRecorded(ctx, recordedIds)
                        val previousCurrentId = pendingItems.getOrNull(currentIndex)?.id
                        val removedIds = recordedIds.toSet()
                        pendingItems.removeAll { it.id in removedIds }
                        if (pendingItems.isEmpty()) {
                            pendingDraft = null
                            pendingDraftItemId = null
                            hasPendingDraft = false
                        } else {
                            val retainedIndex = previousCurrentId
                                ?.let { id -> pendingItems.indexOfFirst { it.id == id } }
                                ?: -1
                            currentIndex = retainedIndex.takeIf { it >= 0 } ?: 0
                            if (previousCurrentId == null || previousCurrentId in removedIds) {
                                pendingDraft = null
                                pendingDraftItemId = null
                                hasPendingDraft = false
                            }
                        }
                    }
                }

                if (recordedIds.isNotEmpty()) {
                    Utils.toast(ctx, "✓ 已入账 ${recordedIds.size} 笔")
                } else if (categorized.isEmpty()) {
                    Utils.toast(ctx, "没有已分类的账单可入账")
                } else {
                    val reason = when {
                        firstError == "missing_category" -> "请先点「智能归类」或写备注匹配分类"
                        firstError?.startsWith("asset_not_found:") == true ->
                            "账户「${firstError.removePrefix("asset_not_found:")}」在敲敲记账中不存在"
                        !firstError.isNullOrBlank() -> firstError
                        else -> "请先补齐分类"
                    }
                    Utils.toast(ctx, "暂未入账：$reason")
                }

                val missing = pendingItems.filter {
                    it.categoryName.isNullOrBlank() && it.billType != LocalBillType.TYPE_TRANSFER
                }
                when {
                    missing.isNotEmpty() -> promptMissingCategories(missing)
                    pendingItems.isEmpty() && recordedIds.isNotEmpty() -> onCloseOverlay(true)
                    else -> renderBatchDrawer()
                }
            } catch (e: Exception) {
                Utils.toast(ctx, "批量入账失败：${e.message ?: e.javaClass.simpleName}")
                renderBatchDrawer()
            } finally {
                isBusy = false
            }
        }
    }

    /** 一键入账后缺分类的列表弹窗；点“逐笔补分类”进入顺序补分类。 */
    private fun promptMissingCategories(items: List<CapturePluginClient.PendingCaptureItem>) {
        if (items.isEmpty()) {
            renderBatchDrawer()
            return
        }
        val preview = buildString {
            items.take(6).forEach { item ->
                append("· ").append(item.formattedAmount()).append("  ")
                append(item.merchant?.takeIf { it.isNotBlank() } ?: "未知商户")
                item.note?.takeIf { it.isNotBlank() }?.let {
                    append("（").append(it.take(12)).append("）")
                }
                append('\n')
            }
            if (items.size > 6) append("… 还有 ${items.size - 6} 笔")
        }
        AlertDialog.Builder(ContextThemeWrapper(ctx, R.style.Theme_TapAccounting))
            .setTitle("${items.size} 笔缺少分类，已跳过入账")
            .setMessage("以下待记仍保留在列表里，需逐笔补分类：\n\n$preview")
            .setPositiveButton("逐笔补分类") { _, _ ->
                promptMissingCategoryAt(items, 0, 0)
            }
            .setNegativeButton("稍后") { _, _ -> renderBatchDrawer() }
            .setOnCancelListener { renderBatchDrawer() }
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
            renderBatchDrawer()
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
                    )
                    if (res.ok) {
                        item.categoryName = res.categoryName ?: selected
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
}
