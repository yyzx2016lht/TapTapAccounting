package com.taostudio.tapaccounting.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R

/** 添加或编辑一个桌面小组件实例的配置页。 */
class WidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedBook: String = BookAccountManager.DEFAULT_BOOK
    private var availableBooks: List<String> = emptyList()
    private var widgetSize: WidgetSize? = null

    private lateinit var tvSelectedBook: TextView
    private lateinit var rgPeriod: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_configure)
        tvSelectedBook = findViewById(R.id.tv_widget_selected_book)
        rgPeriod = findViewById(R.id.rg_widget_period)

        findViewById<View>(R.id.btn_widget_config_back).setOnClickListener { finish() }
        findViewById<View>(R.id.layout_widget_book_selector).setOnClickListener { showBookPicker() }
        findViewById<View>(R.id.btn_save_widget_config).setOnClickListener { save() }

        widgetSize = ExpenseWidgetUpdater.sizeOf(this, AppWidgetManager.getInstance(this), appWidgetId)
        val existing = WidgetConfigStore.load(this, appWidgetId) ?: WidgetConfig.default(this)
        availableBooks = WidgetConfigStore.activeBooks(this)
        selectedBook = existing.bookName.takeIf { it in availableBooks }
            ?: BookAccountManager.getSelectedBook(this).takeIf { it in availableBooks }
            ?: availableBooks.first()

        tvSelectedBook.text = selectedBook
        populatePeriod(existing.period)
        showSizeOptions()
    }

    private fun showBookPicker() {
        val checked = availableBooks.indexOf(selectedBook).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择统计账本")
            .setSingleChoiceItems(availableBooks.toTypedArray(), checked) { dialog, which ->
                selectedBook = availableBooks[which]
                tvSelectedBook.text = selectedBook
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun populatePeriod(period: WidgetPeriod) {
        rgPeriod.check(
            when (period) {
                WidgetPeriod.TODAY -> R.id.rb_period_today
                WidgetPeriod.THIS_MONTH -> R.id.rb_period_month
                WidgetPeriod.THIS_WEEK -> R.id.rb_period_week
                WidgetPeriod.LAST_7_DAYS -> R.id.rb_period_7days
            }
        )
    }

    private fun showSizeOptions() {
        findViewById<TextView>(R.id.tv_widget_size_hint).text = when (widgetSize) {
            WidgetSize.COMPACT -> "支出金额 · 2×1"
            WidgetSize.STANDARD -> "预算金额 · 2×1"
            WidgetSize.TODAY_BUDGET -> "今日花费与预算 · 2×1"
            WidgetSize.DETAILED -> "财务概览 · 4×2"
            null -> "选择小组件要显示的内容"
        }
        findViewById<View>(R.id.card_widget_period).visibility =
            if (widgetSize == WidgetSize.STANDARD || widgetSize == WidgetSize.TODAY_BUDGET) View.GONE else View.VISIBLE
    }

    private fun save() {
        val period = if (widgetSize == WidgetSize.STANDARD) {
            WidgetPeriod.THIS_MONTH
        } else if (widgetSize == WidgetSize.TODAY_BUDGET) {
            WidgetPeriod.TODAY
        } else {
            when (rgPeriod.checkedRadioButtonId) {
                R.id.rb_period_today -> WidgetPeriod.TODAY
                R.id.rb_period_week -> WidgetPeriod.THIS_WEEK
                R.id.rb_period_7days -> WidgetPeriod.LAST_7_DAYS
                else -> WidgetPeriod.THIS_MONTH
            }
        }
        WidgetConfigStore.save(this, appWidgetId, WidgetConfig(selectedBook, period))
        ExpenseWidgetUpdater.refreshOne(this, appWidgetId)

        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        finish()
    }
}
