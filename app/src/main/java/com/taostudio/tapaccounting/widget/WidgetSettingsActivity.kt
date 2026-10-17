package com.taostudio.tapaccounting.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.CurrencyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 内"桌面小组件"管理页：上半部分列出当前已放置到桌面的小组件实例（点击进
 * [WidgetConfigureActivity] 修改），下半部分是四种样式的实时预览，可一键请求系统固定到桌面。
 *
 * 不需要自己维护"哪些 widgetId 存在"的状态，系统本身就能查
 * （见 [ExpenseWidgetUpdater.allPlacedWidgetIds]）。
 */
class WidgetSettingsActivity : AppCompatActivity() {

    private lateinit var placedTitle: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var catalogHint: TextView
    private val previewHosts = mutableMapOf<WidgetSize, FrameLayout>()

    private val appName: String by lazy { applicationInfo.loadLabel(packageManager).toString() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_settings)

        placedTitle = findViewById(R.id.tv_widget_placed_title)
        listContainer = findViewById(R.id.ll_widget_list)
        catalogHint = findViewById(R.id.tv_widget_catalog_hint)
        findViewById<View>(R.id.btn_widget_settings_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_widget_manual_hint).text =
            "没弹出添加窗口？也可以手动添加：回到桌面长按空白处 → 选「小组件」或「添加工具」→ 搜索「$appName」，按上面的样式名挑选。"

        buildCatalog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 系统固定成功后通过 successCallback 回到本页
        if (intent.getBooleanExtra(EXTRA_PINNED, false)) {
            Toast.makeText(this, "已添加到桌面", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        refreshPreviews()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val widgets = ExpenseWidgetUpdater.allPlacedWidgetIds(this)
        val hasPlaced = widgets.isNotEmpty()
        placedTitle.visibility = if (hasPlaced) View.VISIBLE else View.GONE
        listContainer.visibility = if (hasPlaced) View.VISIBLE else View.GONE
        catalogHint.text = if (hasPlaced) {
            "预览用的是当前账本的实时数据，同一样式可以添加多个。"
        } else {
            "桌面上还没有小组件。下面是用当前账本实时数据生成的预览，点「添加到桌面」即可放上去。"
        }

        widgets.forEach { (appWidgetId, size) ->
            listContainer.addView(buildRow(appWidgetId, size))
        }
    }

    private fun buildCatalog() {
        val catalog = findViewById<LinearLayout>(R.id.ll_widget_catalog)
        val inflater = LayoutInflater.from(this)
        val availableDp = resources.configuration.screenWidthDp - CATALOG_HORIZONTAL_INSET_DP

        WidgetSize.values().forEach { size ->
            val card = inflater.inflate(R.layout.item_widget_catalog, catalog, false)
            card.findViewById<TextView>(R.id.tv_widget_catalog_title).text = size.label
            card.findViewById<TextView>(R.id.tv_widget_catalog_desc).text = size.description
            card.findViewById<View>(R.id.btn_widget_catalog_add).setOnClickListener { requestPin(size) }

            val (widthDp, heightDp) = previewSizeDp(size, availableDp)
            val host = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(widthDp), dp(heightDp), Gravity.CENTER)
            }
            card.findViewById<FrameLayout>(R.id.fl_widget_preview).addView(host)
            previewHosts[size] = host
            mountPreview(host, size, null)
            catalog.addView(card)
        }
    }

    private fun refreshPreviews() {
        val config = WidgetConfig.default(this)
        lifecycleScope.launch {
            previewHosts.forEach { (size, host) ->
                val snapshot = withContext(Dispatchers.IO) {
                    ExpenseWidgetRenderer.buildSnapshot(applicationContext, config, size)
                }
                mountPreview(host, size, snapshot)
            }
        }
    }

    /** 先放样式本身，RemoteViews 能套上真实数据就替换；套不上也别留一块空白。 */
    private fun mountPreview(host: FrameLayout, size: WidgetSize, snapshot: ExpenseWidgetRenderer.Snapshot?) {
        val widthDp = previewSizeDp(size, resources.configuration.screenWidthDp - CATALOG_HORIZONTAL_INSET_DP).first
        val applied = snapshot?.let { data ->
            val views = ExpenseWidgetRenderer.buildViews(this, size, data, widthDp)
            runCatching { views.apply(this, host) }.getOrNull()
        }
        val preview = applied ?: LayoutInflater.from(this).inflate(layoutOf(size), host, false).also { root ->
            if (snapshot != null) bindFallback(root, size, snapshot)
        }
        host.removeAllViews()
        host.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun layoutOf(size: WidgetSize): Int = when (size) {
        WidgetSize.COMPACT -> R.layout.widget_expense_compact
        WidgetSize.STANDARD -> R.layout.widget_expense_standard
        WidgetSize.TODAY_BUDGET -> R.layout.widget_expense_today_budget
        WidgetSize.DETAILED -> R.layout.widget_expense_detailed
    }

    private fun bindFallback(root: View, size: WidgetSize, snapshot: ExpenseWidgetRenderer.Snapshot) {
        fun money(amount: Double) =
            AmountFormatHelper.formatCurrency(CurrencyManager.getSymbol("CNY"), amount)
        fun text(id: Int, value: String) {
            root.findViewById<TextView>(id)?.text = value
        }
        fun progress(percent: Int?) {
            val bar = root.findViewById<ProgressBar>(R.id.progress_widget_budget) ?: return
            if (percent == null) {
                bar.visibility = View.GONE
            } else {
                bar.visibility = View.VISIBLE
                bar.progress = percent.coerceIn(0, 100)
            }
        }
        when (size) {
            WidgetSize.COMPACT -> {
                text(R.id.tv_widget_subtitle, "${snapshot.bookLabel} · ${snapshot.periodLabel}")
                text(R.id.tv_widget_primary_label, "${snapshot.periodLabel}支出")
                text(R.id.tv_widget_primary_value, money(snapshot.expense))
            }
            WidgetSize.STANDARD -> {
                text(R.id.tv_widget_primary_label, "预算剩余")
                text(R.id.tv_widget_secondary_label, "本月预算")
                if (snapshot.budget == null || snapshot.remaining == null) {
                    text(R.id.tv_widget_primary_value, "未设置")
                    text(R.id.tv_widget_secondary_value, "—")
                    progress(null)
                } else {
                    text(R.id.tv_widget_primary_value, money(snapshot.remaining))
                    text(R.id.tv_widget_secondary_value, money(snapshot.budget))
                    progress(snapshot.budgetPercent)
                }
            }
            WidgetSize.TODAY_BUDGET -> {
                text(R.id.tv_widget_primary_label, "今日花费")
                text(R.id.tv_widget_primary_value, money(snapshot.expense))
                text(R.id.tv_widget_secondary_label, "预算剩余")
                if (snapshot.remaining == null) {
                    text(R.id.tv_widget_secondary_value, "未设置")
                    progress(null)
                } else {
                    text(R.id.tv_widget_secondary_value, money(snapshot.remaining))
                    progress(snapshot.budgetPercent)
                }
            }
            WidgetSize.DETAILED -> {
                text(R.id.tv_widget_subtitle, "${snapshot.bookLabel} · ${snapshot.periodLabel}")
                text(R.id.tv_widget_primary_label, "${snapshot.periodLabel}支出")
                text(R.id.tv_widget_primary_value, money(snapshot.expense))
                text(R.id.tv_widget_secondary_label, "本月预算")
                text(R.id.tv_widget_tertiary_label, "预算剩余")
                if (snapshot.budget == null || snapshot.remaining == null) {
                    text(R.id.tv_widget_secondary_value, "未设置")
                    text(R.id.tv_widget_tertiary_value, "—")
                    text(R.id.tv_widget_status, "打开应用设置本月预算")
                    progress(null)
                } else {
                    text(R.id.tv_widget_secondary_value, money(snapshot.budget))
                    text(R.id.tv_widget_tertiary_value, money(snapshot.remaining))
                    val percent = snapshot.budgetPercent ?: 0
                    text(
                        R.id.tv_widget_status,
                        if (percent > 100) "已超预算 · ${percent}%" else "本月预算已用 ${percent}%"
                    )
                    progress(percent)
                }
            }
        }
    }

    private fun previewSizeDp(size: WidgetSize, availableDp: Int): Pair<Int, Int> =
        if (size == WidgetSize.DETAILED) {
            val width = availableDp.coerceIn(200, 300)
            width to maxOf(140, width / 2)
        } else {
            availableDp.coerceAtMost(168) to 72
        }

    private fun requestPin(size: WidgetSize) {
        val manager = AppWidgetManager.getInstance(this)
        val pinned = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.isRequestPinAppWidgetSupported &&
            manager.requestPinAppWidget(
                ExpenseWidgetUpdater.providerOf(this, size),
                null,
                pinSuccessCallback(size)
            )
        if (!pinned) showManualGuide(size)
    }

    private fun pinSuccessCallback(size: WidgetSize): PendingIntent {
        val intent = Intent(this, WidgetSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PINNED, true)
        }
        // 系统要往回调里塞 appWidgetId，S 及以上必须声明 MUTABLE
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(this, size.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    private fun showManualGuide(size: WidgetSize) {
        AlertDialog.Builder(this)
            .setTitle("当前桌面不支持一键添加")
            .setMessage(
                "请按以下步骤手动添加：\n\n" +
                    "1. 回到桌面，长按空白处\n" +
                    "2. 点「小组件」或「添加工具」\n" +
                    "3. 搜索「$appName」\n" +
                    "4. 选择「${size.label}」，拖到桌面"
            )
            .setPositiveButton("回到桌面") { _, _ ->
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildRow(appWidgetId: Int, size: WidgetSize): View {
        val config = WidgetConfigStore.load(this, appWidgetId) ?: WidgetConfig.default(this)

        val card = CardView(this).apply {
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            setCardBackgroundColor(resources.getColor(R.color.settings_card_bg, theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            isClickable = true
            isFocusable = true
        }

        val padding = (14 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = size.label
            setTextColor(resources.getColor(R.color.settings_item_title, theme))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textColumn.addView(TextView(this).apply {
            text = when (size) {
                WidgetSize.STANDARD -> "${config.bookName} · 本月"
                WidgetSize.TODAY_BUDGET -> "${config.bookName} · 今日"
                else -> "${config.bookName} · ${config.period.label()}"
            }
            setTextColor(resources.getColor(R.color.settings_item_subtitle, theme))
            textSize = 12f
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        })

        val chevron = TextView(this).apply {
            text = "›"
            setTextColor(resources.getColor(R.color.settings_chevron, theme))
            textSize = 22f
        }

        row.addView(textColumn)
        row.addView(chevron)
        card.addView(row)

        card.setOnClickListener {
            startActivity(Intent(this, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            })
        }
        return card
    }

    private companion object {
        const val EXTRA_PINNED = "extra_widget_pinned"
        /** 页面 16dp×2 + 卡片 16dp×2 + 预览底板 12dp×2。 */
        const val CATALOG_HORIZONTAL_INSET_DP = 88
    }
}
