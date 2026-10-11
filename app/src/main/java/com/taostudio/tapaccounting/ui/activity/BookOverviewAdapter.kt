package com.taostudio.tapaccounting.ui.activity

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.R
import java.io.File

data class BookOverviewItem(
    val bookName: String,
    val themeColor: Int,
    val bannerPath: String?,
    val expense: Double,
    val income: Double,
    val isCurrentBook: Boolean
)

class BookOverviewAdapter(
    private val onCardClick: (BookOverviewItem) -> Unit,
    private val onOrderChanged: (newOrder: List<String>) -> Unit
) : RecyclerView.Adapter<BookOverviewAdapter.ViewHolder>() {

    private val items = mutableListOf<BookOverviewItem>()
    private var isYearMode: Boolean = false

    fun setYearMode(yearMode: Boolean) {
        if (isYearMode == yearMode) return
        isYearMode = yearMode
        notifyDataSetChanged()
    }

    fun submitList(newItems: List<BookOverviewItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<BookOverviewItem> = items.toList()

    /** 供 ItemTouchHelper 在拖拽结束时调用，交换相邻条目 */
    fun onItemMove(fromPos: Int, toPos: Int) {
        val item = items.removeAt(fromPos)
        items.add(toPos, item)
        notifyItemMoved(fromPos, toPos)
    }

    /** 拖拽结束，通知外部持久化新顺序 */
    fun onDragEnd() {
        onOrderChanged(items.map { it.bookName })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book_overview_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val flCover: View = itemView.findViewById(R.id.flBookCover)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivBookCover)
        private val tvCoverName: TextView = itemView.findViewById(R.id.tvCoverBookName)
        private val tvCoverTag: TextView = itemView.findViewById(R.id.tvCoverActiveTag)
        private val layoutCompactHeader: View = itemView.findViewById(R.id.layoutCompactHeader)
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvBookAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvBookName)
        private val tvTag: TextView = itemView.findViewById(R.id.tvActiveTag)
        private val tvExpenseLabel: TextView = itemView.findViewById(R.id.tvBookExpenseLabel)
        private val tvIncomeLabel: TextView = itemView.findViewById(R.id.tvBookIncomeLabel)
        private val tvExpense: TextView = itemView.findViewById(R.id.tvBookExpense)
        private val tvIncome: TextView = itemView.findViewById(R.id.tvBookIncome)

        fun bind(item: BookOverviewItem) {
            val tagVisibility = if (item.isCurrentBook) View.VISIBLE else View.GONE
            val bannerFile = item.bannerPath?.let { File(it) }
            if (bannerFile != null && bannerFile.exists()) {
                flCover.visibility = View.VISIBLE
                layoutCompactHeader.visibility = View.GONE
                flCover.setBackgroundColor(item.themeColor)
                tvCoverName.text = item.bookName
                tvCoverTag.visibility = tagVisibility
                Glide.with(itemView.context)
                    .load(bannerFile)
                    .centerCrop()
                    // Local file + transform should cache original data only to avoid
                    // NoResultEncoderAvailableException for transformed File results.
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                    .into(ivCover)
            } else {
                Glide.with(itemView.context).clear(ivCover)
                flCover.visibility = View.GONE
                layoutCompactHeader.visibility = View.VISIBLE
                tvAvatar.text = item.bookName.trim().take(1)
                tvAvatar.backgroundTintList = ColorStateList.valueOf(item.themeColor)
                tvName.text = item.bookName
                tvTag.visibility = tagVisibility
            }

            tvExpense.text = "¥${AmountFormatHelper.formatAmount(item.expense)}"
            tvIncome.text = "¥${AmountFormatHelper.formatAmount(item.income)}"
            tvExpense.setTextColor(amountColor(item.expense))
            tvIncome.setTextColor(amountColor(item.income))
            tvExpenseLabel.text = if (isYearMode) "年支出" else "月支出"
            tvIncomeLabel.text = if (isYearMode) "年收入" else "月收入"

            itemView.setOnClickListener { onCardClick(item) }
        }
    }
}

private val AMOUNT_COLOR = Color.parseColor("#1F2733")
private val ZERO_AMOUNT_COLOR = Color.parseColor("#8E98A6")

internal fun amountColor(value: Double): Int =
    if (kotlin.math.abs(value) < 0.005) ZERO_AMOUNT_COLOR else AMOUNT_COLOR

