package com.taostudio.tapaccounting.ui.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.CurrencyUtils
import com.taostudio.tapaccounting.logic.InvestmentInterestService
import com.taostudio.tapaccounting.logic.InvestmentLotDraft
import java.text.NumberFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object InvestmentLotSplitDialog {

    fun show(
        activity: AppCompatActivity,
        title: String,
        message: String,
        totalAmount: Double,
        currency: String,
        annualInterestRate: Double,
        initialDrafts: List<InvestmentLotDraft> = emptyList(),
        onLater: (List<InvestmentLotDraft>) -> Unit,
        onConfirm: (List<InvestmentLotDraft>) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
        val dialog = Dialog(themeContext)
        val content = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 18))
            setBackgroundResource(R.drawable.bg_overlay_accounting_panel)
        }
        content.addView(TextView(themeContext).apply {
            text = title
            setTextColor(Color.parseColor("#1F2A38"))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(themeContext).apply {
            text = message
            setTextColor(Color.parseColor("#667085"))
            textSize = 14f
            setPadding(0, dp(activity, 8), 0, dp(activity, 10))
        })
        content.addView(TextView(themeContext).apply {
            text = activity.getString(
                R.string.investment_lot_split_balance_hint,
                formatMoney(totalAmount, currency)
            )
            setTextColor(Color.parseColor("#8A9099"))
            textSize = 13f
            setPadding(0, 0, 0, dp(activity, 14))
        })

        val countRow = LinearLayout(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(activity, 10))
        }
        countRow.addView(TextView(themeContext).apply {
            text = activity.getString(R.string.investment_lot_split_count_label)
            setTextColor(Color.parseColor("#1F2A38"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val etLotCount = EditText(themeContext).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setText("1")
            textSize = 16f
            setTextColor(Color.parseColor("#1F2A38"))
            background = roundedStrokeDrawable(
                activity,
                Color.parseColor("#F7F9FC"),
                Color.parseColor("#E5EAF2"),
                dp(activity, 10)
            )
            layoutParams = LinearLayout.LayoutParams(dp(activity, 70), dp(activity, 40))
        }
        countRow.addView(etLotCount)
        content.addView(countRow)

        val remainingText = TextView(themeContext).apply {
            setTextColor(Color.parseColor("#667085"))
            textSize = 13f
            setPadding(0, 0, 0, dp(activity, 10))
        }
        content.addView(remainingText)

        val lotsContainer = LinearLayout(themeContext).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(themeContext).apply {
            addView(lotsContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(activity, 330)
            )
        }
        content.addView(scroll)

        fun addField(parent: LinearLayout, label: String, hint: String, text: String = ""): EditText {
            parent.addView(TextView(themeContext).apply {
                this.text = label
                setTextColor(Color.parseColor("#667085"))
                textSize = 12f
                setPadding(0, dp(activity, 10), 0, dp(activity, 4))
            })
            return EditText(themeContext).apply {
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                this.hint = hint
                setText(text)
                textSize = 15f
                setTextColor(Color.parseColor("#1F2A38"))
                setHintTextColor(Color.parseColor("#A0A7B2"))
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                background = roundedStrokeDrawable(
                    activity,
                    Color.parseColor("#F7F9FC"),
                    Color.parseColor("#E5EAF2"),
                    dp(activity, 10)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 42)
                )
            }.also(parent::addView)
        }

        data class LotRow(
            val amountInput: EditText,
            val rateInput: EditText,
            val startInput: TextView,
            val cycleInput: Spinner,
            val payoutText: TextView,
            var startEarningAt: Long
        )

        val rows = mutableListOf<LotRow>()
        val cycleOptions = InvestmentInterestService.cycleOptions()
        val todayStart = InvestmentInterestService.startOfDay(System.currentTimeMillis())

        fun updateRemainingHint() {
            val filled = BillAssetImpactService.roundMoney(
                rows.sumOf { parseLocalizedAmount(it.amountInput.text?.toString().orEmpty()) }
            )
            val remaining = BillAssetImpactService.roundMoney(totalAmount - filled)
            remainingText.text = when {
                abs(remaining) <= 0.01 -> activity.getString(R.string.investment_lot_split_allocated_done)
                remaining > 0 -> activity.getString(
                    R.string.investment_lot_split_remaining,
                    formatMoney(remaining, currency)
                )
                else -> activity.getString(
                    R.string.investment_lot_split_over_allocated,
                    formatMoney(abs(remaining), currency)
                )
            }
            remainingText.setTextColor(
                if (remaining < -0.01) Color.parseColor("#D92D20") else Color.parseColor("#667085")
            )
        }

        fun rebuildRows() {
            val preservedDrafts = rows.map { row ->
                val cycle = cycleOptions[row.cycleInput.selectedItemPosition.coerceAtLeast(0)].second
                InvestmentLotDraft(
                    amount = BillAssetImpactService.roundMoney(
                        parseLocalizedAmount(row.amountInput.text?.toString().orEmpty())
                    ),
                    schedule = InvestmentInterestService.InvestmentSchedule(
                        startEarningAt = row.startEarningAt,
                        firstPayoutAt = InvestmentInterestService.firstPayoutFor(row.startEarningAt, cycle),
                        annualInterestRate = parseLocalizedAmount(row.rateInput.text?.toString().orEmpty()),
                        settlementCycle = cycle
                    )
                )
            }
            lotsContainer.removeAllViews()
            rows.clear()
            val initialCount = initialDrafts.size.takeIf { it > 0 }
            val count = (etLotCount.text?.toString()?.toIntOrNull() ?: initialCount ?: 1).coerceIn(1, 20)
            repeat(count) { index ->
                val card = LinearLayout(themeContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 14))
                    background = roundedStrokeDrawable(
                        activity,
                        Color.WHITE,
                        Color.parseColor("#E8EDF5"),
                        dp(activity, 14)
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(activity, 10) }
                }
                card.addView(TextView(themeContext).apply {
                    text = activity.getString(R.string.investment_lot_split_row_title, index + 1)
                    setTextColor(Color.parseColor("#1F2A38"))
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                val draft = preservedDrafts.getOrNull(index) ?: initialDrafts.getOrNull(index)
                val amountText = when {
                    draft != null && draft.amount > 0.0 -> AmountFormatHelper.formatRate(draft.amount)
                    count == 1 -> AmountFormatHelper.formatRate(totalAmount)
                    else -> ""
                }
                val rateText = when {
                    draft != null && draft.schedule.annualInterestRate != 0.0 ->
                        AmountFormatHelper.formatRate(draft.schedule.annualInterestRate)
                    annualInterestRate != 0.0 -> AmountFormatHelper.formatRate(annualInterestRate)
                    else -> ""
                }
                val amountInput = addField(
                    card,
                    activity.getString(R.string.amount),
                    activity.getString(R.string.investment_lot_split_amount_required),
                    amountText
                )
                amountInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = updateRemainingHint()
                })
                val rateInput = addField(
                    card,
                    activity.getString(R.string.investment_lot_split_annual_rate),
                    activity.getString(R.string.investment_lot_split_rate_optional),
                    rateText
                )
                val startInput = TextView(themeContext)
                val cycleInput = Spinner(themeContext).apply {
                    adapter = ArrayAdapter(
                        themeContext,
                        android.R.layout.simple_spinner_dropdown_item,
                        cycleOptions.map { it.first }
                    )
                    val initialCycle = draft?.schedule?.settlementCycle
                        ?: com.taostudio.tapaccounting.data.local.entity.InvestmentLot.CYCLE_DAILY
                    setSelection(cycleOptions.indexOfFirst { it.second == initialCycle }.coerceAtLeast(0))
                }
                val row = LotRow(
                    amountInput = amountInput,
                    rateInput = rateInput,
                    startInput = startInput,
                    cycleInput = cycleInput,
                    payoutText = TextView(themeContext),
                    startEarningAt = draft?.schedule?.startEarningAt
                        ?: InvestmentInterestService.plusDays(todayStart, 1)
                )
                card.addView(TextView(themeContext).apply {
                    text = activity.getString(R.string.start_earning)
                    setTextColor(Color.parseColor("#667085"))
                    textSize = 12f
                    setPadding(0, dp(activity, 10), 0, dp(activity, 4))
                })
                row.startInput.apply {
                    text = formatDateForSchedule(row.startEarningAt)
                    setTextColor(Color.parseColor("#1F2A38"))
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                    background = roundedStrokeDrawable(
                        activity,
                        Color.parseColor("#F7F9FC"),
                        Color.parseColor("#E5EAF2"),
                        dp(activity, 10)
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(activity, 42)
                    )
                    setOnClickListener {
                        ElegantDatePickerSheet.show(
                            context = activity,
                            initialTimeMillis = row.startEarningAt,
                            minTimeMillis = null
                        ) { selected ->
                            row.startEarningAt = InvestmentInterestService.startOfDay(selected)
                            row.startInput.text = formatDateForSchedule(row.startEarningAt)
                            val cycle = cycleOptions[row.cycleInput.selectedItemPosition.coerceAtLeast(0)].second
                            row.payoutText.text = activity.getString(
                                R.string.investment_first_payout_value,
                                formatDateForSchedule(InvestmentInterestService.firstPayoutFor(row.startEarningAt, cycle))
                            )
                        }
                    }
                }
                card.addView(row.startInput)
                card.addView(TextView(themeContext).apply {
                    text = activity.getString(R.string.investment_settlement_cycle)
                    setTextColor(Color.parseColor("#667085"))
                    textSize = 12f
                    setPadding(0, dp(activity, 10), 0, dp(activity, 4))
                })
                card.addView(row.cycleInput, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 42)
                ))
                fun refreshPayout() {
                    val cycle = cycleOptions[row.cycleInput.selectedItemPosition.coerceAtLeast(0)].second
                    row.payoutText.text = activity.getString(
                        R.string.investment_first_payout_value,
                        formatDateForSchedule(InvestmentInterestService.firstPayoutFor(row.startEarningAt, cycle))
                    )
                }
                row.payoutText.apply {
                    setTextColor(Color.parseColor("#8A9099"))
                    textSize = 12f
                    setPadding(0, dp(activity, 6), 0, 0)
                }
                row.cycleInput.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        refreshPayout()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
                refreshPayout()
                card.addView(row.payoutText)
                lotsContainer.addView(card)
                rows += row
            }
            updateRemainingHint()
        }

        etLotCount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = rebuildRows()
        })
        if (initialDrafts.isNotEmpty()) {
            etLotCount.setText(initialDrafts.size.toString())
        }
        rebuildRows()

        fun collectDrafts(): List<InvestmentLotDraft> {
            return rows.map { row ->
                val amount = BillAssetImpactService.roundMoney(
                    parseLocalizedAmount(row.amountInput.text?.toString().orEmpty())
                )
                InvestmentLotDraft(
                    amount = amount,
                    schedule = InvestmentInterestService.InvestmentSchedule(
                        startEarningAt = row.startEarningAt,
                        firstPayoutAt = InvestmentInterestService.firstPayoutFor(
                            row.startEarningAt,
                            cycleOptions[row.cycleInput.selectedItemPosition.coerceAtLeast(0)].second
                        ),
                        annualInterestRate = parseLocalizedAmount(row.rateInput.text?.toString().orEmpty()),
                        settlementCycle = cycleOptions[row.cycleInput.selectedItemPosition.coerceAtLeast(0)].second
                    )
                )
            }
        }

        content.addView(TextView(themeContext).apply {
            text = activity.getString(R.string.earning_default_hint)
            setTextColor(Color.parseColor("#8A9099"))
            textSize = 12f
            setPadding(0, dp(activity, 10), 0, 0)
        })

        val actionRow = LinearLayout(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 16), 0, 0)
        }
        val cancelButton = TextView(themeContext).apply {
            text = activity.getString(R.string.cancel)
            setTextColor(ContextCompat.getColor(activity, R.color.dialog_button_cancel_text))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dialog_button_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        val laterButton = TextView(themeContext).apply {
            text = activity.getString(R.string.investment_lot_prompt_later)
            setTextColor(ContextCompat.getColor(activity, R.color.dialog_button_cancel_text))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dialog_button_outline)
            setOnClickListener {
                dialog.dismiss()
                onLater(collectDrafts())
            }
        }
        val confirmButton = TextView(themeContext).apply {
            text = activity.getString(R.string.confirm)
            setTextColor(ContextCompat.getColor(activity, R.color.dialog_button_primary_text))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dialog_button_primary)
            setOnClickListener {
                val drafts = rows.map { row ->
                    val amount = BillAssetImpactService.roundMoney(
                        parseLocalizedAmount(row.amountInput.text?.toString().orEmpty())
                    )
                    if (amount <= 0.0) {
                        row.amountInput.error = activity.getString(R.string.investment_lot_split_amount_required)
                        return@setOnClickListener
                    }
                    val annualRate = parseLocalizedAmount(row.rateInput.text?.toString().orEmpty())
                    if (!InvestmentInterestService.isValidAnnualRate(annualRate)) {
                        row.rateInput.error = "请输入大于 -100 且不超过 10000 的年利率"
                        return@setOnClickListener
                    }
                    InvestmentLotDraft(
                        amount = amount,
                        schedule = InvestmentInterestService.InvestmentSchedule(
                            startEarningAt = row.startEarningAt,
                            firstPayoutAt = InvestmentInterestService.firstPayoutFor(
                                row.startEarningAt,
                                cycleOptions[row.cycleInput.selectedItemPosition.coerceAtLeast(0)].second
                            ),
                            annualInterestRate = annualRate,
                            settlementCycle = cycleOptions[row.cycleInput.selectedItemPosition.coerceAtLeast(0)].second
                        )
                    )
                }
                val sum = BillAssetImpactService.roundMoney(drafts.sumOf { it.amount })
                if (abs(sum - totalAmount) > 0.01) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.investment_lot_split_sum_mismatch),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(drafts)
            }
        }
        val buttonHeight = activity.resources.getDimensionPixelSize(R.dimen.dialog_button_height)
        actionRow.addView(cancelButton, LinearLayout.LayoutParams(0, buttonHeight, 1f).apply {
            rightMargin = dp(activity, 6)
        })
        actionRow.addView(laterButton, LinearLayout.LayoutParams(0, buttonHeight, 1f).apply {
            leftMargin = dp(activity, 6)
            rightMargin = dp(activity, 6)
        })
        actionRow.addView(confirmButton, LinearLayout.LayoutParams(0, buttonHeight, 1f).apply {
            leftMargin = dp(activity, 6)
        })
        content.addView(actionRow)

        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun formatDateForSchedule(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd E", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun parseLocalizedAmount(raw: String): Double {
        val value = raw.trim().removeSuffix("%").trim()
        if (value.isEmpty() || value == "-") return 0.0

        val localized = NumberFormat.getNumberInstance(Locale.getDefault())
        val parsePosition = ParsePosition(0)
        localized.parse(value, parsePosition)?.toDouble()
            ?.takeIf { parsePosition.index == value.length }
            ?.let { return it }

        val normalized = value
            .replace("\\s".toRegex(), "")
            .replace(',', '.')

        return normalized.toDoubleOrNull() ?: 0.0
    }

    private fun formatMoney(value: Double, currency: String): String {
        return CurrencyUtils.formatAmount(value, currency)
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }

    private fun roundedDrawable(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun roundedStrokeDrawable(
        activity: AppCompatActivity,
        color: Int,
        strokeColor: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(activity, 1), strokeColor)
        }
    }
}
