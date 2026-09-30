package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class BuiltInCategoryAdapter(
    private var items: List<BuiltInCategory>,
    private val onSelect: (BuiltInCategory) -> Unit,
    private val onMultiSelectionChanged: (List<BuiltInCategory>) -> Unit
) : RecyclerView.Adapter<BuiltInCategoryAdapter.VH>() {

    private var selectedIconUrl: String? = null
    private var isMultiSelect = false
    private var isInteractionEnabled = true
    private val multiSelection = BuiltInCategorySelection()

    fun updateList(newItems: List<BuiltInCategory>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setSelectedIcon(iconUrl: String?) {
        val oldIconUrl = selectedIconUrl
        selectedIconUrl = iconUrl
        notifyItemsWithIcons(oldIconUrl, iconUrl)
    }

    fun setMultiSelect(enabled: Boolean) {
        isMultiSelect = enabled
        multiSelection.clear()
        notifyDataSetChanged()
        onMultiSelectionChanged(emptyList())
    }

    fun setInteractionEnabled(enabled: Boolean) {
        isInteractionEnabled = enabled
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.iv_built_in_icon)
        val tv: TextView = v.findViewById(R.id.tv_built_in_name)
        val container: View = v.findViewById(R.id.layout_item_container)
        val iconWrap: View = v.findViewById(R.id.layout_icon_wrap)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_built_in_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tv.text = item.name
        Glide.with(holder.itemView)
            .load(CategoryIcons.fallback(item.icon, item.name))
            .into(holder.iv)

        val isSelected = if (isMultiSelect) {
            multiSelection.isSelected(item)
        } else {
            item.icon == selectedIconUrl
        }
        if (isSelected) {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
            holder.iconWrap.setBackgroundResource(R.drawable.bg_category_icon_dot_selected)
            holder.iv.setColorFilter(Color.parseColor("#2196F3"), PorterDuff.Mode.SRC_IN)
            holder.tv.setTextColor(Color.parseColor("#2196F3"))
        } else {
            holder.container.setBackgroundColor(Color.TRANSPARENT)
            holder.iconWrap.background = null
            holder.iv.setColorFilter(Color.parseColor("#7D8796"), PorterDuff.Mode.SRC_IN)
            holder.tv.setTextColor(Color.parseColor("#6E7A8C"))
        }

        holder.itemView.setOnClickListener {
            if (!isInteractionEnabled) return@setOnClickListener

            if (isMultiSelect) {
                multiSelection.toggle(item)
                onMultiSelectionChanged(multiSelection.selectedItems())
                notifyItemsWithNames(item.name.trim())
            } else {
                val oldIconUrl = selectedIconUrl
                selectedIconUrl = item.icon
                onSelect(item)
                notifyItemsWithIcons(oldIconUrl, item.icon)
            }
        }
    }

    override fun getItemCount() = items.size

    private fun notifyItemsWithNames(vararg names: String) {
        val changedNames = names.toSet()
        items.forEachIndexed { index, item ->
            if (item.name.trim() in changedNames) notifyItemChanged(index)
        }
    }

    private fun notifyItemsWithIcons(vararg icons: String?) {
        val changedIcons = icons.toSet()
        items.forEachIndexed { index, item ->
            if (item.icon in changedIcons) notifyItemChanged(index)
        }
    }
}

