package com.taostudio.tapaccounting.ui.main.stats

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.BillColors
import com.taostudio.tapaccounting.R
import java.util.Locale

class DailyReportAdapter(
    private var reports: List<TimeReport> = emptyList(),
    private val onItemClick: (TimeReport) -> Unit
) : RecyclerView.Adapter<DailyReportAdapter.ViewHolder>() {

    fun submitList(newReports: List<TimeReport>) {
        reports = newReports
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_daily_report, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val report = reports[position]
        holder.tvDate.text = report.dateString
        holder.tvIncome.text = String.format(Locale.getDefault(), "楼%.2f", report.income)
        holder.tvExpense.text = String.format(Locale.getDefault(), "楼%.2f", report.expense)
        
        holder.tvBalance.text = String.format(Locale.getDefault(), "楼%.2f", report.balance)
        if (report.balance < 0) {
            holder.tvBalance.setTextColor(BillColors.expense)
        } else {
            holder.tvBalance.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green for positive
        }

        holder.itemView.setOnClickListener { onItemClick(report) }
    }

    override fun getItemCount() = reports.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDate: TextView = itemView.findViewById(R.id.tv_date)
        val tvIncome: TextView = itemView.findViewById(R.id.tv_income)
        val tvExpense: TextView = itemView.findViewById(R.id.tv_expense)
        val tvBalance: TextView = itemView.findViewById(R.id.tv_balance)
    }
}
