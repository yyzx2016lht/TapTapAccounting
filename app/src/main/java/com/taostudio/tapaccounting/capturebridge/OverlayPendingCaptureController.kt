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
 * - 有待记时：自动将最新一笔带入记账悬浮窗，并在顶部展示提示横幅；
 * - 支持在同一个悬浮窗内展开「多笔连填备注 + 实时本地规则匹配 + 一键智能归类入账」抽屉。
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

    private val formContent: LinearLayout? = rootFloatingView.findViewById(R.id.layout_form_content)
    private val formBody: View? = rootFloatingView.findViewById(R.id.layout_form_body)
    private val amountRow: View? = formContent?.getChildAt(0)

    private val hostContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(10)
        }
    }

    private var pendingItems: MutableList<CapturePluginClient.PendingCaptureItem> = mutableListOf()
    private var currentIndex: Int = 0
    private var isBatchDrawerOpen: Boolean = false
    private var isBusy: Boolean = false
    private val noteSyncJobs = mutableMapOf<String, Job>()

    init {
        formContent?.addView(hostContainer, 0)
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
                hostContainer.visibility = View.GONE
                return@launch
            }
            pendingItems = items.toMutableList()
            currentIndex = 0
            hostContainer.visibility = View.VISIBLE
            if (startInBatchMode && pendingItems.size > 1) {
                openBatchDrawer()
            } else {
                applyItemToSingleForm(0)
            }
        }
    }

    fun destroy() {
        noteSyncJobs.values.forEach { it.cancel() }
        scope.cancel()
    }

    private fun applyItemToSingleForm(index: Int) {
        if (pendingItems.isEmpty()) {
            dismissToBlankForm()
            return
        }
        currentIndex = index.coerceIn(0, pendingItems.lastIndex)
        isBatchDrawerOpen = false
        setSingleFormVisible(true)

        val item = pendingItems[currentIndex]
        val prefill = CapturePluginClient.toPrefillJson(item)
        formController.updateCaptureContext(item.id, UUID.randomUUID().toString())
        formController.fillDataToUi(prefill, showToast = false, forceMultiMode = false)
        renderSingleBanner()
    }

    private fun dismissToBlankForm() {
        isBatchDrawerOpen = false
        setSingleFormVisible(true)
        hostContainer.visibility = View.GONE
        formController.resetToBlankBill()
    }

    private fun setSingleFormVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        amountRow?.visibility = v
        formBody?.visibility = v
    }

    // ════════════════════════════════════════════════════════
    //  顶部单笔提示条
    // ════════════════════════════════════════════════════════

    private fun renderSingleBanner() {
        hostContainer.removeAllViews()
        val item = pendingItems.getOrNull(currentIndex) ?: return
        val otherCount = (pendingItems.size - 1).coerceAtLeast(0)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(Color.parseColor("#F8FAFF"), dp(13), dp(1), Color.parseColor("#BFDBFE"))
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val isAlipay = item.source == CaptureSource.ALIPAY
        val badge = TextView(ctx).apply {
            text = item.sourceLabel()
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isAlipay) "#1677FF" else "#07C160"))
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = roundedBg(
                fillColor = Color.parseColor(if (isAlipay) "#EFF6FF" else "#ECFDF5"),
                radiusPx = dp(6),
            )
        }
        topRow.addView(badge)

        val merchantPart = item.merchant?.let { " · ${it.take(8)}" }.orEmpty()
        val titleText = if (otherCount > 0) {
            " 已带入 ${item.formattedTime()} ${item.formattedAmount()}$merchantPart (还有${otherCount}笔)"
        } else {
            " 已带入 ${item.formattedTime()} ${item.formattedAmount()}$merchantPart"
        }

        val tvInfo = TextView(ctx).apply {
            text = titleText
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
        }
        topRow.addView(tvInfo)
        card.addView(topRow)

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        if (pendingItems.size > 1) {
            actionRow.addView(
                smallChipButton(
                    text = "⇄ 批量速清 / 智能归类 (${pendingItems.size})",
                    textColor = Color.WHITE,
                    bgColor = Color.parseColor("#2563EB"),
                ) {
                    openBatchDrawer()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
            )
        } else {
            actionRow.addView(
                smallChipButton(
                    text = if (isBusy) "归类中…" else "✨ 智能归类此笔",
                    textColor = Color.parseColor("#1D4ED8"),
                    bgColor = Color.parseColor("#DBEAFE"),
                ) {
                    runBatchAiClassification(singleOnly = true)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
            )
        }

        actionRow.addView(
            smallChipButton(
                text = "不计这笔",
                textColor = Color.parseColor("#DC2626"),
                bgColor = Color.parseColor("#FEF2F2"),
            ) {
                ignoreItem(currentIndex)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        )

        actionRow.addView(
            smallChipButton(
                text = "记新账",
                textColor = Color.parseColor("#475569"),
                bgColor = Color.parseColor("#EEF2F6"),
            ) {
                dismissToBlankForm()
            }
        )

        card.addView(actionRow)
        hostContainer.addView(card)
    }

    // ════════════════════════════════════════════════════════
    //  多笔待记速清 & 一键智能归类入账面板
    // ════════════════════════════════════════════════════════

    private fun openBatchDrawer() {
        isBatchDrawerOpen = true
        setSingleFormVisible(false)
        renderBatchDrawer()
    }

    private fun renderBatchDrawer() {
        hostContainer.removeAllViews()
        if (pendingItems.isEmpty()) {
            dismissToBlankForm()
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
                applyItemToSingleForm(currentIndex)
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
                dismissToBlankForm()
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
            setTextColor(Color.parseColor("#1D4ED8"))
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rippleBg(
                fillColor = Color.parseColor("#EFF6FF"),
                radiusPx = dp(11),
                strokeWidthPx = dp(1),
                strokeColor = Color.parseColor("#BFDBFE"),
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
            background = rippleBg(Color.parseColor("#2563EB"), dp(11))
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

        hostContainer.addView(panel)
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
            applyItemToSingleForm(index)
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
                textColor = Color.parseColor("#1D4ED8"),
                bgColor = Color.parseColor("#EFF6FF"),
            ) {
                applyItemToSingleForm(index)
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
            val res = CapturePluginClient.updateCaptureDraft(ctx, item.id, note = note)
            if (res.ok && res.categoryName != item.categoryName) {
                item.categoryName = res.categoryName
                onCategoryUpdated(res.categoryName)
            }
        }
    }

    private fun ignoreItem(index: Int) {
        val target = pendingItems.getOrNull(index) ?: return
        scope.launch {
            CapturePluginClient.ignoreCapture(ctx, target.id)
            pendingItems.removeAt(index)
            if (pendingItems.isEmpty()) {
                Utils.toast(ctx, "所有待记已处理完毕")
                dismissToBlankForm()
            } else if (isBatchDrawerOpen) {
                renderBatchDrawer()
            } else {
                val nextIdx = index.coerceAtMost(pendingItems.lastIndex)
                applyItemToSingleForm(nextIdx)
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
        if (isBatchDrawerOpen) renderBatchDrawer() else renderSingleBanner()

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
                if (isBatchDrawerOpen) {
                    renderBatchDrawer()
                } else {
                    applyItemToSingleForm(currentIndex)
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
                        pendingItems.removeAll { it.id in recordedIds.toSet() }
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
