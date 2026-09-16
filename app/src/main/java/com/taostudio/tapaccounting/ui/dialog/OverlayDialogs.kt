package com.taostudio.tapaccounting.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.WindowCompat
import android.view.WindowManager.BadTokenException
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.CategoryNode
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import java.io.File
import java.util.*

object OverlayDialogs {
    private const val TAG = "OverlayDialogs"

    private sealed class AssetPickerItem {
        data class AssetItem(val asset: com.taostudio.tapaccounting.data.local.entity.Asset) : AssetPickerItem()
        data class ArchivedGroup(val count: Int, val expanded: Boolean) : AssetPickerItem()
        object ClearItem : AssetPickerItem()
    }

    private fun setExactVisibleRowsHeight(target: View, rowHeight: Int, rows: Int = 4) {
        if (rowHeight <= 0) return
        val lp = target.layoutParams ?: return
        val maxPickerHeight = (target.resources.displayMetrics.heightPixels * 0.46f).toInt()
        val desired = (rowHeight * rows).coerceAtMost(maxPickerHeight)
        if (lp.height != desired) {
            lp.height = desired
            target.layoutParams = lp
        }
    }

    private fun navigationBarHeight(ctx: Context): Int {
        val res = ctx.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    private fun bottomDialogYOffset(ctx: Context, legacyYOffset: Int): Int {
        if (legacyYOffset <= 0) return legacyYOffset
        val baseOffset = (48f * ctx.resources.displayMetrics.density).toInt()
        return baseOffset + navigationBarHeight(ctx)
    }

    private fun loadSafeCategoryIcon(ctx: Context, icon: String, imageView: ImageView) {
        if (icon.isBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        val file = File(icon)
        if (file.exists()) {
            Glide.with(ctx)
                .load(file)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(imageView)
        } else {
            Glide.with(ctx)
                .load(icon)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(imageView)
        }
    }

    private fun canUseOverlayWindow(ctx: Context): Boolean {
        return if (ctx is Activity) Settings.canDrawOverlays(ctx) else true
    }

    private fun unwrapContext(ctx: Context): Context {
        return if (ctx is ContextThemeWrapper) ctx.baseContext else ctx
    }

    private fun shouldUseOverlayWindow(ctx: Context): Boolean {
        return unwrapContext(ctx) !is Activity
    }

    private fun isContextAlive(ctx: Context): Boolean {
        val unwrapped = unwrapContext(ctx)
        if (unwrapped !is Activity) return true
        if (unwrapped.isFinishing) return false
        return !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && unwrapped.isDestroyed)
    }

    private fun isAnchorReady(anchor: View): Boolean {
        return anchor.isAttachedToWindow && anchor.windowToken != null
    }

    private fun applyOverlayTypeIfAllowed(dialog: AlertDialog, ctx: Context) {
        if (!canUseOverlayWindow(ctx)) return
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
        )
    }

    private fun applyBottomPickerEnterAnimation(
        dialog: AlertDialog,
        panelView: View,
        vararg contentViews: View?,
        onShown: (() -> Unit)? = null
    ) {
        dialog.setOnShowListener {
            val offsetY = 18f * panelView.resources.displayMetrics.density
            panelView.animate().cancel()
            panelView.alpha = 0f
            panelView.translationY = offsetY
            contentViews.filterNotNull().forEach { content ->
                content.animate().cancel()
                content.alpha = 0f
            }
            panelView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(com.taostudio.tapaccounting.ui.common.UiMotion.NORMAL)
                .setInterpolator(com.taostudio.tapaccounting.ui.common.UiMotion.STANDARD_EASING)
                .start()
            contentViews.filterNotNull().forEach { content ->
                content.animate()
                    .alpha(1f)
                    .setStartDelay(70L)
                    .setDuration(180L)
                    .start()
            }
            onShown?.invoke()
        }
    }

    private fun styleDialogWindow(
        dialog: AlertDialog,
        ctx: Context,
        widthRatio: Float = 0.86f,
        gravity: Int = Gravity.CENTER,
        y: Int = 0,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        dimAmount: Float = 0.34f,
        clearDecorPadding: Boolean = false
    ) {
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val animationStyle = if (gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
                R.style.Animation_TapAccounting_BottomDialogSoft
            } else {
                R.style.Animation_TapAccounting_DialogSoft
            }
            window.setWindowAnimations(animationStyle)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window.setGravity(gravity)
            window.setDimAmount(dimAmount)
            if (clearDecorPadding) {
                window.decorView.setPadding(0, 0, 0, 0)
            }
            val width = (ctx.resources.displayMetrics.widthPixels * widthRatio).toInt()
            val resolvedY = if (gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
                bottomDialogYOffset(ctx, y)
            } else {
                y
            }
            window.attributes = window.attributes.apply {
                this.width = width
                this.height = height
                this.y = resolvedY
            }
        }
    }

    private fun showStyledDialog(
        dialog: AlertDialog,
        ctx: Context,
        widthRatio: Float = 0.86f,
        gravity: Int = Gravity.CENTER,
        y: Int = 0,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        dimAmount: Float = 0.34f,
        clearDecorPadding: Boolean = false,
        applyOverlayType: Boolean = false
    ) {
        styleDialogWindow(
            dialog = dialog,
            ctx = ctx,
            widthRatio = widthRatio,
            gravity = gravity,
            y = y,
            height = height,
            dimAmount = dimAmount,
            clearDecorPadding = clearDecorPadding
        )
        if (!isContextAlive(ctx)) return
        if (applyOverlayType || shouldUseOverlayWindow(ctx)) applyOverlayTypeIfAllowed(dialog, ctx)
        try {
            dialog.show()
        } catch (e: BadTokenException) {
            Log.w(TAG, "Ignore dialog show due to invalid token: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Ignore dialog show due to illegal window state: ${e.message}")
        }
    }

    fun showPageCenterDialog(
        dialog: AlertDialog,
        ctx: Context,
        widthRatio: Float = 0.88f,
        cancelOnTouchOutside: Boolean = true,
        dimAmount: Float = 0.34f,
        useSolidPanelBackground: Boolean = true
    ) {
        dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
        showStyledDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = widthRatio,
            gravity = Gravity.CENTER,
            y = 0,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            dimAmount = dimAmount,
            clearDecorPadding = false,
            applyOverlayType = false
        )
        if (useSolidPanelBackground) {
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bg)
        }
    }

    fun showOverlayCenterDialog(
        dialog: AlertDialog,
        ctx: Context,
        widthRatio: Float = 0.88f,
        cancelOnTouchOutside: Boolean = true,
        dimAmount: Float = 0.34f,
        useSolidPanelBackground: Boolean = true
    ) {
        dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
        showStyledDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = widthRatio,
            gravity = Gravity.CENTER,
            y = 0,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            dimAmount = dimAmount,
            clearDecorPadding = false,
            applyOverlayType = true
        )
        if (useSolidPanelBackground) {
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bg)
        }
    }

    fun showPageBottomDialog(
        dialog: AlertDialog,
        ctx: Context,
        widthRatio: Float = 0.94f,
        y: Int = 0,
        cancelOnTouchOutside: Boolean = true,
        dimAmount: Float = 0.34f,
        useSolidPanelBackground: Boolean = true
    ) {
        dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
        showStyledDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = widthRatio,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            y = y,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            dimAmount = dimAmount,
            clearDecorPadding = false,
            applyOverlayType = false
        )
        if (useSolidPanelBackground) {
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bg)
        }
    }

    fun showOverlayBottomDialog(
        dialog: AlertDialog,
        ctx: Context,
        widthRatio: Float = 0.94f,
        y: Int = 0,
        cancelOnTouchOutside: Boolean = true,
        dimAmount: Float = 0.34f,
        useSolidPanelBackground: Boolean = true
    ) {
        dialog.setCanceledOnTouchOutside(cancelOnTouchOutside)
        showStyledDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = widthRatio,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            y = y,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            dimAmount = dimAmount,
            clearDecorPadding = false,
            applyOverlayType = true
        )
        if (useSolidPanelBackground) {
            dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bg)
        }
    }

    fun showAnchoredMenu(ctx: Context, anchor: View, items: List<String>, onSelected: (String) -> Unit) {
        if (!isContextAlive(ctx) || !isAnchorReady(anchor) || items.isEmpty()) return
        val popup = ListPopupWindow(ctx).apply {
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, items))
            anchorView = anchor
            width = anchor.width
            isModal = true
            setOnItemClickListener { _, _, pos, _ ->
                onSelected(items[pos])
                dismiss()
            }
        }
        try {
            popup.show()
        } catch (e: BadTokenException) {
            Log.w(TAG, "Ignore anchored menu show due to invalid token: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Ignore anchored menu show due to illegal window state: ${e.message}")
        }
    }
    fun showGridCategoryPicker(
        ctx: Context,
        currentSelectionText: String,
        type: Int,
        allowClear: Boolean = true,
        onConfirm: (String) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_category_picker, null)

        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val container = view.findViewById<LinearLayout>(R.id.container_categories)
        val scrollCategories = view.findViewById<ScrollView>(R.id.scroll_categories)
        val rvSortCategories = view.findViewById<RecyclerView>(R.id.rv_sort_categories)
        view.findViewById<Button>(R.id.btn_confirm_category)?.visibility = View.GONE
        var currentSelection = currentSelectionText.replace(" - ", "/::/").replace(" > ", "/::/")

        val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0
        val normalIconColor = if (dbType == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
        val normalBgColor   = if (dbType == 1) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE")
        val selectedIconColor = Color.parseColor("#5C6BC0")
        val selectedBgColor   = Color.parseColor("#E8EAF6")
        val categoryRepository = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        fun applyIconStyle(itemView: View, isSelected: Boolean) {
            val ivIcon = itemView.findViewById<ImageView>(R.id.iv_category_icon)
            val iconContainer = itemView.findViewById<View>(R.id.layout_category_icon_container)
            ivIcon.setColorFilter(if (isSelected) selectedIconColor else normalIconColor)
            iconContainer.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (isSelected) selectedBgColor else normalBgColor)
            }
        }

        fun render(categories: List<CategoryNode>) {
            container.removeAllViews()
            val parts = currentSelection.split("/::/")
            val parent = categories.find { it.name == parts.getOrNull(0) }

            categories.chunked(5).forEach { row ->
                val rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { cat ->
                    val itemView = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, rowLayout, false)
                    val isSelected = cat.name == parent?.name
                    itemView.findViewById<TextView>(R.id.tv_category_name).apply {
                        text = cat.name
                        setTextColor(if (isSelected) Color.parseColor("#5C6BC0") else Color.parseColor("#333333"))
                    }
                    applyIconStyle(itemView, isSelected)
                    loadSafeCategoryIcon(ctx, cat.icon, itemView.findViewById(R.id.iv_category_icon))
                    itemView.setOnClickListener {
                        val selectedParent = currentSelection.split("/::/").getOrNull(0)
                        when {
                            cat.subs.isEmpty() -> {
                                onConfirm(cat.name)
                                dialog.dismiss()
                            }
                            selectedParent == cat.name -> {
                                onConfirm(cat.name)
                                dialog.dismiss()
                            }
                            else -> {
                                currentSelection = cat.name
                                render(categories)
                            }
                        }
                    }
                    rowLayout.addView(itemView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (row.size < 5) {
                    for (i in 0 until (5 - row.size)) rowLayout.addView(View(ctx), LinearLayout.LayoutParams(0, -2, 1f))
                }
                container.addView(rowLayout)
                if (parent != null && row.any { it.name == parent.name } && parent.subs.isNotEmpty()) {
                    val anchorIndex = row.indexOfFirst { it.name == parent.name }.coerceAtLeast(0)
                    container.addView(createSubPanel(ctx, parent, anchorIndex, parts.getOrNull(1), dbType, {
                        onConfirm("${parent.name} > ${it.name}")
                        dialog.dismiss()
                    }))
                }
            }

            // 常驻「不分类」清空项：始终排在分类列表最后
            if (allowClear) {
                val clearRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val clearItem = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, clearRow, false)
                clearItem.findViewById<TextView>(R.id.tv_category_name).apply {
                    text = ctx.getString(R.string.clear_category)
                    setTextColor(Color.parseColor("#6E7D94"))
                }
                clearItem.findViewById<ImageView>(R.id.iv_category_icon).apply {
                    setColorFilter(Color.parseColor("#90A4AE"))
                    setImageResource(R.drawable.ic_close_small)
                }
                clearItem.findViewById<View>(R.id.layout_category_icon_container).background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#F0F2F5"))
                    }
                clearItem.setOnClickListener {
                    onConfirm("")
                    dialog.dismiss()
                }
                clearRow.addView(clearItem, LinearLayout.LayoutParams(0, -2, 1f))
                for (i in 0 until 4) clearRow.addView(View(ctx), LinearLayout.LayoutParams(0, -2, 1f))
                container.addView(clearRow)
            }
        }

        // 异步从数据库加载分类，然后渲染
        CoroutineScope(Dispatchers.Main).launch {
            val categories = withContext(Dispatchers.IO) { categoryRepository.getCategoryTree(dbType) }
            render(categories)
            applyBottomPickerEnterAnimation(dialog, view, scrollCategories)
            showStyledDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.9f,
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                y = 150,
                clearDecorPadding = true
            )
        }
    }

    private fun createSubPanel(
        ctx: Context,
        parent: CategoryNode,
        anchorIndexInRow: Int,
        selected: String?,
        dbType: Int,
        onClick: (CategoryNode) -> Unit
    ): View {
        val normalIconColor   = if (dbType == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
        val normalBgColor     = if (dbType == 1) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE")
        val selectedIconColor = Color.parseColor("#5C6BC0")
        val selectedBgColor   = Color.parseColor("#E8EAF6")
        val wrapper = FrameLayout(ctx)
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_rule_page_section)
            setPadding(10, 10, 10, 8)
        }
        val pointer = ImageView(ctx).apply {
            setImageResource(R.drawable.bg_category_subpanel_pointer)
        }
        val density = ctx.resources.displayMetrics.density
        val dialogContentWidth = (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt() - (32 * density).toInt()
        val cellWidth = dialogContentWidth / 5f
        val pointerWidth = (20 * density).toInt()
        val pointerHeight = (10 * density).toInt()
        val panelTopMargin = pointerHeight - (1 * density).toInt()
        val pointerLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (pointerWidth / 2f)).toInt()
            .coerceAtLeast(0)
        val borderCutWidth = (22 * density).toInt()
        val borderCutHeight = (2 * density).toInt().coerceAtLeast(1)
        val borderCutLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (borderCutWidth / 2f)).toInt()
            .coerceAtLeast(0)
        val grid = GridLayout(ctx).apply {
            columnCount = 5
            setPadding(0, 0, 0, 0)
            parent.subs.forEach { sub ->
                val item = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, this, false)
                val isSelected = sub.name == selected
                item.findViewById<TextView>(R.id.tv_category_name).apply {
                    text = sub.name
                    setTextColor(if (isSelected) Color.parseColor("#5C6BC0") else Color.parseColor("#333333"))
                }
                val ivIcon = item.findViewById<ImageView>(R.id.iv_category_icon)
                ivIcon.setColorFilter(if (isSelected) selectedIconColor else normalIconColor)
                item.findViewById<View>(R.id.layout_category_icon_container).background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (isSelected) selectedBgColor else normalBgColor)
                    }
                loadSafeCategoryIcon(ctx, sub.icon, ivIcon)
                item.setOnClickListener { onClick(sub) }
                addView(
                    item,
                    GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    ).apply { width = 0 }
                )
            }
            val remainder = parent.subs.size % 5
            if (remainder != 0) {
                for (i in 0 until (5 - remainder)) {
                    addView(
                        View(ctx),
                        GridLayout.LayoutParams(
                            GridLayout.spec(GridLayout.UNDEFINED, 1f),
                            GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        ).apply { width = 0 }
                    )
                }
            }
        }
        panel.addView(grid)
        wrapper.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = panelTopMargin
            }
        )
        wrapper.addView(
            View(ctx).apply {
                setBackgroundColor(Color.parseColor("#F9FBFF"))
            },
            FrameLayout.LayoutParams(borderCutWidth, borderCutHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = borderCutLeft
                topMargin = panelTopMargin
            }
        )
        wrapper.addView(
            pointer,
            FrameLayout.LayoutParams(pointerWidth, pointerHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = pointerLeft
                topMargin = 0
            }
        )
        return wrapper.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 2, 0, 4)
            }
        }
    }

    fun showCategorySortDialog(ctx: Context, type: Int, onSaved: (() -> Unit)? = null) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val repo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0

        val container = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 12)
        }

        val tip = TextView(themeContext).apply {
            text = ctx.getString(R.string.category_sort_tip)
            setTextColor(Color.parseColor("#8A8A8A"))
            textSize = 12f
            setPadding(8, 0, 8, 18)
        }
        container.addView(tip)

        val rv = RecyclerView(themeContext).apply {
            layoutManager = LinearLayoutManager(themeContext)
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundResource(R.drawable.bg_search_box)
            clipToPadding = false
            setPadding(0, 8, 0, 8)
        }
        container.addView(
            rv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (themeContext.resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        )

        val flatList = mutableListOf<Category>()
        val adapter = object : RecyclerView.Adapter<SortVH>() {
            init {
                setHasStableIds(true)
            }

            override fun getItemId(position: Int): Long = flatList[position].id

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SortVH {
                val row = LinearLayout(themeContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (54 * themeContext.resources.displayMetrics.density).toInt()
                    setPadding(18, 10, 18, 10)
                }

                val name = TextView(themeContext).apply {
                    id = android.R.id.text1
                    setTextColor(Color.parseColor("#333333"))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val promote = TextView(themeContext).apply {
                    id = android.R.id.button1
                    text = ctx.getString(R.string.promote_to_top_level)
                    textSize = 11f
                    setTextColor(Color.parseColor("#5E86FF"))
                    setPadding(14, 8, 14, 8)
                    setBackgroundResource(R.drawable.bg_search_box)
                    visibility = View.GONE
                }
                val drag = TextView(themeContext).apply {
                    text = "\u2261"
                    setTextColor(Color.parseColor("#B7B7B7"))
                    textSize = 19f
                    setPadding(18, 0, 0, 0)
                }
                row.addView(name)
                row.addView(promote)
                row.addView(drag)
                return SortVH(row)
            }

            override fun getItemCount(): Int = flatList.size

            override fun onBindViewHolder(holder: SortVH, position: Int) {
                val item = flatList[position]
                val isChild = item.parentId != null
                holder.title.text = if (isChild) "    ${item.name}" else item.name
                holder.title.setTypeface(null, if (isChild) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
                holder.promote?.visibility = if (isChild) View.VISIBLE else View.GONE
                holder.promote?.setOnClickListener {
                    val index = holder.adapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    flatList[index] = flatList[index].copy(parentId = null)
                    notifyItemChanged(index)
                }
            }
        }
        rv.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false
                val moved = flatList.removeAt(from)
                flatList.add(to, moved)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.03f)?.scaleY(1.03f)?.alpha(0.88f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
        }).attachToRecyclerView(rv)

        val dialog = AlertDialog.Builder(themeContext)
            .setTitle(ctx.getString(R.string.sort_category))
            .setView(container)
            .setPositiveButton(ctx.getString(R.string.save), null)
            .setNegativeButton(ctx.getString(R.string.cancel), null)
            .create()

        CoroutineScope(Dispatchers.Main).launch {
            val all = withContext(Dispatchers.IO) { repo.getCategoriesListByType(dbType) }
            val childrenByParent = all.filter { it.parentId != null }.groupBy { it.parentId }
            all.filter { it.parentId == null }.forEach { parent ->
                flatList += parent
                flatList += childrenByParent[parent.id].orEmpty()
            }
            flatList += all.filter { it.parentId != null && flatList.none { added -> added.id == it.id } }
            adapter.notifyDataSetChanged()

            showStyledDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.92f
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    repo.saveOrderedCategoryTree(flatList.toList())
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        onSaved?.invoke()
                    }
                }
            }
        }
    }

    private class SortVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(android.R.id.text1)
        val promote: TextView? = v.findViewById(android.R.id.button1)
    }

    /**
     * 选择迁移目标分类的网格面板。
     * @param excludeIds  需要从列表中排除的分类 ID（比如正在删除的分类及其子分类）
     * @param title       面板顶部标题
     * @param dbType      0=支出 1=收入
     * @param onConfirm   选择确认后的回调，返回选中的 CategoryNode
     */
    fun showMigrationTargetPicker(
        ctx: Context,
        excludeIds: Set<Long>,
        title: String = ctx.getString(R.string.select_migration_target),
        dbType: Int,
        onConfirm: (CategoryNode) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_category_picker, null)

    // 设置自定义标题
        view.findViewById<TextView>(R.id.dialog_title)?.text = title

        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val container = view.findViewById<LinearLayout>(R.id.container_categories)
        val categoryRepository = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())

    // 当前选中的父级 CategoryNode
        var selectedParent: CategoryNode? = null
    // 当前选中的子级 CategoryNode（可为 null，表示选中父级本身）
        var selectedSub: CategoryNode? = null

        fun render(categories: List<CategoryNode>) {
            container.removeAllViews()
            val normalIconColor   = if (dbType == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
            val normalBgColor     = if (dbType == 1) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE")
            val selectedIconColor = Color.parseColor("#5C6BC0")
            val selectedBgColor   = Color.parseColor("#E8EAF6")

            categories.chunked(5).forEach { row ->
                val rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { cat ->
                    val itemView = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, rowLayout, false)
                    val isSelected = cat.name == selectedParent?.name
                    itemView.findViewById<TextView>(R.id.tv_category_name).apply {
                        text = cat.name
                        setTextColor(if (isSelected) Color.parseColor("#5C6BC0") else Color.parseColor("#333333"))
                    }
                    val ivIcon = itemView.findViewById<ImageView>(R.id.iv_category_icon)
                    ivIcon.setColorFilter(if (isSelected) selectedIconColor else normalIconColor)
                    itemView.findViewById<View>(R.id.layout_category_icon_container).background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (isSelected) selectedBgColor else normalBgColor)
                        }
                    loadSafeCategoryIcon(ctx, cat.icon, ivIcon)
                    itemView.setOnClickListener {
                        if (selectedParent?.name == cat.name) {
                            selectedParent = null
                            selectedSub = null
                        } else {
                            selectedParent = cat
                            selectedSub = null
                        }
                        render(categories)
                    }
                    rowLayout.addView(itemView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (row.size < 5) {
                    for (i in 0 until (5 - row.size)) rowLayout.addView(View(ctx), LinearLayout.LayoutParams(0, -2, 1f))
                }
                container.addView(rowLayout)

                // 展开子分类面板
                val curParent = selectedParent
                if (curParent != null && row.any { it.name == curParent.name } && curParent.subs.isNotEmpty()) {
                    val anchorIndex = row.indexOfFirst { it.name == curParent.name }.coerceAtLeast(0)
                    container.addView(createSubPanel(ctx, curParent, anchorIndex, selectedSub?.name, dbType, { sub ->
                        selectedSub = if (selectedSub?.name == sub.name) null else sub
                        render(categories)
                    }))
                }
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val allCategories = withContext(Dispatchers.IO) { categoryRepository.getCategoryTree(dbType) }
            // 过滤掉需要排除的分类（自身及其子分类）
            val filtered = allCategories
                .filter { it.id !in excludeIds }
                .map { parent ->
                    val filteredSubs = parent.subs.filter { it.id !in excludeIds }.toMutableList()
                    CategoryNode(parent.name, parent.icon, filteredSubs).also { it.id = parent.id }
                }
            render(filtered)
        }

        view.findViewById<Button>(R.id.btn_confirm_category).setOnClickListener {
            val chosen = selectedSub ?: selectedParent
            if (chosen == null) {
                Toast.makeText(ctx, ctx.getString(R.string.please_select_category), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onConfirm(chosen)
            dialog.dismiss()
        }

        showStyledDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.9f,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            y = 150,
            clearDecorPadding = true
        )
    }

    fun showBookPickerDialog(ctx: Context, books: List<String>, currentBook: String, onConfirm: (String) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_book_picker, null)
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val rv = view.findViewById<RecyclerView>(R.id.rv_books)
        val sourceBooks = books
            .map { BookAccountManager.normalizeBookName(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val includeAllBook = sourceBooks.contains(BookAccountManager.ALL_BOOK)
        val defaultBook = BookAccountManager.getDefaultBook(ctx, sourceBooks)
        val currentSelection = BookAccountManager.normalizeBookName(currentBook)
        var collapsedGroupExpanded = false
        fun displayBooks(): List<String> = BookAccountManager.getDisplayBookAccounts(
            context = ctx,
            books = sourceBooks,
            includeAllBook = includeAllBook,
            collapsedGroupExpanded = collapsedGroupExpanded,
            defaultBookName = defaultBook
        )
        var visibleBooks = displayBooks()
        var selectedIndex = visibleBooks.indexOfFirst { it == currentSelection }

        // 账本很多时限制列表高度，避免弹窗过长；列表内部保持可滚动
        val density = ctx.resources.displayMetrics.density
        val itemHeightPx = (52f * density).toInt()
        val maxListHeightPx = (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
        val estimatedListHeight = sourceBooks.size * itemHeightPx
        rv.layoutParams = rv.layoutParams.apply {
            height = if (estimatedListHeight > maxListHeightPx) {
                maxListHeightPx
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        rv.isNestedScrollingEnabled = true
        rv.overScrollMode = View.OVER_SCROLL_NEVER

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = LayoutInflater.from(themeContext).inflate(R.layout.item_book_picker, parent, false)
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun getItemCount(): Int = visibleBooks.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val book = visibleBooks[position]
                val itemView = holder.itemView
                val tvName = itemView.findViewById<TextView>(R.id.tv_book_name)
                val ivCheck = itemView.findViewById<ImageView>(R.id.iv_book_selected)
                val ivIcon = itemView.findViewById<ImageView>(R.id.iv_book_icon)
                val isSelected = book == currentSelection
                tvName.text = book
                tvName.setTextColor(if (isSelected) Color.parseColor("#5C6BC0") else Color.parseColor("#333333"))
                ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                ivIcon.alpha = if (isSelected) 1f else 0.75f
                itemView.background = androidx.core.content.ContextCompat.getDrawable(
                    themeContext,
                    if (isSelected) R.drawable.bg_book_item_selected else R.drawable.bg_book_item_normal
                )
                itemView.setOnClickListener {
                    if (book == BookAccountManager.COLLAPSED_BOOK_GROUP) {
                        collapsedGroupExpanded = !collapsedGroupExpanded
                        visibleBooks = displayBooks()
                        selectedIndex = visibleBooks.indexOfFirst { it == currentSelection }
                        notifyDataSetChanged()
                        return@setOnClickListener
                    }
                    onConfirm(book)
                    dialog.dismiss()
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(themeContext)
        rv.adapter = adapter
        applyBottomPickerEnterAnimation(dialog, view, rv) {
            if (selectedIndex >= 0) {
                rv.post {
                    // Keep picker in IDLE state on first frame to avoid
                    // occasional first-tap loss caused by settling scroll.
                    rv.stopScroll()
                    rv.scrollToPosition(selectedIndex)
                }
            }
        }
         showStyledDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.9f,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            y = 150,
            clearDecorPadding = true
        )
    }

    fun showCustomTimePicker(
        ctx: Context,
        initialTimeMillis: Long? = null,
        onConfirm: (String) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_custom_time_picker, null)
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val cal = Calendar.getInstance().apply {
            initialTimeMillis?.let { timeInMillis = it }
        }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val initialYear = cal.get(Calendar.YEAR)
        // 不再使用业务硬编码年份，改为宽范围动态窗口，覆盖历史补录和未来计划场景
        val minYearBound = minOf(1900, initialYear - 50, currentYear - 50)
        val maxYearBound = maxOf(2200, initialYear + 50, currentYear + 50)
        val npYear = view.findViewById<NumberPicker>(R.id.np_year).apply {
            minValue = minYearBound
            maxValue = maxYearBound
            value = cal.get(Calendar.YEAR)
            wrapSelectorWheel = false
        }
        val npMonth = view.findViewById<NumberPicker>(R.id.np_month).apply {
            minValue = 1
            maxValue = 12
            value = cal.get(Calendar.MONTH) + 1
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val npDay = view.findViewById<NumberPicker>(R.id.np_day).apply {
            minValue = 1
            maxValue = 31
            value = cal.get(Calendar.DAY_OF_MONTH)
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val npHour = view.findViewById<NumberPicker>(R.id.np_hour).apply {
            minValue = 0
            maxValue = 23
            value = cal.get(Calendar.HOUR_OF_DAY)
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val npMin = view.findViewById<NumberPicker>(R.id.np_minute).apply {
            minValue = 0
            maxValue = 59
            value = cal.get(Calendar.MINUTE)
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }

        var suppressLinkedUpdate = false

        fun maxDayOf(year: Int, month: Int): Int {
            val temp = Calendar.getInstance()
            temp.set(Calendar.YEAR, year)
            temp.set(Calendar.MONTH, month - 1)
            return temp.getActualMaximum(Calendar.DAY_OF_MONTH)
        }

        fun applyDateToPickers(calendar: Calendar) {
            suppressLinkedUpdate = true
            npYear.value = calendar.get(Calendar.YEAR)
            npMonth.value = calendar.get(Calendar.MONTH) + 1
            val newMaxDay = maxDayOf(npYear.value, npMonth.value)
            npDay.maxValue = newMaxDay
            npDay.value = calendar.get(Calendar.DAY_OF_MONTH).coerceAtMost(newMaxDay)
            suppressLinkedUpdate = false
        }

        fun buildCurrentDate(): Calendar {
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, npYear.value)
                set(Calendar.MONTH, npMonth.value - 1)
                set(Calendar.DAY_OF_MONTH, npDay.value.coerceAtMost(maxDayOf(npYear.value, npMonth.value)))
                set(Calendar.HOUR_OF_DAY, npHour.value)
                set(Calendar.MINUTE, npMin.value)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        fun buildDateWithValues(
            year: Int = npYear.value,
            month: Int = npMonth.value,
            day: Int = npDay.value,
            hour: Int = npHour.value,
            minute: Int = npMin.value
        ): Calendar {
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day.coerceAtMost(maxDayOf(year, month)))
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        npDay.maxValue = maxDayOf(npYear.value, npMonth.value)

        npYear.setOnValueChangedListener { _, _, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            val newMaxDay = maxDayOf(newVal, npMonth.value)
            suppressLinkedUpdate = true
            npDay.maxValue = newMaxDay
            if (npDay.value > newMaxDay) npDay.value = newMaxDay
            suppressLinkedUpdate = false
        }

        npMonth.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            val current = buildDateWithValues(month = oldVal)
            when {
                oldVal == 12 && newVal == 1 -> current.add(Calendar.YEAR, 1)
                oldVal == 1 && newVal == 12 -> current.add(Calendar.YEAR, -1)
                else -> current.set(Calendar.MONTH, newVal - 1)
            }
            applyDateToPickers(current)
        }

        npDay.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            val currentMaxDay = maxDayOf(npYear.value, npMonth.value)
            val current = buildDateWithValues(day = oldVal)
            when {
                oldVal == currentMaxDay && newVal == 1 -> current.add(Calendar.DAY_OF_MONTH, 1)
                oldVal == 1 && newVal >= 28 -> current.add(Calendar.DAY_OF_MONTH, -1)
                else -> current.set(Calendar.DAY_OF_MONTH, newVal)
            }
            applyDateToPickers(current)
        }

        npHour.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            when {
                oldVal == 23 && newVal == 0 -> {
                    val current = buildDateWithValues(hour = oldVal)
                    current.add(Calendar.HOUR_OF_DAY, 1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    suppressLinkedUpdate = false
                }
                oldVal == 0 && newVal == 23 -> {
                    val current = buildDateWithValues(hour = oldVal)
                    current.add(Calendar.HOUR_OF_DAY, -1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    suppressLinkedUpdate = false
                }
            }
        }

        npMin.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            when {
                oldVal == 59 && newVal == 0 -> {
                    val current = buildDateWithValues(minute = oldVal)
                    current.add(Calendar.MINUTE, 1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    npMin.value = current.get(Calendar.MINUTE)
                    suppressLinkedUpdate = false
                }
                oldVal == 0 && newVal == 59 -> {
                    val current = buildDateWithValues(minute = oldVal)
                    current.add(Calendar.MINUTE, -1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    npMin.value = current.get(Calendar.MINUTE)
                    suppressLinkedUpdate = false
                }
            }
        }

        view.findViewById<View>(R.id.btn_confirm_time).setOnClickListener {
            onConfirm(String.format(Locale.getDefault(), "%d-%02d-%02d %02d:%02d:00", npYear.value, npMonth.value, npDay.value, npHour.value, npMin.value))
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_cancel_time)?.setOnClickListener { dialog.dismiss() }

        val panelGroup = view as? ViewGroup
        applyBottomPickerEnterAnimation(
            dialog = dialog,
            panelView = view,
            panelGroup?.getChildAt(1),
            panelGroup?.getChildAt(3)
        )
        showStyledDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.9f,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            y = 150,
            clearDecorPadding = true
        )
    }

    fun showGridAssetPicker(
        ctx: Context,
        currentSelectionText: String,
        title: String,
        assetFilter: ((com.taostudio.tapaccounting.data.local.entity.Asset) -> Boolean)? = null,
        allowClear: Boolean = true,
        onConfirm: (String) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_asset_picker, null)
        view.findViewById<TextView>(R.id.tv_asset_picker_title).text = title
        view.findViewById<Button>(R.id.btn_confirm_asset)?.visibility = View.GONE
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_assets)
        var currentSelection = currentSelectionText
        var archivedExpanded = false

        val pickerItems = mutableListOf<AssetPickerItem>()
        var activePickerAssets = emptyList<com.taostudio.tapaccounting.data.local.entity.Asset>()
        var archivedPickerAssets = emptyList<com.taostudio.tapaccounting.data.local.entity.Asset>()

        fun rebuildPickerItems() {
            pickerItems.clear()
            activePickerAssets.forEach { pickerItems.add(AssetPickerItem.AssetItem(it)) }
            if (archivedPickerAssets.isNotEmpty()) {
                pickerItems.add(AssetPickerItem.ArchivedGroup(archivedPickerAssets.size, archivedExpanded))
                if (archivedExpanded) {
                    archivedPickerAssets.forEach { pickerItems.add(AssetPickerItem.AssetItem(it)) }
                }
            }
            // 常驻「不选资产」清空项：始终排在资产列表最后
            if (allowClear) {
                pickerItems.add(AssetPickerItem.ClearItem)
            }
        }

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            init { setHasStableIds(true) }
            inner class AssetVH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)

            override fun getItemId(position: Int): Long {
                return when (val item = pickerItems[position]) {
                    is AssetPickerItem.AssetItem -> item.asset.id
                    is AssetPickerItem.ArchivedGroup -> Long.MIN_VALUE + item.count
                    is AssetPickerItem.ClearItem -> Long.MAX_VALUE
                }
            }

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AssetVH {
                val v = LayoutInflater.from(ctx).inflate(R.layout.item_asset_grid, parent, false)
                return AssetVH(v)
            }

            override fun getItemCount() = pickerItems.size

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val item = pickerItems[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.tv_asset_name)
                val tvType = holder.itemView.findViewById<TextView?>(R.id.tv_asset_type)
                val iv = holder.itemView.findViewById<ImageView>(R.id.iv_asset_icon)

                when (item) {
                    is AssetPickerItem.ArchivedGroup -> {
                        tv.text = if (item.expanded) ctx.getString(R.string.collapse_assets) else ctx.getString(R.string.archived_assets)
                        tvType?.text = ctx.getString(R.string.count_format, item.count)
                        tvType?.visibility = View.VISIBLE
                        tv.setTextColor(Color.parseColor("#6E7D94"))
                        holder.itemView.alpha = 0.92f
                        iv.colorFilter = null
                        iv.setImageResource(R.drawable.ic_backup_folder_action)
                        iv.alpha = 0.72f
                        holder.itemView.setOnClickListener {
                            archivedExpanded = !archivedExpanded
                            rebuildPickerItems()
                            notifyDataSetChanged()
                        }
                    }
                    is AssetPickerItem.ClearItem -> {
                        tv.text = ctx.getString(R.string.clear_asset)
                        tvType?.visibility = View.GONE
                        tv.setTextColor(Color.parseColor("#6E7D94"))
                        holder.itemView.alpha = 1f
                        iv.setImageResource(R.drawable.ic_close_small)
                        iv.alpha = 0.85f
                        iv.colorFilter = android.graphics.PorterDuffColorFilter(
                            Color.parseColor("#90A4AE"),
                            android.graphics.PorterDuff.Mode.SRC_IN
                        )
                        holder.itemView.setOnClickListener {
                            onConfirm("")
                            dialog.dismiss()
                        }
                    }
                    is AssetPickerItem.AssetItem -> {
                        val asset = item.asset
                        tv.text = asset.name
                        tvType?.text = if (asset.isArchived) ctx.getString(R.string.archived) else ""
                        tvType?.visibility = if (asset.isArchived) View.VISIBLE else View.GONE
                        tv.setTextColor(if (asset.name == currentSelection) Color.parseColor("#5C6BC0") else Color.parseColor("#333333"))
                        holder.itemView.alpha = when {
                            asset.name == currentSelection -> 1f
                            asset.isArchived -> 0.72f
                            else -> 0.85f
                        }
                        iv.colorFilter = null
                        iv.alpha = if (asset.isArchived) 0.76f else 1f

                        if (asset.icon.isNotEmpty()) {
                            Glide.with(ctx)
                                .load(asset.icon)
                                .transform(CircleCrop())
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                                .into(iv)
                        } else {
                            iv.setImageResource(R.mipmap.ic_launcher_round)
                        }

                        holder.itemView.setOnClickListener {
                            currentSelection = asset.name
                            onConfirm(currentSelection)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }

        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 5)
        rv.adapter = adapter

        // 长按拖拽排序
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
            ) {
                override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, target: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                    val from = vh.adapterPosition
                    val to = target.adapterPosition
                    if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                        to == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                        from == to
                    ) return false
                    if (pickerItems.getOrNull(from) !is AssetPickerItem.AssetItem ||
                        pickerItems.getOrNull(to) !is AssetPickerItem.AssetItem
                    ) return false
                    val moved = pickerItems.removeAt(from)
                    pickerItems.add(to, moved)
                    adapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

                override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.alpha = 0.7f
                        viewHolder?.itemView?.scaleX = 1.1f
                        viewHolder?.itemView?.scaleY = 1.1f
                    }
                }

                override fun clearView(rv: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                    super.clearView(rv, viewHolder)
                    val item = pickerItems.getOrNull(viewHolder.adapterPosition)
                    val asset = (item as? AssetPickerItem.AssetItem)?.asset
                    viewHolder.itemView.alpha = when {
                        asset?.name == currentSelection -> 1f
                        asset?.isArchived == true -> 0.72f
                        else -> 0.85f
                    }
                    viewHolder.itemView.scaleX = 1f
                    viewHolder.itemView.scaleY = 1f
                    // 拖拽释放后批量保存新的 pickerSortOrder（独立于资产页 sortOrder）
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = AppDatabase.getDatabase(ctx)
                        pickerItems.mapNotNull { (it as? AssetPickerItem.AssetItem)?.asset }
                            .forEachIndexed { idx, asset ->
                            db.assetDao().updatePickerSortOrder(asset.id, idx + 1)
                        }
                    }
                }
            }
        )
        touchHelper.attachToRecyclerView(rv)

        CoroutineScope(Dispatchers.Main).launch {
            val assets = withContext(Dispatchers.IO) { AppDatabase.getDatabase(ctx).assetDao().getAllAssetsListForPicker() }
            val filteredAssets = if (assetFilter == null) assets else assets.filter(assetFilter)
            activePickerAssets = filteredAssets.filterNot { it.isArchived }
            archivedPickerAssets = filteredAssets.filter { it.isArchived }
            rebuildPickerItems()
            adapter.notifyDataSetChanged()
            applyBottomPickerEnterAnimation(dialog, view, rv)
            showStyledDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.9f,
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                y = 150,
                clearDecorPadding = true
            )
        }
    }

    fun showShizukuPrompt(ctx: Context) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val dialog = AlertDialog.Builder(themeContext).setTitle(ctx.getString(R.string.shizuku_auth_required)).setMessage(ctx.getString(R.string.shizuku_auth_message)) .setPositiveButton(ctx.getString(R.string.go_authorize)) { d, _ ->
            d.dismiss()
            try { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Toast.makeText(ctx, ctx.getString(R.string.cannot_launch_shizuku), Toast.LENGTH_SHORT).show() }
        }.setNegativeButton(ctx.getString(R.string.cancel), null).create()
        showStyledDialog(dialog = dialog, ctx = ctx, widthRatio = 0.84f)
    }

    fun showExchangeRateDialog(
        ctx: Context,
        sourceAmount: Double,
        sourceCurrency: String,
        targetCurrency: String,
        initialRate: Double?,
        onConfirm: (Double, Double, Double) -> Unit
    ) {
        fun createDialog(baseCtx: Context): AlertDialog {
            val themeContext = ContextThemeWrapper(baseCtx, R.style.Theme_TapAccounting)
            val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_exchange_rate, null)
            val dialog = AlertDialog.Builder(themeContext).setView(view).setCancelable(false).create()

            val etSource = view.findViewById<EditText>(R.id.et_source_amount)
            val tvSourceCurrency = view.findViewById<TextView>(R.id.tv_source_currency)
            val etRate = view.findViewById<EditText>(R.id.et_exchange_rate)
            val btnRefresh = view.findViewById<View>(R.id.btn_refresh_rate)
            val etTarget = view.findViewById<EditText>(R.id.et_target_amount)
            val tvTargetCurrency = view.findViewById<TextView>(R.id.tv_target_currency)
            val tvFormula = view.findViewById<TextView>(R.id.tv_formula)
            val btnCancel = view.findViewById<View>(R.id.btn_cancel)
            val btnConfirm = view.findViewById<View>(R.id.btn_confirm)

            tvSourceCurrency.text = "(${sourceCurrency})"
            tvTargetCurrency.text = "(${targetCurrency})"
            etSource.setText(String.format("%.2f", sourceAmount))

            var currentRate = initialRate ?: 1.0
            if (initialRate == null) {
                val rateSource = com.taostudio.tapaccounting.logic.CurrencyManager.getRate(sourceCurrency) ?: 1.0
                val rateTarget = com.taostudio.tapaccounting.logic.CurrencyManager.getRate(targetCurrency) ?: 1.0
                if (rateSource != 0.0) currentRate = rateTarget / rateSource
            }
            etRate.setText(String.format("%.6f", currentRate))
            etTarget.setText(String.format("%.2f", sourceAmount * currentRate))

            fun updateFormula() {
                val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                val rVal = etRate.text.toString().toDoubleOrNull() ?: 0.0
                val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
                tvFormula.text = ctx.getString(R.string.currency_conversion, "${String.format("%.2f", sVal)} $sourceCurrency × ${String.format("%.4f", rVal)} = ${String.format("%.2f", tVal)} $targetCurrency")
            }
            updateFormula()

            val textWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (etSource.hasFocus() || etRate.hasFocus()) {
                        val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                        val rVal = etRate.text.toString().toDoubleOrNull() ?: 1.0
                        if (!etTarget.hasFocus()) {
                            etTarget.setText(String.format("%.2f", sVal * rVal))
                        }
                        updateFormula()
                    }
                }
            }
            val targetWatcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (etTarget.hasFocus()) {
                        val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                        val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
                        if (sVal != 0.0 && !etRate.hasFocus()) {
                            etRate.setText(String.format("%.6f", tVal / sVal))
                        }
                        updateFormula()
                    }
                }
            }
            etSource.addTextChangedListener(textWatcher)
            etRate.addTextChangedListener(textWatcher)
            etTarget.addTextChangedListener(targetWatcher)

            btnRefresh.setOnClickListener {
                val rateSource = com.taostudio.tapaccounting.logic.CurrencyManager.getRate(sourceCurrency) ?: 1.0
                val rateTarget = com.taostudio.tapaccounting.logic.CurrencyManager.getRate(targetCurrency) ?: 1.0
                if (rateSource != 0.0) {
                    val newRate = rateTarget / rateSource
                    etRate.setText(String.format("%.6f", newRate))
                    val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                    etTarget.setText(String.format("%.2f", sVal * newRate))
                    updateFormula()
                }
            }

            btnCancel.setOnClickListener { dialog.dismiss() }
            btnConfirm.setOnClickListener {
                val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
                val rVal = etRate.text.toString().toDoubleOrNull() ?: 1.0
                onConfirm(sVal, tVal, rVal)
                dialog.dismiss()
            }
            return dialog
        }

        val preferredCtx = unwrapContext(ctx)
        val useOverlayWindow = shouldUseOverlayWindow(preferredCtx)
        try {
            val dialog = createDialog(preferredCtx)
            if (useOverlayWindow) {
                showOverlayBottomDialog(
                    dialog = dialog,
                    ctx = preferredCtx,
                    widthRatio = 0.9f,
                    y = 150,
                    cancelOnTouchOutside = true,
                    useSolidPanelBackground = true
                )
            } else {
                showPageBottomDialog(
                    dialog = dialog,
                    ctx = preferredCtx,
                    widthRatio = 0.9f,
                    y = 150,
                    cancelOnTouchOutside = true,
                    useSolidPanelBackground = true
                )
            }
        } catch (e: BadTokenException) {
            try {
                val appCtx = preferredCtx.applicationContext
                val recoveryDialog = createDialog(appCtx)
                showOverlayBottomDialog(
                    dialog = recoveryDialog,
                    ctx = appCtx,
                    widthRatio = 0.9f,
                    y = 150,
                    cancelOnTouchOutside = true,
                    useSolidPanelBackground = true
                )
            } catch (ex: Exception) {
                android.util.Log.e("OverlayDialogs", "Failed to show exchange rate dialog even after recovery attempt", ex)
                onConfirm(sourceAmount, sourceAmount, 1.0)
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayDialogs", "Failed to initialize exchange rate dialog", e)
            onConfirm(sourceAmount, sourceAmount, 1.0)
        }
    }

    /**
     * 弹出"选择退款来源账单"对话框。
     * 读取最近的支出账单（非退款），以列表+复选框形式展示，用户选择一条后点击确认回调。
     *
     * @param ctx         上下文（Activity 或 Service）
     * @param onConfirm   用户确认选择后的回调，参数为选中的支出账单
     */
    fun showRefundBillPickerDialog(ctx: Context, onConfirm: (com.taostudio.tapaccounting.data.local.entity.Bill) -> Unit) {
        val themeContext = android.view.ContextThemeWrapper(ctx, com.taostudio.tapaccounting.R.style.Theme_TapAccounting)
        val scope = CoroutineScope(Dispatchers.Main)

        // 加载最近 60 条支出账单（排除已退款完成的账单）
        scope.launch(Dispatchers.IO) {
            val db = com.taostudio.tapaccounting.data.local.AppDatabase.getDatabase(ctx)
            val expenseBills = db.billDao().getRecentExpenseBills(60)

            withContext(Dispatchers.Main) {
                fun normalizedTimeMillis(rawTime: Long): Long {
                    // 兼容历史秒级时间戳，统一转成毫秒再排序/展示，避免列表看起来"乱序"。
                    return if (rawTime in 1..9_999_999_999L) rawTime * 1000L else rawTime
                }
                val sortedRefundCandidates = expenseBills
                    .asSequence()
                    .filter { it.amount > 0.0 } // 已完全退款的支出（amount<=0）不再作为退款来源候选
                    .sortedWith(
                        compareByDescending<com.taostudio.tapaccounting.data.local.entity.Bill> { normalizedTimeMillis(it.time) }
                            .thenByDescending { it.id }
                    )
                    .toList()

                if (sortedRefundCandidates.isEmpty()) {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.no_refundable_bills), android.widget.Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                val dp = ctx.resources.displayMetrics.density

                // —— 外层容器：圆角白卡片 ——
                val container = android.widget.LinearLayout(themeContext).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = androidx.core.content.ContextCompat.getDrawable(
                        themeContext, com.taostudio.tapaccounting.R.drawable.shape_dialog_bg)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
                }

                // —— 标题区 ——
                val tvTitle = android.widget.TextView(themeContext).apply {
                    text = ctx.getString(R.string.refund_source_bill_title)
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(0, (20 * dp).toInt(), 0, (14 * dp).toInt())
                    }
                }
                container.addView(tvTitle)

                // —— 标题下细线 ——
                container.addView(android.view.View(themeContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                })

                // —— 搜索框（带圆角背景） ——
                val searchContainer = android.widget.FrameLayout(themeContext).apply {
                    val dp12 = (12 * dp).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(dp12, dp12, dp12, (4 * dp).toInt())
                    }
                }
                val searchBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F5F5F5"))
                    cornerRadius = (10 * dp)
                }
                val etSearch = android.widget.EditText(themeContext).apply {
                    hint = ctx.getString(R.string.search_category_or_remark)
                    textSize = 13f
                    maxLines = 1
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    background = searchBg
                    val dp10 = (10 * dp).toInt()
                    val dp14 = (14 * dp).toInt()
                    setPadding(dp14, dp10, dp14, dp10)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
                }
                searchContainer.addView(etSearch)
                container.addView(searchContainer)

                // —— 滚动列表（height=1 占满剩余空间） ——
                val scrollView = android.widget.ScrollView(themeContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val listLayout = android.widget.LinearLayout(themeContext).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val dp4 = (4 * dp).toInt()
                    setPadding(0, dp4, 0, dp4)
                }
                scrollView.addView(listLayout)
                container.addView(scrollView)

                // —— 按钮区上细线 ——
                container.addView(android.view.View(themeContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                })

                var selectedBill: com.taostudio.tapaccounting.data.local.entity.Bill? = null

                // —— 构建/刷新列表函数 ——
                fun renderList(filter: String) {
                    listLayout.removeAllViews()
                    val keyword = filter.trim().lowercase()
                    val filtered = if (keyword.isEmpty()) sortedRefundCandidates else sortedRefundCandidates.filter { b ->
                        b.categoryName.lowercase().contains(keyword) ||
                        b.remark.lowercase().contains(keyword)
                    }

                    if (selectedBill != null && filtered.none { it.id == selectedBill!!.id }) {
                        selectedBill = null
                    }

                    if (filtered.isEmpty()) {
                        listLayout.addView(android.widget.TextView(themeContext).apply {
                            text = ctx.getString(R.string.no_matching_bill)
                            textSize = 13f
                            gravity = android.view.Gravity.CENTER
                            setTextColor(android.graphics.Color.parseColor("#BBBBBB"))
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                                it.setMargins(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
                            }
                        })
                        return
                    }

                    val checkBoxes = mutableListOf<android.widget.CheckBox>()

                    filtered.forEachIndexed { index, bill ->
                        val isSelected = (selectedBill?.id == bill.id)

                        // 閫変腑楂樹寒鑳屾櫙
                        val itemBg = if (isSelected)
                            android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#FFF3F3"))
                        else
                            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)

                        val itemLayout = android.widget.LinearLayout(themeContext).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            background = itemBg
                            val dpH  = (14 * dp).toInt()
                            val dpLR = (16 * dp).toInt()
                            setPadding(dpLR, dpH, dpLR, dpH)
                            isClickable = true
                            isFocusable = true
                        }

                        val cb = android.widget.CheckBox(themeContext).apply {
                            id = android.view.View.generateViewId()
                            isChecked = isSelected
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        checkBoxes.add(cb)

                        val textLayout = android.widget.LinearLayout(themeContext).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                                it.marginStart = (10 * dp).toInt()
                            }
                        }

                        val tvCat = android.widget.TextView(themeContext).apply {
                            text = bill.categoryName.ifEmpty { ctx.getString(R.string.uncategorized) }
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
                        }

                        val detailSb = StringBuilder(df.format(java.util.Date(normalizedTimeMillis(bill.time))))
                        if (bill.accountName.isNotEmpty()) detailSb.append("  ·  ${bill.accountName}")
                        if (bill.remark.isNotEmpty())      detailSb.append("  ·  ${bill.remark}")
                        val tvDetail = android.widget.TextView(themeContext).apply {
                            text = detailSb.toString()
                            textSize = 11f
                            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                                it.topMargin = (3 * dp).toInt()
                            }
                        }

                        val tvAmount = android.widget.TextView(themeContext).apply {
                            text = if (bill.currency.equals("CNY", ignoreCase = true)) {
                                "-¥${String.format(java.util.Locale.getDefault(), "%.2f", bill.amount)}"
                            } else {
                                "-${String.format(java.util.Locale.getDefault(), "%.2f", bill.amount)} ${bill.currency}"
                            }
                            textSize = 15f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(android.graphics.Color.parseColor("#E53935"))
                            gravity = android.view.Gravity.END
                        }

                        textLayout.addView(tvCat)
                        textLayout.addView(tvDetail)
                        itemLayout.addView(cb)
                        itemLayout.addView(textLayout)
                        itemLayout.addView(tvAmount)

                        val clickAction = {
                            val wasChecked = cb.isChecked
                            checkBoxes.forEach { it.isChecked = false }
                            cb.isChecked = !wasChecked
                            selectedBill = if (cb.isChecked) bill else null
                            // 刷新高亮
                            renderList(etSearch.text.toString())
                        }
                        itemLayout.setOnClickListener { clickAction() }
                        cb.setOnClickListener {
                            val isNowChecked = cb.isChecked
                            checkBoxes.filter { it != cb }.forEach { it.isChecked = false }
                            selectedBill = if (isNowChecked) bill else null
                            renderList(etSearch.text.toString())
                        }

                        listLayout.addView(itemLayout)

                        // 分隔线（选中行不加，视觉上更整洁）
                        if (index < filtered.size - 1 && !isSelected) {
                            listLayout.addView(android.view.View(themeContext).apply {
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                                    it.marginStart = (56 * dp).toInt()
                                    it.marginEnd   = (16 * dp).toInt()
                                }
                                setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                            })
                        }
                    }
                }

                // 初始渲染全部
                renderList("")

                // 监听搜索框输入
                etSearch.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        renderList(s?.toString() ?: "")
                    }
                })

                // —— 按钮行：固定高度，按钮在上下方向居中 ——
                val btnRow = android.widget.LinearLayout(themeContext).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    val dp16 = (16 * dp).toInt()
                    setPadding(dp16, 0, dp16, 0)
                    // 固定高度，使按钮在分割线与底部之间完全垂直居中
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (64 * dp).toInt())
                }

                try {
                    val alertDialog = androidx.appcompat.app.AlertDialog.Builder(themeContext)
                        .setView(container)
                        .create()

                    // 鍏叡鎸夐挳鑳屾櫙锛氬～鍏呰壊鍦嗚锛屾棤鎻忚竟
                    fun makeBtnBg(fillColor: Int) = android.graphics.drawable.GradientDrawable().apply {
                        setColor(fillColor)
                        cornerRadius = (24 * dp)
                    }

                    val btnCancel = android.widget.Button(themeContext).apply {
                        text = ctx.getString(R.string.cancel)
                        textSize = 14f
                        setTextColor(android.graphics.Color.parseColor("#888888"))
                        background = makeBtnBg(android.graphics.Color.parseColor("#F2F2F2"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, (40 * dp).toInt(), 1f).also {
                            it.marginEnd = (6 * dp).toInt()
                        }
                        setPadding(0, 0, 0, 0)
                        isAllCaps = false
                        setOnClickListener { alertDialog.dismiss() }
                    }
                    val btnConfirm = android.widget.Button(themeContext).apply {
                        text = ctx.getString(R.string.confirm)
                        textSize = 14f
                        setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        background = makeBtnBg(android.graphics.Color.parseColor("#E53935"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, (40 * dp).toInt(), 1f).also {
                            it.marginStart = (6 * dp).toInt()
                        }
                        setPadding(0, 0, 0, 0)
                        isAllCaps = false
                        setOnClickListener {
                            val chosen = selectedBill
                            if (chosen == null) {
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.please_select_bill), android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                alertDialog.dismiss()
                                onConfirm(chosen)
                            }
                        }
                    }
                    btnRow.addView(btnCancel)
                    btnRow.addView(btnConfirm)
                    container.addView(btnRow)

                    val targetHeight = (ctx.resources.displayMetrics.heightPixels * 0.50f).toInt()
                    showStyledDialog(
                        dialog = alertDialog,
                        ctx = ctx,
                        widthRatio = 0.88f,
                        height = targetHeight
                    )
                } catch (e: Exception) {
                    android.util.Log.e("OverlayDialogs", "showRefundBillPickerDialog show failed", e)
                }
            }
        }
    }
}

