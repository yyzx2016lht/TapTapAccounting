package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.bridge.CaptureBridgeClient
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件里的拆分确认页（activity 用底部 Dialog，气泡用同内容的 overlay 卡片）。
 *
 * 只做展示与“补差额调整”，不写账；用户点“确认入账”后才回调 [onConfirm]。
 * 取消/关闭只关闭页面，待记仍是 PENDING。
 */
internal object SplitConfirmSheet {

    fun show(
        activity: Activity,
        capture: PaymentCapture,
        preview: CaptureBridgeClient.SplitPreviewResult,
        onEdit: () -> Unit,
        onConfirm: (List<CaptureBridgeClient.SplitItem>) -> Unit,
    ) {
        var dialog: Dialog? = null
        val content = Content(
            context = activity,
            capture = capture,
            preview = preview,
            onCancel = { dialog?.dismiss() },
            onEdit = {
                dialog?.dismiss()
                onEdit()
            },
            onConfirm = { items ->
                dialog?.dismiss()
                onConfirm(items)
            },
        )
        val sheet = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val container = FrameLayout(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { dialog?.dismiss() }
            }
            container.addView(
                content.view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
            content.view.setOnClickListener { }
            setContentView(container)
            setCanceledOnTouchOutside(true)
        }
        dialog = sheet
        sheet.setOnDismissListener { }
        sheet.show()
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.42f)
        }
    }

    /** 供气泡 overlay 复用；调用方负责把 view 放进自己的 WindowManager。 */
    fun buildOverlayContent(
        context: Context,
        capture: PaymentCapture,
        preview: CaptureBridgeClient.SplitPreviewResult,
        onCancel: () -> Unit,
        onEdit: () -> Unit,
        onConfirm: (List<CaptureBridgeClient.SplitItem>) -> Unit,
    ): View = Content(
        context = context,
        capture = capture,
        preview = preview,
        onCancel = onCancel,
        onEdit = onEdit,
        onConfirm = onConfirm,
    ).view

    private class Content(
        private val context: Context,
        private val capture: PaymentCapture,
        preview: CaptureBridgeClient.SplitPreviewResult,
        private val onCancel: () -> Unit,
        private val onEdit: () -> Unit,
        private val onConfirm: (List<CaptureBridgeClient.SplitItem>) -> Unit,
    ) {
        private val items = preview.items.toMutableList()
        private val totalCents = preview.totalCents
        private val fallbackCategory = preview.fallbackCategoryName
        private var diffCents = preview.diffCents

        private val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        private val summaryText = TextView(context)
        private val hintText = TextView(context)
        private val adjustButton = sheetButton("补差额调整", CaptureUi.BRAND_DARK, CaptureUi.BRAND_SOFT)
        private val confirmButton = sheetButton("确认入账", Color.WHITE, CaptureUi.BRAND)

        val view: LinearLayout = build()

        private fun build(): LinearLayout {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(10), dp(18), dp(16))
                background = CaptureUi.roundedBg(Color.WHITE, dp(22), dp(1), CaptureUi.BORDER)
            }
            root.addView(View(context).apply {
                setBackgroundColor(CaptureUi.BORDER)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(2)
                    bottomMargin = dp(12)
                }
            })

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(context).apply {
                text = "拆分记账 · ${formatCents(capture.amountCents)}"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(context).apply {
                text = " ✕ "
                textSize = 14f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { onCancel() }
            })
            root.addView(header)

            root.addView(TextView(context).apply {
                text = buildSubtitle()
                textSize = 11.5f
                setTextColor(CaptureUi.TEXT_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, dp(10))
            })

            val listScroll = ScrollView(context).apply {
                isFillViewport = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (items.size > 5) dp(250) else ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(listContainer)
            }
            root.addView(listScroll)

            summaryText.textSize = 12.5f
            summaryText.setPadding(0, dp(10), 0, 0)
            root.addView(summaryText)

            hintText.textSize = 12f
            hintText.setTextColor(CaptureUi.WARNING)
            hintText.setPadding(0, dp(6), 0, 0)
            root.addView(hintText)

            val adjustRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            adjustRow.addView(
                adjustButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
            adjustRow.addView(
                sheetButton("改", CaptureUi.TEXT_SECONDARY, CaptureUi.BG_SUBTLE) { onEdit() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f),
            )
            root.addView(adjustRow)

            val footer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            footer.addView(
                sheetButton("取消", CaptureUi.TEXT_SECONDARY, CaptureUi.BG_SUBTLE) { onCancel() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                    marginEnd = dp(8)
                },
            )
            footer.addView(
                confirmButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f),
            )
            root.addView(footer)

            adjustButton.setOnClickListener { addDifferenceAdjustment() }
            confirmButton.setOnClickListener {
                if (canConfirm()) onConfirm(items.toList())
            }
            render()
            return root
        }

        private fun buildSubtitle(): String = buildString {
            capture.merchant?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (isNotEmpty()) append(" · ")
            append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(capture.paidAt)))
            capture.assetName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }

        private fun render() {
            listContainer.removeAllViews()
            items.forEach { item ->
                listContainer.addView(buildItemRow(item))
            }
            val sum = items.sumOf { it.amountCents }
            val diff = totalCents - sum
            diffCents = diff
            val missingCount = items.count { it.categoryName.isNullOrBlank() }

            summaryText.text = buildString {
                append("合计 ").append(formatCents(sum))
                append(" / 付款 ").append(formatCents(totalCents))
                append(" · ").append(items.size).append(" 项")
            }
            summaryText.setTextColor(CaptureUi.TEXT_SECONDARY)

            val adjustCategoryLabel = fallbackCategory ?: "其他"
            when {
                diff > 0L && missingCount > 0 -> {
                    hintText.text = "还差 ${formatCents(diff)}，可补一笔“$adjustCategoryLabel”；另有 $missingCount 项未归类"
                    hintText.setTextColor(CaptureUi.WARNING)
                    hintText.visibility = View.VISIBLE
                }

                diff > 0L -> {
                    hintText.text = "还差 ${formatCents(diff)}：可补一笔“$adjustCategoryLabel”，或点“改”改备注"
                    hintText.setTextColor(CaptureUi.WARNING)
                    hintText.visibility = View.VISIBLE
                }

                diff < 0L -> {
                    hintText.text = "明细合计超出付款 ${formatCents(-diff)}，请点“改”改备注"
                    hintText.setTextColor(CaptureUi.DANGER)
                    hintText.visibility = View.VISIBLE
                }

                missingCount > 0 -> {
                    hintText.text = "有 $missingCount 项未归类，请点“改”改备注或先配置 AI/规则"
                    hintText.setTextColor(CaptureUi.WARNING)
                    hintText.visibility = View.VISIBLE
                }

                else -> {
                    hintText.visibility = View.GONE
                }
            }

            adjustButton.visibility = if (diff > 0L) View.VISIBLE else View.GONE
            adjustButton.text = if (fallbackCategory != null) {
                "补差额调整（$fallbackCategory）"
            } else {
                "补差额调整"
            }

            confirmButton.text = "确认入账 ${items.size} 笔"
            val enabled = canConfirm()
            confirmButton.isEnabled = enabled
            confirmButton.alpha = if (enabled) 1f else 0.45f
        }

        private fun buildItemRow(item: CaptureBridgeClient.SplitItem): View {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val left = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            left.addView(TextView(context).apply {
                text = item.name
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(CaptureUi.TEXT_PRIMARY)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            left.addView(TextView(context).apply {
                text = item.categoryName?.takeIf { it.isNotBlank() } ?: "待分类"
                textSize = 11.5f
                setTextColor(
                    if (item.categoryName.isNullOrBlank()) CaptureUi.WARNING else CaptureUi.TEXT_MUTED
                )
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            })
            row.addView(left)

            row.addView(TextView(context).apply {
                text = buildString {
                    append(formatCents(item.amountCents))
                    if (item.inferred) append(" · 按差额")
                }
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (item.inferred) CaptureUi.WARNING else CaptureUi.TEXT_PRIMARY)
                gravity = Gravity.END
            })
            return row
        }

        private fun addDifferenceAdjustment() {
            if (diffCents <= 0L) return
            val category = fallbackCategory
            if (category.isNullOrBlank()) {
                Toast.makeText(context, "敲敲记账没有“其他”分类，请点“改”或先建分类", Toast.LENGTH_SHORT).show()
                return
            }
            items.add(
                CaptureBridgeClient.SplitItem(
                    name = "补差额调整",
                    amountCents = diffCents,
                    categoryName = category,
                    inferred = false,
                )
            )
            render()
        }

        private fun canConfirm(): Boolean {
            val sum = items.sumOf { it.amountCents }
            return items.isNotEmpty() &&
                sum == totalCents &&
                items.all { !it.categoryName.isNullOrBlank() }
        }

        private fun sheetButton(
            text: String,
            textColor: Int,
            bgColor: Int,
            onClick: (() -> Unit)? = null,
        ): TextView = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = CaptureUi.rippleBg(bgColor, dp(11), dp(1), CaptureUi.BORDER)
            setOnClickListener { onClick?.invoke() }
        }

        private fun formatCents(cents: Long?): String =
            if (cents == null) "¥0.00" else "¥" + String.format(Locale.CHINA, "%.2f", cents / 100.0)

        private fun dp(value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
