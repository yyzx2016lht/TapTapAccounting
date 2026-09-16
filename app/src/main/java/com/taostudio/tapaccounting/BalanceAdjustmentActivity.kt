package com.taostudio.tapaccounting

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.CurrencyManager
import com.taostudio.tapaccounting.logic.CurrencyUtils
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.util.Locale
import kotlin.math.abs

class BalanceAdjustmentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ASSET_ID = "ASSET_ID"
        const val EXTRA_OLD_BALANCE = "OLD_BALANCE"
        const val EXTRA_NEW_BALANCE = "NEW_BALANCE"
        const val EXTRA_ASSET_NAME = "ASSET_NAME"
        const val EXTRA_OLD_CURRENCY = "OLD_CURRENCY"
        const val EXTRA_CURRENCY = "CURRENCY"

        const val RESULT_MODE = "RESULT_MODE"
        const val RESULT_CATEGORY_NAME = "RESULT_CATEGORY_NAME"
        const val RESULT_INCLUDE_IN_STATS = "RESULT_INCLUDE_IN_STATS"
        const val RESULT_REMARK = "RESULT_REMARK"

        const val MODE_SAVE_ONLY = "SAVE_ONLY"
        const val MODE_SAVE_WITH_RECORD = "SAVE_WITH_RECORD"
    }

    private var includeInStats: Boolean = true
    private var selectedCategoryName: String = "其他"

    private var assetId: Long = -1
    private var oldBalance: Double = 0.0
    private var newBalance: Double = 0.0
    private var assetName: String = ""
    private var oldAssetCurrency: String = "CNY"
    private var assetCurrency: String = "CNY"

    private val diff: Double
        get() = BillAssetImpactService.roundMoney(newBalance - oldBalance)

    private val isCurrencyChanged: Boolean
        get() = !oldAssetCurrency.equals(assetCurrency, ignoreCase = true)

    private lateinit var tvDiffLabel: TextView
    private lateinit var tvDiffAmount: TextView
    private lateinit var tvDiffFormula: TextView
    private lateinit var tvBillType: TextView
    private lateinit var tvCategory: TextView
    private lateinit var tvTag: TextView
    private lateinit var etRemark: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_balance_adjustment)

        assetId = intent.getLongExtra(EXTRA_ASSET_ID, -1)
        oldBalance = BillAssetImpactService.roundMoney(intent.getDoubleExtra(EXTRA_OLD_BALANCE, 0.0))
        newBalance = BillAssetImpactService.roundMoney(intent.getDoubleExtra(EXTRA_NEW_BALANCE, 0.0))
        assetName = intent.getStringExtra(EXTRA_ASSET_NAME) ?: ""
        oldAssetCurrency = intent.getStringExtra(EXTRA_OLD_CURRENCY)?.ifBlank { "CNY" } ?: "CNY"
        assetCurrency = intent.getStringExtra(EXTRA_CURRENCY)?.ifBlank { "CNY" } ?: "CNY"
        includeInStats = !isCurrencyChanged

        if (assetId == -1L) {
            finish()
            return
        }

        initViews()
        updateUI()
    }

    private fun initViews() {
        tvDiffLabel = findViewById(R.id.tv_diff_label)
        tvDiffAmount = findViewById(R.id.tv_diff_amount)
        tvDiffFormula = findViewById(R.id.tv_diff_formula)
        tvBillType = findViewById(R.id.tv_bill_type)
        tvCategory = findViewById(R.id.tv_category)
        tvTag = findViewById(R.id.tv_tag)
        etRemark = findViewById(R.id.et_remark)

        etRemark.dismissKeyboardOnEnter()

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.row_category).setOnClickListener { showCategoryDialog() }
        findViewById<View>(R.id.row_tag).setOnClickListener { showTagDialog() }

        findViewById<Button>(R.id.btn_no_record).setOnClickListener {
            setResult(
                RESULT_OK,
                Intent().putExtra(RESULT_MODE, MODE_SAVE_ONLY)
            )
            finish()
        }

        findViewById<Button>(R.id.btn_generate).setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(RESULT_MODE, MODE_SAVE_WITH_RECORD)
                    .putExtra(RESULT_CATEGORY_NAME, selectedCategoryName)
                    .putExtra(RESULT_INCLUDE_IN_STATS, includeInStats)
                    .putExtra(RESULT_REMARK, etRemark.text.toString().ifBlank { buildDefaultRemark() })
            )
            finish()
        }
    }

    private fun updateUI() {
        val oldStr = CurrencyUtils.formatAmount(oldBalance, oldAssetCurrency)
        val newStr = CurrencyUtils.formatAmount(newBalance, assetCurrency)

        if (isCurrencyChanged) {
            val color = Color.parseColor("#2196F3")
            tvDiffLabel.text = getString(R.string.balance_after_change)
            tvDiffAmount.text = newStr
            tvDiffAmount.setTextColor(color)
            tvDiffFormula.text = "$oldStr -> $newStr"
            tvBillType.text = getString(R.string.currency_exchange)
            tvBillType.setTextColor(color)
        } else {
            val color = if (diff >= 0) Color.parseColor("#2196F3") else Color.parseColor("#F44336")
            val symbol = CurrencyManager.getSymbol(assetCurrency)
            val diffStr = CurrencyUtils.formatAmount(diff, assetCurrency)
            tvDiffLabel.text = getString(R.string.difference)
            tvDiffAmount.text = "${if (diff >= 0) "+" else "-"}$symbol${String.format(Locale.getDefault(), "%.2f", abs(diff))}"
            tvDiffAmount.setTextColor(color)
            tvDiffFormula.text = "$newStr - ($oldStr) = $diffStr"
            tvBillType.text = if (diff >= 0) getString(R.string.income) else getString(R.string.expense)
            tvBillType.setTextColor(color)
        }

        tvCategory.text = selectedCategoryName
        updateTagSummary()
        etRemark.setText(buildDefaultRemark())
    }

    private fun showTagDialog() {
        if (isCurrencyChanged) {
            includeInStats = false
            updateTagSummary()
            Toast.makeText(this, getString(R.string.exchange_excluded), Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(getString(R.string.include_in_stats), getString(R.string.exclude_from_stats))
        val currentIndex = if (includeInStats) 0 else 1
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.bill_mark_title))
            .setSingleChoiceItems(options, currentIndex) { d, which ->
                includeInStats = (which == 0)
                updateTagSummary()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog, this)
    }

    private fun showCategoryDialog() {
        val type = if (!isCurrencyChanged && diff >= 0) 1 else 0
        OverlayDialogs.showGridCategoryPicker(this, selectedCategoryName, type) { selected ->
            selectedCategoryName = selected.ifBlank { "其他" }
            tvCategory.text = selectedCategoryName
        }
    }

    private fun updateTagSummary() {
        tvTag.text = if (includeInStats) getString(R.string.include_in_stats) else getString(R.string.exclude_from_stats)
        tvTag.setTextColor(
            if (includeInStats) Color.parseColor("#2196F3")
            else Color.parseColor("#999999")
        )
    }

    private fun buildDefaultRemark(): String {
        val oldStr = CurrencyUtils.formatAmount(oldBalance, oldAssetCurrency)
        val newStr = CurrencyUtils.formatAmount(newBalance, assetCurrency)
        return if (isCurrencyChanged) {
            "换币平账($oldStr -> $newStr)"
        } else {
            "平账($oldStr -> $newStr)"
        }
    }
}

