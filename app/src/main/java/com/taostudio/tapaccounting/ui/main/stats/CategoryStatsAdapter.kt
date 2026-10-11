package com.taostudio.tapaccounting.ui.main.stats

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.CategoryIcons
import com.taostudio.tapaccounting.CategoryIconHelper
import com.taostudio.tapaccounting.R
import java.util.Locale
import kotlin.math.abs

class CategoryStatsAdapter(
    private var chartColors: List<Int> = emptyList(),
    private var items: List<CategoryStat> = emptyList(),
    private var isExpense: Boolean = true,
    private var currencySymbol: String = "¥",
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryStatsAdapter.ViewHolder>() {
    companion object {
        private const val TAG = "CategoryStatsAdapter"
        /** 与普通账单列表一致：支出图标染红、收入图标染绿。 */
        private const val ICON_TINT_EXPENSE = 0xFFFF5252.toInt()
        private const val ICON_TINT_INCOME = 0xFF4CAF50.toInt()
    }

    private var colorMap: Map<String, Int> = emptyMap()
    private var pinnedCategory: String? = null
    private var listRenderSeq = 0L
    private val iconUrlCache = mutableMapOf<String, String>()
    private val loadingIconKeys = mutableSetOf<String>()
    // 缓存对应的分类版本号；分类换图标/被删除后版本号会变，届时整表清空重查。
    private var iconCacheRevision = -1
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setColors(colors: List<Int>) {
        chartColors = colors
    }

    fun setColorMap(map: Map<String, Int>) {
        colorMap = map
    }

    fun submitList(newItems: List<CategoryStat>, isExpense: Boolean, currencySymbol: String) {
        val start = SystemClock.elapsedRealtime()
        items = newItems
        this.isExpense = isExpense
        this.currencySymbol = currencySymbol
        listRenderSeq++
        if (pinnedCategory != null && newItems.none { it.categoryName == pinnedCategory }) {
            pinnedCategory = null
        }
        preloadIconsAsync(newItems, listRenderSeq, isExpense)
        notifyDataSetChanged()
        Log.d(
            TAG,
            "submitList size=${newItems.size}, mode=${if (isExpense) "expense" else "income"}, costMs=${SystemClock.elapsedRealtime() - start}"
        )
    }

    fun findPositionByCategory(categoryName: String): Int =
        displayItems().indexOfFirst { it.categoryName == categoryName }

    fun pinCategory(categoryName: String) {
        if (items.none { it.categoryName == categoryName }) return
        pinnedCategory = categoryName
        notifyDataSetChanged()
    }

    fun clearPinCategory() {
        if (pinnedCategory == null) return
        pinnedCategory = null
        notifyDataSetChanged()
    }

    private fun displayItems(): List<CategoryStat> {
        val pinned = pinnedCategory ?: return items
        val index = items.indexOfFirst { it.categoryName == pinned }
        if (index <= 0) return items
        val mutable = items.toMutableList()
        val selected = mutable.removeAt(index)
        mutable.add(0, selected)
        return mutable
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_category_icon)
        val tvName: TextView = view.findViewById(R.id.tv_category_name)
        val tvPercent: TextView = view.findViewById(R.id.tv_category_percent)
        val pbPercent: ProgressBar = view.findViewById(R.id.pb_category_percent)
        val tvAmount: TextView = view.findViewById(R.id.tv_category_amount)
        val ivArrow: ImageView = view.findViewById(R.id.iv_comparison_arrow)
        val tvComparison: TextView = view.findViewById(R.id.tv_comparison_amount)
        val comparisonContainer: LinearLayout = view.findViewById(R.id.ll_comparison)
        val divider: View = view.findViewById(R.id.view_item_divider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stats_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val bindStart = SystemClock.elapsedRealtime()
        val stat = displayItems()[position]

        holder.itemView.visibility = View.VISIBLE
        holder.itemView.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        holder.tvName.text = stat.categoryName
        holder.tvPercent.text = String.format(Locale.getDefault(), "%.2f%%", stat.percentage)
        holder.pbPercent.progress = stat.percentage.toInt().coerceIn(0, 100)
        holder.tvAmount.text = "$currencySymbol${AmountFormatHelper.formatAmount(stat.amount)}"

        val themeColor = colorMap[stat.categoryName]
            ?: if (chartColors.isNotEmpty()) chartColors[position % chartColors.size] else Color.GRAY
        holder.pbPercent.progressTintList = ColorStateList.valueOf(themeColor)
        // 图标与普通账单保持一致：支出红色、收入绿色；底色用对应的浅色圆。
        // 这里不能用分类图表色给图标染色，否则每行颜色都不一样（历史 bug）。
        holder.ivIcon.backgroundTintList = null
        holder.ivIcon.setBackgroundResource(
            if (isExpense) R.drawable.bg_circle_expense_soft else R.drawable.bg_circle_income_soft
        )
        // 注意：染色必须在 [bindCategoryIcon] 之后设置，因为它在加载前会 clearColorFilter()。
        val typeTint = if (isExpense) ICON_TINT_EXPENSE else ICON_TINT_INCOME

        val type = if (isExpense) 0 else 1
        val ctx = holder.itemView.context
        val iconKey = iconCacheKey(stat.categoryName, type)
        holder.ivIcon.tag = iconKey
        bindCategoryIcon(holder.ivIcon, ctx, stat.categoryName, type, iconKey)
        applyIconTint(holder.ivIcon, iconKey, typeTint)

        holder.divider.visibility = if (position == itemCount - 1) View.GONE else View.VISIBLE
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        holder.itemView.alpha = 1f

        when {
            stat.amountDiffFromLastPeriod > 0 -> {
                holder.comparisonContainer.visibility = View.VISIBLE
                holder.comparisonContainer.setBackgroundResource(R.drawable.bg_stats_comparison_up)
                holder.ivArrow.setImageResource(R.drawable.ic_trend_up)
                holder.ivArrow.setColorFilter(Color.parseColor("#DC2626"))
                holder.tvComparison.setTextColor(Color.parseColor("#DC2626"))
                holder.tvComparison.text = String.format(
                    Locale.getDefault(),
                    "+%s%.2f",
                    currencySymbol,
                    stat.amountDiffFromLastPeriod
                )
            }

            stat.amountDiffFromLastPeriod < 0 -> {
                holder.comparisonContainer.visibility = View.VISIBLE
                holder.comparisonContainer.setBackgroundResource(R.drawable.bg_stats_comparison_down)
                holder.ivArrow.setImageResource(R.drawable.ic_trend_down)
                holder.ivArrow.setColorFilter(Color.parseColor("#059669"))
                holder.tvComparison.setTextColor(Color.parseColor("#059669"))
                holder.tvComparison.text = String.format(
                    Locale.getDefault(),
                    "-%s%.2f",
                    currencySymbol,
                    abs(stat.amountDiffFromLastPeriod)
                )
            }

            else -> {
                holder.comparisonContainer.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener { onItemClick(stat.categoryName) }
        if (position < 3 || SystemClock.elapsedRealtime() - bindStart >= 8L) {
            Log.d(TAG, "bind pos=$position, category=${stat.categoryName}, costMs=${SystemClock.elapsedRealtime() - bindStart}")
        }
    }

    override fun getItemCount() = displayItems().size

    private fun bindCategoryIcon(
        imageView: ImageView,
        context: android.content.Context,
        categoryName: String,
        type: Int,
        iconKey: String
    ) {
        imageView.clearColorFilter()
        imageView.imageTintList = null
        imageView.setImageDrawable(null)

        // 分类换过图标/被删除后，缓存里按名字存的旧图标要整表作废，重新解析
        if (iconCacheRevision != CategoryIcons.revision) {
            iconCacheRevision = CategoryIcons.revision
            iconUrlCache.clear()
            loadingIconKeys.clear()
        }

        val cachedIconUrl = iconUrlCache[iconKey]
        if (cachedIconUrl != null) {
            if (cachedIconUrl.isNotEmpty()) {
                Glide.with(context)
                    .load(cachedIconUrl)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .into(imageView)
            }
            return
        }

        if (!loadingIconKeys.add(iconKey)) return
        adapterScope.launch {
            val iconUrl = withContext(Dispatchers.IO) {
                CategoryIconHelper.findCategoryIcon(context.applicationContext, categoryName, type)
            }
            loadingIconKeys.remove(iconKey)
            iconUrlCache[iconKey] = iconUrl
            if (imageView.tag == iconKey && iconUrl.isNotEmpty()) {
                Glide.with(context)
                    .load(iconUrl)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .into(imageView)
                // 图标加载是异步的，颜色可能被加载流程清掉，这里按当前类型补回来。
                applyIconTint(imageView, iconKey, if (type == 0) ICON_TINT_EXPENSE else ICON_TINT_INCOME)
            }
        }
    }

    /** 按当前行绑定的图标键给图标染色；仅当该 ImageView 仍属于这一行时才生效。 */
    private fun applyIconTint(imageView: ImageView, iconKey: String, tint: Int) {
        if (imageView.tag == iconKey) imageView.setColorFilter(tint)
    }

    private fun preloadIconsAsync(newItems: List<CategoryStat>, seq: Long, isExpense: Boolean) {
        val type = if (isExpense) 0 else 1
        val names = newItems.map { it.categoryName }.distinct()
        if (names.isEmpty()) return
        // 分类版本号变了：先清缓存再预热，否则下面的 containsKey 会把旧图标当成已缓存跳过
        if (iconCacheRevision != CategoryIcons.revision) {
            iconCacheRevision = CategoryIcons.revision
            iconUrlCache.clear()
            loadingIconKeys.clear()
        }
        adapterScope.launch(Dispatchers.IO) {
            val context = lastBoundContext ?: return@launch
            val updates = mutableMapOf<String, String>()
            names.forEach { name ->
                val key = iconCacheKey(name, type)
                if (iconUrlCache.containsKey(key)) return@forEach
                val url = CategoryIconHelper.findCategoryIcon(context, name, type)
                updates[key] = url
            }
            if (updates.isEmpty()) return@launch
            CoroutineScope(Dispatchers.Main).launch {
                if (seq != listRenderSeq) return@launch
                iconUrlCache.putAll(updates)
                notifyItemRangeChanged(0, itemCount, "icon")
                Log.d(TAG, "icon preload done: updated=${updates.size}, totalCache=${iconUrlCache.size}")
            }
        }
    }

    private var lastBoundContext: android.content.Context? = null

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        lastBoundContext = holder.itemView.context.applicationContext
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    private fun iconCacheKey(name: String, type: Int): String = "$type|$name"
}

