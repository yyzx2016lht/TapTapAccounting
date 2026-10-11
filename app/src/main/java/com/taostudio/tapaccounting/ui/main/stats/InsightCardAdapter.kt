package com.taostudio.tapaccounting.ui.main.stats

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.insight.InsightCardModel
import com.taostudio.tapaccounting.logic.insight.InsightSeverity
import com.taostudio.tapaccounting.logic.insight.InsightType

class InsightCardAdapter(
    private val onCardClick: (InsightCardModel) -> Unit
) : ListAdapter<InsightCardModel, InsightCardAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_insight_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), isFirst = position == 0)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val divider: View = itemView.findViewById(R.id.view_insight_divider)
        private val row: View = itemView.findViewById(R.id.layout_insight_row)
        private val iconBg: View = itemView.findViewById(R.id.layout_insight_icon)
        private val icon: ImageView = itemView.findViewById(R.id.iv_insight_icon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_insight_title)
        private val tvBody: TextView = itemView.findViewById(R.id.tv_insight_body)
        private val tvHighlight: TextView = itemView.findViewById(R.id.tv_insight_highlight)
        private val chevron: View = itemView.findViewById(R.id.iv_insight_chevron)

        fun bind(model: InsightCardModel, isFirst: Boolean) {
            val context = itemView.context
            val color = ContextCompat.getColor(
                context,
                when (model.severity) {
                    InsightSeverity.WARN -> R.color.expense_color
                    InsightSeverity.POSITIVE -> R.color.income_color
                    InsightSeverity.INFO -> R.color.brand_primary
                }
            )

            divider.visibility = if (isFirst) View.GONE else View.VISIBLE
            iconBg.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x1F))
            icon.setImageResource(iconOf(model))
            icon.imageTintList = ColorStateList.valueOf(color)

            tvTitle.text = model.title
            tvBody.text = model.body
            tvBody.visibility = if (model.body.isBlank()) View.GONE else View.VISIBLE
            tvHighlight.text = model.highlight.orEmpty()
            tvHighlight.setTextColor(color)
            tvHighlight.visibility = if (model.highlight.isNullOrBlank()) View.GONE else View.VISIBLE

            val clickable = model.action != null
            chevron.visibility = if (clickable) View.VISIBLE else View.GONE
            row.isClickable = clickable
            row.setOnClickListener(if (clickable) View.OnClickListener { onCardClick(model) } else null)
        }

        private fun iconOf(model: InsightCardModel): Int = when (model.type) {
            InsightType.MONTH_TOTAL_DELTA, InsightType.MONTH_CATEGORY_DELTA -> when (model.severity) {
                InsightSeverity.POSITIVE -> R.drawable.ic_trend_down
                InsightSeverity.WARN -> R.drawable.ic_trend_up
                InsightSeverity.INFO ->
                    if (model.highlight == null) R.drawable.ic_insight_flat else R.drawable.ic_trend_up
            }
            InsightType.MONTH_PROJECTION -> R.drawable.ic_trend_up
            InsightType.LARGE_EXPENSE -> R.drawable.ic_insight_receipt
            InsightType.CATEGORY_CONCENTRATION -> R.drawable.ic_insight_pie
            InsightType.WEEKEND_SPEND -> R.drawable.ic_insight_calendar
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<InsightCardModel>() {
            override fun areItemsTheSame(oldItem: InsightCardModel, newItem: InsightCardModel) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: InsightCardModel, newItem: InsightCardModel) = oldItem == newItem
        }
    }
}
