package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.contract.CaptureSources
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CaptureEvent
import com.taostudio.tapaccounting.capture.history.AutoAccountingHistoryImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史数据页：查看所有原始事件、规则命中情况，并可打开原始 JSON。
 *
 * 这里不做编辑/入账；待记仍在 PendingListActivity。页面只负责展示与本地筛选，
 * 数据来源与导入流程保持原有实现。
 */
class CaptureHistoryActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayLabelFormat = SimpleDateFormat("MM-dd EEE", Locale.getDefault())

    private lateinit var summaryTotal: TextView
    private lateinit var summaryMatched: TextView
    private lateinit var summaryToday: TextView
    private lateinit var summaryCaption: TextView
    private lateinit var list: LinearLayout
    private lateinit var importButton: TextView

    private var filterTabs: List<TextView> = emptyList()
    private var filter = FILTER_ALL
    private var loadedEvents: List<CaptureEvent> = emptyList()
    private var totalCount = 0
    private var matchedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        CaptureUi.applyLightStatusBar(this)
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildRoot(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CaptureUi.BG_PAGE)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CaptureUi.BG_SURFACE)
            setPadding(dp(4), dp(6), dp(6), dp(6))
            elevation = dp(2).toFloat()
            minimumHeight = dp(56)
        }
        topBar.addView(
            CaptureUi.iconButton(
                context = this,
                res = R.drawable.ic_chevron_left,
                tint = CaptureUi.TEXT_PRIMARY,
                contentDescription = "返回",
            ) { finish() },
        )

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(2), 0, dp(8), 0)
        }
        titleBlock.addView(TextView(this).apply {
            text = "历史数据"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        titleBlock.addView(TextView(this).apply {
            text = "原始捕获事件 · 只读"
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(1), 0, 0)
        })
        topBar.addView(titleBlock)

        importButton = CaptureUi.button(
            activity = this,
            text = "导入",
            textColor = CaptureUi.BRAND_DARK,
            bgColor = CaptureUi.BRAND_SOFT,
            strokeColor = CaptureUi.BRAND_BORDER,
            radiusDp = 10,
            paddingH = 12,
            paddingV = 8,
            textSizeSp = 12.5f,
        ) { importHistory() }.apply {
            minimumHeight = dp(40)
        }
        topBar.addView(
            importButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(2) },
        )

        topBar.addView(
            CaptureUi.iconButton(
                context = this,
                res = R.drawable.ic_refresh,
                tint = CaptureUi.TEXT_SECONDARY,
                contentDescription = "刷新",
            ) { load() },
        )
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(32))
        }
        content.addView(buildSummaryCard())
        content.addView(buildFilterRow())

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun buildSummaryCard(): View {
        val card = CaptureUi.card(
            activity = this,
            paddingH = 16,
            paddingV = 14,
            radiusDp = 16,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addMetric(label: String, valueColor: Int): TextView {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                text = "—"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(valueColor)
            }
            column.addView(value)
            column.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(CaptureUi.TEXT_MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(column)
            return value
        }

        summaryTotal = addMetric("原始事件", CaptureUi.TEXT_PRIMARY)
        summaryMatched = addMetric("规则命中", CaptureUi.SUCCESS)
        summaryToday = addMetric("今天新增", CaptureUi.BRAND_DARK)
        card.addView(row)

        summaryCaption = TextView(this).apply {
            text = "最近显示 0 条 · 点击事件查看原始 JSON"
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(summaryCaption)
        return card
    }

    private fun buildFilterRow(): View {
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = CaptureUi.roundedBg(CaptureUi.BG_SEGMENT, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        filterTabs = listOf(
            "全部" to FILTER_ALL,
            "已命中" to FILTER_MATCHED,
            "未命中" to FILTER_UNMATCHED,
        ).map { (label, value) ->
            TextView(this).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                setOnClickListener {
                    if (filter != value) {
                        filter = value
                        updateFilterTabs()
                        renderFiltered()
                    }
                }
            }.also { track.addView(it) }
        }
        updateFilterTabs()
        return track
    }

    private fun updateFilterTabs() {
        filterTabs.forEachIndexed { index, tab ->
            val selected = index == filter
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.setTextColor(if (selected) CaptureUi.TEXT_PRIMARY else CaptureUi.TEXT_SECONDARY)
            tab.background = if (selected) {
                CaptureUi.roundedBg(Color.WHITE, dp(9), dp(1), CaptureUi.BORDER)
            } else {
                null
            }
            tab.elevation = if (selected) dp(1).toFloat() else 0f
        }
    }

    private fun load() {
        scope.launch {
            val app = CaptureApp.from(this@CaptureHistoryActivity)
            val events = withContext(Dispatchers.IO) { app.eventRepository.listRecent(500) }
            val total = withContext(Dispatchers.IO) { app.eventRepository.count() }
            val matched = withContext(Dispatchers.IO) { app.eventRepository.countMatched() }
            totalCount = total
            matchedCount = matched
            render(events)
        }
    }

    private fun render(events: List<CaptureEvent>) {
        loadedEvents = events
        val todayCount = events.count { isToday(it.receivedAt) }

        summaryTotal.text = totalCount.toString()
        summaryMatched.text = if (totalCount > 0) {
            "${matchedCount * 100 / totalCount}%"
        } else {
            "—"
        }
        summaryToday.text = todayCount.toString()
        summaryCaption.text = if (events.isEmpty()) {
            "还没有历史事件"
        } else {
            "最近显示 ${events.size} 条 · 点击事件查看原始 JSON"
        }
        renderFiltered()
    }

    private fun renderFiltered() {
        list.removeAllViews()
        val filtered = when (filter) {
            FILTER_MATCHED -> loadedEvents.filter { it.matched }
            FILTER_UNMATCHED -> loadedEvents.filterNot { it.matched }
            else -> loadedEvents
        }

        if (filtered.isEmpty()) {
            val noEvents = loadedEvents.isEmpty()
            list.addView(
                buildEmptyCard(
                    title = if (noEvents) "还没有历史事件" else "没有符合筛选的事件",
                    desc = if (noEvents) {
                        "微信/支付宝付款后会自动留底到这里；\n也可以点右上角「导入」从自动记账导入。"
                    } else {
                        "切换到「全部」查看最近捕获的事件。"
                    },
                    actionText = if (noEvents) "导入自动记账历史" else "查看全部",
                    action = if (noEvents) {
                        { importHistory() }
                    } else {
                        {
                            filter = FILTER_ALL
                            updateFilterTabs()
                            renderFiltered()
                        }
                    },
                ),
            )
            return
        }

        val todayKey = dayKeyFormat.format(Date())
        val yesterdayKey = dayKeyFormat.format(Date(System.currentTimeMillis() - DAY_MILLIS))
        filtered.groupBy { dayKeyFormat.format(Date(it.receivedAt)) }
            .forEach { (key, dayEvents) ->
                val label = when (key) {
                    todayKey -> "今天 · ${key.substring(5)}"
                    yesterdayKey -> "昨天 · ${key.substring(5)}"
                    else -> dayLabelFormat.format(Date(dayEvents.first().receivedAt))
                }
                list.addView(dayHeader(label, dayEvents.size))
                dayEvents.forEach { event -> list.addView(renderEvent(event)) }
            }
    }

    private fun dayHeader(label: String, count: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(8), dp(2), dp(6))
        addView(TextView(this@CaptureHistoryActivity).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@CaptureHistoryActivity).apply {
            text = "$count 条"
            textSize = 11f
            setTextColor(CaptureUi.TEXT_MUTED)
        })
    }

    private fun renderEvent(event: CaptureEvent): View {
        val card = CaptureUi.card(
            activity = this,
            paddingH = 13,
            paddingV = 11,
            radiusDp = 14,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            background = CaptureUi.rippleBg(
                fillColor = CaptureUi.BG_SURFACE,
                radiusPx = dp(14),
                strokeWidthPx = dp(1),
                strokeColor = CaptureUi.BORDER,
            )
            setOnClickListener { showRaw(event) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val isWechat = event.source == CaptureSource.WECHAT
        val isAlipay = event.source == CaptureSource.ALIPAY
        topRow.addView(TextView(this).apply {
            text = sourceLabel(event.source)
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when {
                    isWechat -> CaptureUi.WECHAT
                    isAlipay -> CaptureUi.ALIPAY
                    else -> CaptureUi.TEXT_SECONDARY
                }
            )
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = CaptureUi.roundedBg(
                when {
                    isWechat -> CaptureUi.WECHAT_SOFT
                    isAlipay -> CaptureUi.ALIPAY_SOFT
                    else -> CaptureUi.BG_SUBTLE
                },
                dp(6),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(7) }
        })

        topRow.addView(TextView(this).apply {
            text = event.merchant?.takeIf { it.isNotBlank() } ?: typeLabel(event)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        })

        val amount = event.amountCents
        val isRefund = event.localSubType == LocalBillType.SUBTYPE_REFUND
        val isIncome = event.localType == LocalBillType.TYPE_INCOME
        val isTransfer = event.localType == LocalBillType.TYPE_TRANSFER
        val prefix = when {
            isIncome || isRefund -> "+"
            isTransfer || event.localType == null -> ""
            else -> "-"
        }
        topRow.addView(TextView(this).apply {
            text = amount?.let { "$prefix${formatAmount(it)}" } ?: "—"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                when {
                    isIncome || isRefund -> CaptureUi.SUCCESS
                    amount == null -> CaptureUi.TEXT_MUTED
                    else -> CaptureUi.TEXT_PRIMARY
                }
            )
        })
        card.addView(topRow)

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val meta = buildString {
            append(timeFormat.format(Date(event.receivedAt)))
            append(" · ").append(typeLabel(event))
            event.channel?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        metaRow.addView(TextView(this).apply {
            text = meta
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        })
        metaRow.addView(TextView(this).apply {
            text = if (event.matched) "已命中" else "未命中"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (event.matched) CaptureUi.SUCCESS else CaptureUi.TEXT_SECONDARY)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = if (event.matched) {
                CaptureUi.roundedBg(CaptureUi.SUCCESS_SOFT, dp(6), dp(1), CaptureUi.SUCCESS_BORDER)
            } else {
                CaptureUi.roundedBg(CaptureUi.BG_SUBTLE, dp(6), dp(1), CaptureUi.BORDER)
            }
        })
        card.addView(metaRow)

        val ruleLine = buildString {
            append("规则：").append(event.ruleName?.takeIf { it.isNotBlank() } ?: "—")
            event.categoryName?.takeIf { it.isNotBlank() }?.let {
                append(" · 分类：").append(it)
            }
        }
        card.addView(TextView(this).apply {
            text = ruleLine
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(5), 0, 0)
        })
        return card
    }

    private fun buildEmptyCard(
        title: String,
        desc: String,
        actionText: String?,
        action: (() -> Unit)?,
    ): View {
        val card = CaptureUi.card(
            activity = this,
            paddingH = 22,
            paddingV = 30,
            radiusDp = 16,
        ).apply {
            gravity = Gravity.CENTER
        }

        val badge = FrameLayout(this).apply {
            background = CaptureUi.roundedBg(
                CaptureUi.BG_SUBTLE,
                dp(999),
                dp(1),
                CaptureUi.BORDER,
            )
        }
        card.addView(
            badge,
            LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            },
        )
        badge.addView(
            CaptureUi.icon(this, R.drawable.ic_history, 24, CaptureUi.TEXT_SECONDARY),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )

        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        card.addView(TextView(this).apply {
            text = desc
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(6), dp(6), dp(6), if (actionText != null) dp(16) else 0)
        })
        if (actionText != null && action != null) {
            card.addView(
                CaptureUi.button(
                    activity = this,
                    text = actionText,
                    textColor = CaptureUi.BRAND_DARK,
                    bgColor = CaptureUi.BRAND_SOFT,
                    strokeColor = CaptureUi.BRAND_BORDER,
                    paddingH = 18,
                    paddingV = 10,
                ) { action() },
            )
        }
        return card
    }

    private fun showRaw(event: CaptureEvent) {
        var dialog: Dialog? = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            background = CaptureUi.roundedBg(Color.WHITE, dp(20), dp(1), CaptureUi.BORDER)
        }
        content.addView(View(this).apply {
            setBackgroundColor(CaptureUi.BORDER)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
                bottomMargin = dp(12)
            }
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBlock.addView(TextView(this).apply {
            text = "原始事件"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(CaptureUi.TEXT_PRIMARY)
        })
        titleBlock.addView(TextView(this).apply {
            text = buildString {
                append(sourceLabel(event.source))
                append(" · ").append(timeFormat.format(Date(event.receivedAt)))
                if (event.matched) append(" · 已命中")
            }
            textSize = 11.5f
            setTextColor(CaptureUi.TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleBlock)
        header.addView(
            CaptureUi.iconButton(
                context = this,
                res = R.drawable.ic_close,
                tint = CaptureUi.TEXT_MUTED,
                sizeDp = 36,
                iconDp = 18,
                contentDescription = "关闭",
            ) { dialog?.dismiss() },
        )
        content.addView(header)

        val raw = event.rawJson
        val shown = if (raw.length <= MAX_RAW_CHARS) {
            raw
        } else {
            raw.take(MAX_RAW_CHARS) + "\n…（已截断，复制会得到完整 JSON）"
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.42f).toInt(),
            )
            isFillViewport = false
            setPadding(0, dp(8), 0, 0)
        }
        scroll.addView(TextView(this).apply {
            text = shown
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(CaptureUi.TEXT_SECONDARY)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(2), 0, dp(2), 0)
        })
        content.addView(scroll)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        footer.addView(
            CaptureUi.button(
                activity = this,
                text = "复制 JSON",
                textColor = CaptureUi.BRAND_DARK,
                bgColor = CaptureUi.BRAND_SOFT,
                strokeColor = CaptureUi.BRAND_BORDER,
                paddingV = 9,
                textSizeSp = 12.5f,
            ) { copyJson(raw) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        footer.addView(
            CaptureUi.button(
                activity = this,
                text = "关闭",
                textColor = CaptureUi.TEXT_SECONDARY,
                bgColor = CaptureUi.BG_SUBTLE,
                strokeColor = CaptureUi.BORDER,
                paddingV = 9,
                textSizeSp = 12.5f,
            ) { dialog?.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        content.addView(footer)

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog?.dismiss() }
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(10)
            },
        )
        content.setOnClickListener { }

        val sheet = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(container)
            setCanceledOnTouchOutside(true)
        }
        dialog = sheet
        sheet.show()
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.42f)
        }
    }

    private fun copyJson(raw: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("capture_event", raw))
        Toast.makeText(this, "已复制原始 JSON", Toast.LENGTH_SHORT).show()
    }

    private fun importHistory() {
        importButton.isEnabled = false
        importButton.text = "导入中…"
        scope.launch {
            val result = AutoAccountingHistoryImporter.import(this@CaptureHistoryActivity)
            importButton.isEnabled = true
            importButton.text = "导入"
            val msg = if (result.error != null) {
                "导入失败：${result.error}"
            } else {
                "读取 ${result.fetched} 条，新增 ${result.inserted} 条"
            }
            Toast.makeText(this@CaptureHistoryActivity, msg, Toast.LENGTH_LONG).show()
            load()
        }
    }

    private fun isToday(timestamp: Long): Boolean =
        dayKeyFormat.format(Date(timestamp)) == dayKeyFormat.format(Date())

    private fun sourceLabel(source: String): String = CaptureSources.displayName(source)

    private fun typeLabel(event: CaptureEvent): String = when {
        event.localType == null -> "待分类"
        event.localType == 0 -> "支出"
        event.localType == 1 && event.localSubType == 2 -> "退款"
        event.localType == 1 -> "收入"
        event.localType == 2 && event.localSubType == 1 -> "还款"
        event.localType == 2 -> "转账"
        else -> "未知"
    }

    private fun formatAmount(cents: Long): String =
        String.format(Locale.CHINA, "¥%.2f", cents / 100.0)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val FILTER_ALL = 0
        const val FILTER_MATCHED = 1
        const val FILTER_UNMATCHED = 2
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_RAW_CHARS = 6000
    }
}
