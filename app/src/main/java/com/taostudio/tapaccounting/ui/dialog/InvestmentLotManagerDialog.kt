package com.taostudio.tapaccounting.ui.dialog

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.switchmaterial.SwitchMaterial
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.InvestmentLot
import com.taostudio.tapaccounting.logic.CurrencyUtils
import com.taostudio.tapaccounting.logic.InvestmentInterestService
import java.text.NumberFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InvestmentLotManagerDialog {
    fun show(
        activity: AppCompatActivity,
        asset: Asset,
        lots: List<InvestmentLot>,
        onEdit: (lot: InvestmentLot, annualRate: Double, cycle: Int, active: Boolean) -> Unit,
        onAddMissing: (() -> Unit)? = null
    ) {
        val context = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8))
        }
        val activeLots = lots.filter { it.status != InvestmentLot.STATUS_CLOSED && it.remainingPrincipal > 0.0 }
        val tracked = activeLots.sumOf { it.remainingPrincipal }
        val missing = (asset.balance - tracked).coerceAtLeast(0.0)

        root.addView(TextView(context).apply {
            text = if (activeLots.isEmpty()) {
                activity.getString(R.string.investment_manage_empty)
            } else {
                "共 ${activeLots.size} 笔 · 已跟踪 ${CurrencyUtils.formatAmount(tracked, asset.currency)}"
            }
            setTextColor(Color.parseColor("#667085"))
            textSize = 13f
            setPadding(0, 0, 0, dp(activity, 10))
        })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        activeLots.forEachIndexed { index, lot ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
                setBackgroundResource(R.drawable.bg_search_box)
                isClickable = true
                isFocusable = true
            }
            card.addView(TextView(context).apply {
                text = "第 ${index + 1} 笔 · ${CurrencyUtils.formatAmount(lot.remainingPrincipal, lot.currency)}"
                setTextColor(Color.parseColor("#1F2A38"))
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            val rateText = if (lot.annualInterestRate == 0.0) {
                "仅跟踪本金"
            } else {
                "固定年化 ${AmountFormatHelper.formatRate(lot.annualInterestRate)}% · ${InvestmentInterestService.cycleLabel(lot.settlementCycle)}结息"
            }
            card.addView(TextView(context).apply {
                text = rateText
                setTextColor(Color.parseColor("#526174"))
                textSize = 13f
                setPadding(0, dp(activity, 5), 0, 0)
            })
            val stateText = when {
                lot.status == InvestmentLot.STATUS_PAUSED -> "已暂停 · 点击修改"
                lot.annualInterestRate == 0.0 -> "起始 ${formatDate(lot.startEarningAt)} · 点击设置收益"
                else -> {
                    val next = InvestmentInterestService.nextPayoutAt(lot)
                    "下次结息 ${next?.let(::formatDate) ?: "--"} · 尾差 ${formatCarry(lot.interestCarry, lot.currency)} · 点击修改"
                }
            }
            card.addView(TextView(context).apply {
                text = stateText
                setTextColor(Color.parseColor(if (lot.status == InvestmentLot.STATUS_PAUSED) "#B54708" else "#8A9099"))
                textSize = 12f
                setPadding(0, dp(activity, 4), 0, 0)
            })
            card.setOnClickListener {
                showEditDialog(activity, lot) { rate, cycle, active ->
                    onEdit(lot, rate, cycle, active)
                }
            }
            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(activity, 10) })
        }

        root.addView(ScrollView(context).apply {
            addView(list)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 360)
        ))

        if (onAddMissing != null && missing > 0.005) {
            root.addView(TextView(context).apply {
                text = "还有 ${CurrencyUtils.formatAmount(missing, asset.currency)} 未分配到批次，点击补录"
                setTextColor(Color.parseColor("#3267E3"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(activity, 8), dp(activity, 12), dp(activity, 8), dp(activity, 8))
                setOnClickListener { onAddMissing() }
            })
        }

        AlertDialog.Builder(context)
            .setTitle(activity.getString(R.string.investment_manage_title))
            .setMessage("固定年化收益会复投到当前本金；自动生成的收益属于本机估算，不计入真实收支统计。")
            .setView(root)
            .setNegativeButton(activity.getString(R.string.close), null)
            .show()
    }

    private fun showEditDialog(
        activity: AppCompatActivity,
        lot: InvestmentLot,
        onSave: (annualRate: Double, cycle: Int, active: Boolean) -> Unit
    ) {
        val context = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), 0)
        }
        content.addView(TextView(context).apply {
            text = "年利率（%）"
            setTextColor(Color.parseColor("#667085"))
            textSize = 12f
            setPadding(0, dp(activity, 8), 0, dp(activity, 4))
        })
        val rateInput = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "留空仅跟踪本金"
            if (lot.annualInterestRate != 0.0) setText(AmountFormatHelper.formatRate(lot.annualInterestRate))
        }
        content.addView(rateInput)

        content.addView(TextView(context).apply {
            text = activity.getString(R.string.investment_settlement_cycle)
            setTextColor(Color.parseColor("#667085"))
            textSize = 12f
            setPadding(0, dp(activity, 10), 0, dp(activity, 4))
        })
        val options = InvestmentInterestService.cycleOptions()
        val cycleSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.first }
            )
            setSelection(options.indexOfFirst { it.second == lot.settlementCycle }.coerceAtLeast(0))
        }
        content.addView(cycleSpinner)

        val enabledSwitch = SwitchMaterial(context).apply {
            text = "启用自动结息"
            isChecked = lot.status == InvestmentLot.STATUS_ACTIVE
            setPadding(0, dp(activity, 12), 0, 0)
        }
        content.addView(enabledSwitch)
        content.addView(TextView(context).apply {
            text = "修改从今天起生效；此前尚未满一分的收益尾差会保留。"
            setTextColor(Color.parseColor("#8A9099"))
            textSize = 12f
            setPadding(0, dp(activity, 8), 0, 0)
        })

        val dialog = AlertDialog.Builder(context)
            .setTitle("修改收益设置")
            .setView(content)
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .setPositiveButton(activity.getString(R.string.save), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rate = parseLocalizedNumber(rateInput.text?.toString().orEmpty())
                if (!rate.isFinite() || rate <= -100.0 || rate > 10_000.0) {
                    rateInput.error = "请输入大于 -100 且不超过 10000 的年利率"
                    return@setOnClickListener
                }
                val cycle = options[cycleSpinner.selectedItemPosition.coerceAtLeast(0)].second
                dialog.dismiss()
                onSave(rate, cycle, enabledSwitch.isChecked)
            }
        }
        dialog.show()
    }

    private fun parseLocalizedNumber(raw: String): Double {
        val value = raw.trim().removeSuffix("%").trim()
        if (value.isEmpty() || value == "-") return 0.0
        val localized = NumberFormat.getNumberInstance(Locale.getDefault())
        val position = ParsePosition(0)
        localized.parse(value, position)?.toDouble()
            ?.takeIf { position.index == value.length }
            ?.let { return it }
        return value.replace("\\s".toRegex(), "").replace(',', '.').toDoubleOrNull() ?: Double.NaN
    }

    private fun formatDate(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMillis))

    private fun formatCarry(value: Double, currency: String): String {
        val decimals = maxOf(CurrencyUtils.decimalPlaces(currency) + 3, 4)
        return String.format(Locale.getDefault(), "%.${decimals}f", value)
            .trimEnd('0')
            .trimEnd('.')
            .ifBlank { "0" }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
