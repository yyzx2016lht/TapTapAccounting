package com.taostudio.tapaccounting.ui.dialog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索专用的资产多选弹窗：复用记账表单的图标宫格样式（底部弹出），
 * 点选切换勾选，标题显示已选数量，底部确认为准（草稿制，不污染记账排序）。
 */
object SearchAssetPickerDialog {

    fun show(
        activity: AppCompatActivity,
        initialSelected: Set<String>,
        onConfirm: (Set<String>) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_asset_picker, null)
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val draft = initialSelected.toMutableSet()

        val tvTitle: TextView = view.findViewById(R.id.tv_asset_picker_title)
        val tvHint: TextView = view.findViewById(R.id.tv_asset_picker_hint)
        val rv: RecyclerView = view.findViewById(R.id.rv_assets)
        val btnConfirm: Button = view.findViewById(R.id.btn_confirm_asset)
        btnConfirm.visibility = View.VISIBLE
        btnConfirm.text = activity.getString(R.string.confirm_btn)

        fun refreshTitle() {
            tvTitle.text = if (draft.isEmpty()) {
                activity.getString(R.string.search_pick_account)
            } else {
                activity.getString(R.string.search_pick_account_count_fmt, draft.size)
            }
        }
        refreshTitle()

        // 右上提示位复用为“清空”入口。
        tvHint.text = activity.getString(R.string.search_clear_all)
        tvHint.setTextColor(Color.parseColor("#5C6BC0"))
        tvHint.setOnClickListener {
            draft.clear()
            (rv.adapter as? AssetAdapter)?.refresh()
            refreshTitle()
        }

        var assets: List<Asset> = emptyList()
        val adapter = AssetAdapter(emptyList(), draft) { refreshTitle() }
        rv.layoutManager = GridLayoutManager(activity, 5)
        rv.adapter = adapter

        btnConfirm.setOnClickListener {
            onConfirm(draft.toSet())
            dialog.dismiss()
        }

        CoroutineScope(Dispatchers.Main).launch {
            assets = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(activity).assetDao().getAllAssetsListForPicker()
            }
            if (!activity.isFinishing) {
                adapter.setAssets(assets)
            }
            OverlayDialogs.showPageBottomDialog(
                dialog = dialog,
                ctx = activity,
                widthRatio = 0.9f,
                y = 150,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = false
            )
        }
    }

    private class AssetAdapter(
        private var assets: List<Asset>,
        private val draft: MutableSet<String>,
        private val onChanged: () -> Unit
    ) : RecyclerView.Adapter<AssetAdapter.VH>() {

        init { setHasStableIds(true) }

        fun setAssets(list: List<Asset>) {
            assets = list
            notifyDataSetChanged()
        }

        fun refresh() = notifyDataSetChanged()

        override fun getItemId(position: Int): Long = assets[position].id

        inner class VH(v: View) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_asset_grid, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = assets.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val asset = assets[position]
            val tv: TextView = holder.itemView.findViewById(R.id.tv_asset_name)
            val tvType: TextView? = holder.itemView.findViewById(R.id.tv_asset_type)
            val iv: ImageView = holder.itemView.findViewById(R.id.iv_asset_icon)
            val selected = draft.contains(asset.name)
            tv.text = asset.name
            tv.setTextColor(
                if (selected) Color.parseColor("#5C6BC0") else Color.parseColor("#333333")
            )
            tvType?.text = if (asset.isArchived) {
                holder.itemView.context.getString(R.string.archived)
            } else ""
            tvType?.visibility = if (asset.isArchived) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (selected || !asset.isArchived) 1f else 0.72f

            if (asset.icon.isNotEmpty()) {
                Glide.with(iv.context)
                    .load(asset.icon)
                    .transform(CircleCrop())
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                    .into(iv)
            } else {
                iv.setImageResource(R.mipmap.ic_launcher_round)
            }
            holder.itemView.setOnClickListener {
                if (draft.contains(asset.name)) draft.remove(asset.name)
                else draft.add(asset.name)
                notifyItemChanged(position)
                onChanged()
            }
        }
    }
}
