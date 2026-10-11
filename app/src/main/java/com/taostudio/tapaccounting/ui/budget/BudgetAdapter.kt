package com.taostudio.tapaccounting.ui.budget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.logic.BudgetService

class BudgetAdapter(
    private val onItemClick: (Budget) -> Unit,
    private val onItemLongClick: (Budget) -> Unit
) : RecyclerView.Adapter<BudgetAdapter.ViewHolder>() {

    private val items = mutableListOf<BudgetService.BudgetOverview>()

    fun submitList(newItems: List<BudgetService.BudgetOverview>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_budget, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], isLast = position == items.lastIndex)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCategory: TextView = itemView.findViewById(R.id.tv_budget_category)
        private val tvAmount: TextView = itemView.findViewById(R.id.tv_budget_amount)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_budget)
        private val tvUsed: TextView = itemView.findViewById(R.id.tv_budget_used)
        private val tvPercent: TextView = itemView.findViewById(R.id.tv_budget_percent)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_budget_status)
        private val tvPace: TextView = itemView.findViewById(R.id.tv_budget_pace)
        private val btnDelete: TextView = itemView.findViewById(R.id.btn_budget_delete)
        private val divider: View = itemView.findViewById(R.id.view_budget_divider)

        fun bind(item: BudgetService.BudgetOverview, isLast: Boolean) {
            val budget = item.budget
            val progress = item.progress
            val context = itemView.context
            val statusColor = BudgetUi.statusColor(context, progress.status)

            tvCategory.text = budget.categoryName ?: context.getString(R.string.budget_monthly_total)
            tvAmount.text = BudgetUi.money(budget.amount)
            BudgetUi.bindPill(
                tvStatus,
                when (progress.status) {
                    BudgetService.BudgetStatus.EXCEEDED -> context.getString(R.string.budget_status_exceeded)
                    BudgetService.BudgetStatus.WARNING -> context.getString(R.string.budget_status_warning)
                    BudgetService.BudgetStatus.NORMAL -> context.getString(R.string.budget_status_normal)
                },
                statusColor
            )
            BudgetUi.bindProgress(progressBar, progress.usedAmount, budget.amount, statusColor)
            tvPercent.text = context.getString(R.string.budget_percent_fmt, progress.percent * 100)
            tvPercent.setTextColor(statusColor)
            tvUsed.text = context.getString(
                R.string.budget_month_spent_fmt,
                BudgetUi.money(progress.usedAmount)
            ) + " · " + if (progress.remaining >= 0) {
                context.getString(R.string.budget_month_left_fmt, BudgetUi.money(progress.remaining))
            } else {
                context.getString(R.string.budget_month_over_fmt, BudgetUi.money(-progress.remaining))
            }
            tvPace.text = when {
                progress.isMonthClosed -> context.getString(R.string.budget_month_closed)
                progress.remaining < 0 -> reasonLabel(progress.riskReason)
                else -> context.getString(
                    R.string.budget_daily_remaining_fmt,
                    progress.remainingDays,
                    progress.dailyRemainingAllowance
                ) + " · " + paceLabel(progress.pace)
            }
            divider.visibility = if (isLast) View.GONE else View.VISIBLE

            itemView.setOnClickListener { onItemClick(budget) }
            itemView.setOnLongClickListener { onItemLongClick(budget); true }
            btnDelete.setOnClickListener { onItemLongClick(budget) }
        }

        private fun paceLabel(pace: BudgetService.BudgetPace): String {
            val context = itemView.context
            return when (pace) {
                BudgetService.BudgetPace.AHEAD -> context.getString(R.string.budget_pace_ahead)
                BudgetService.BudgetPace.ON_TRACK -> context.getString(R.string.budget_pace_on_track)
                BudgetService.BudgetPace.HAS_ROOM -> context.getString(R.string.budget_pace_has_room)
            }
        }

        private fun reasonLabel(reason: BudgetService.BudgetRiskReason): String {
            val context = itemView.context
            return when (reason) {
                BudgetService.BudgetRiskReason.EXCEEDED -> context.getString(R.string.budget_reason_exceeded)
                BudgetService.BudgetRiskReason.THRESHOLD_REACHED -> context.getString(R.string.budget_reason_threshold)
                BudgetService.BudgetRiskReason.SPENDING_FAST -> context.getString(R.string.budget_reason_fast)
                BudgetService.BudgetRiskReason.NONE -> context.getString(R.string.budget_reason_none)
            }
        }
    }
}
