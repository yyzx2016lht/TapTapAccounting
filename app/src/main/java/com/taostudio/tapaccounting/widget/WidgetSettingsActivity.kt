package com.taostudio.tapaccounting.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.taostudio.tapaccounting.R

/**
 * App 内"桌面小组件"管理页：列出当前已放置到桌面的所有小组件实例，
 * 点击可以直接跳转到 [WidgetConfigureActivity] 修改显示内容。
 *
 * 不需要自己维护"哪些 widgetId 存在"的状态，系统本身就能查
 * （见 [ExpenseWidgetUpdater.allPlacedWidgetIds]）。
 */
class WidgetSettingsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_settings)

        listContainer = findViewById(R.id.ll_widget_list)
        emptyView = findViewById(R.id.tv_widget_settings_empty)
        findViewById<View>(R.id.btn_widget_settings_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val widgets = ExpenseWidgetUpdater.allPlacedWidgetIds(this)
        emptyView.visibility = if (widgets.isEmpty()) View.VISIBLE else View.GONE

        widgets.forEach { (appWidgetId, size) ->
            listContainer.addView(buildRow(appWidgetId, size))
        }
    }

    private fun buildRow(appWidgetId: Int, size: WidgetSize): View {
        val config = WidgetConfigStore.load(this, appWidgetId) ?: WidgetConfig.default(this)
        val sizeLabel = when (size) {
            WidgetSize.COMPACT -> "支出金额 · 2×1"
            WidgetSize.STANDARD -> "预算金额 · 2×1"
            WidgetSize.TODAY_BUDGET -> "今日花费与预算 · 2×1"
            WidgetSize.DETAILED -> "财务概览 · 4×2"
        }

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
            text = sizeLabel
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
}
