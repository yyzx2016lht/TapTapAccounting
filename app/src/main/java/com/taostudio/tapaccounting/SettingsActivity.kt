package com.taostudio.tapaccounting

import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class SettingsActivity : AppCompatActivity() {
    private data class OptionItem(
        val title: String,
        val desc: String,
        val highRisk: Boolean = false,
        val onClick: () -> Unit
    )

    private lateinit var container: LinearLayout
    private lateinit var scrollNormalContent: View
    private lateinit var layoutSortActions: View
    private lateinit var rvSortCategories: RecyclerView
    private var expandedParentName: String? = null
    private var isSortMode = false
    private var activeSortParentId: Long? = null
    private val sortCategories = mutableListOf<Category>()
    private lateinit var sortAdapter: RecyclerView.Adapter<*>

    // 默认为支出 (0)
    private var currentType = Prefs.TYPE_EXPENSE

    private val categoryRepository by lazy {
        val db = AppDatabase.getDatabase(this)
        CategoryRepository(db.categoryDao(), db.billDao(), db.recurringPatternDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        container = findViewById(R.id.container_main)
        scrollNormalContent = findViewById(R.id.scroll_normal_content)
        layoutSortActions = findViewById(R.id.layout_sort_actions)
        rvSortCategories = findViewById(R.id.rv_sort_categories)
        findViewById<View>(R.id.btn_back).setOnClickListener {
            if (isSortMode) {
                exitSortMode(resetData = true)
            } else {
                finish()
            }
        }

    // 右上角添加按钮
        findViewById<View>(R.id.btn_header_action_text).setOnClickListener {
            val intent = Intent(this@SettingsActivity, AddCategoryActivity::class.java)
            intent.putExtra("type", currentType)
            startActivity(intent)
        }

    // 处理顶部 Tab 切换
        val rgType = findViewById<RadioGroup>(R.id.rg_type)
        rgType.setOnCheckedChangeListener { _, checkedId ->
            currentType = if (checkedId == R.id.rb_income) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
            // 切换 Tab 时，收起展开项并刷新
            expandedParentName = null
            if (isSortMode) exitSortMode(resetData = true)
            renderUI()
        }

        setupSortRecycler()
    }

    override fun onResume() {
        super.onResume()
        StatusBarStyle.applyByColor(window, Color.WHITE)
        if (isSortMode) enterSortMode(activeSortParentId) else renderUI()
    }

    private fun renderUI() {
        if (isSortMode) return
        lifecycleScope.launch {
            val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
            val allCats = withContext(Dispatchers.IO) {
                categoryRepository.getCategoryTree(dbType)
            }
            renderCategoryList(allCats)
        }
    }

    private fun setupSortRecycler() {
        rvSortCategories.layoutManager = GridLayoutManager(this, 4)
        sortAdapter = object : RecyclerView.Adapter<SortCategoryVH>() {
            init { setHasStableIds(true) }

            override fun getItemId(position: Int): Long = sortCategories[position].id

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SortCategoryVH {
                val view = LayoutInflater.from(this@SettingsActivity)
                    .inflate(R.layout.item_category_grid, parent, false)
                return SortCategoryVH(view)
            }

            override fun getItemCount(): Int = sortCategories.size

            override fun onBindViewHolder(holder: SortCategoryVH, position: Int) {
                val item = sortCategories[position]
                holder.name.text = if (item.parentId != null) "· ${item.name}" else item.name
                holder.name.textSize = if (item.parentId != null) 10.5f else 11f
                holder.name.setTextColor(if (item.parentId != null) Color.parseColor("#666666") else Color.parseColor("#333333"))
                holder.iconContainer.scaleX = 0.88f
                holder.iconContainer.scaleY = 0.88f
                holder.itemView.alpha = 0.96f
                loadSafeCategoryIcon(CategoryIcons.fallback(item.iconId, item.name), holder.icon)
                holder.icon.setColorFilter(Color.parseColor("#4F5D75"))
            }
        }
        rvSortCategories.adapter = sortAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
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
                val moved = sortCategories.removeAt(from)
                sortCategories.add(to, moved)
                sortAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(0.94f)?.scaleY(0.94f)?.alpha(0.72f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).alpha(0.96f).setDuration(120).start()
            }
        }).attachToRecyclerView(rvSortCategories)

        findViewById<View>(R.id.btn_cancel_sort).setOnClickListener {
            exitSortMode(resetData = true)
        }
        findViewById<View>(R.id.btn_save_sort).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                categoryRepository.saveOrderedCategories(sortCategories.toList())
                withContext(Dispatchers.Main) {
                    exitSortMode(resetData = false)
                    renderUI()
                }
            }
        }
    }

    private fun loadSafeCategoryIcon(icon: String, imageView: ImageView) {
        if (icon.isBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        val file = File(icon)
        if (file.exists()) {
            Glide.with(this)
                .load(file)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(imageView)
        } else {
            Glide.with(this)
                .load(icon)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(imageView)
        }
    }

    private fun enterSortMode(sortParentId: Long?) {
        isSortMode = true
        activeSortParentId = sortParentId
        scrollNormalContent.visibility = View.GONE
        layoutSortActions.visibility = View.VISIBLE
        rvSortCategories.visibility = View.VISIBLE
        findViewById<View>(R.id.btn_header_action_text).visibility = View.GONE

        lifecycleScope.launch {
            val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
            val all = withContext(Dispatchers.IO) { categoryRepository.getCategoriesListByType(dbType) }
            sortCategories.clear()
            if (sortParentId == null) {
                sortCategories += all.filter { it.parentId == null }
                findViewById<TextView>(R.id.tv_sort_tip)?.text = "拖动一级分类进行排序"
            } else {
                sortCategories += all.filter { it.parentId == sortParentId }
                findViewById<TextView>(R.id.tv_sort_tip)?.text = "拖动子分类进行排序"
            }
            sortAdapter.notifyDataSetChanged()
        }
    }

    private fun exitSortMode(resetData: Boolean) {
        isSortMode = false
        activeSortParentId = null
        layoutSortActions.visibility = View.GONE
        rvSortCategories.visibility = View.GONE
        scrollNormalContent.visibility = View.VISIBLE
        findViewById<View>(R.id.btn_header_action_text).visibility = View.VISIBLE
        if (resetData) sortCategories.clear()
    }

    private class SortCategoryVH(v: View) : RecyclerView.ViewHolder(v) {
        val iconContainer: View = v.findViewById(R.id.layout_category_icon_container)
        val icon: ImageView = v.findViewById(R.id.iv_category_icon)
        val name: TextView = v.findViewById(R.id.tv_category_name)
    }

    private fun renderCategoryList(allCats: List<CategoryNode>) {
        container.removeAllViews()
        val spanCount = 4

        allCats.chunked(spanCount).forEach { row ->
            val rowLayout = createRowLayout(spanCount)

            row.forEach { cat ->
                val isSelected = (expandedParentName == cat.name)
                val itemView = createView(cat, isSub = false, isSelected = isSelected)

                itemView.setOnClickListener {
                    expandedParentName = if (expandedParentName == cat.name) null else cat.name
                    renderCategoryList(allCats)
                }
                // 长按一级分类（parent = null）
                itemView.setOnLongClickListener {
                    showActionMenu(cat, null, allCats)
                    true
                }
                rowLayout.addView(itemView)
            }
            fillPlaceholder(rowLayout, spanCount - row.size)
            container.addView(rowLayout)

            val matchedParent = row.find { it.name == expandedParentName }
            if (matchedParent != null) {
                val anchorIndex = row.indexOfFirst { it.name == matchedParent.name }.coerceAtLeast(0)
                container.addView(createSubPanel(matchedParent, anchorIndex, allCats))
            }
        }
    }

    private fun createSubPanel(parent: CategoryNode, anchorIndexInRow: Int, allCats: List<CategoryNode>): View {
        val wrapper = FrameLayout(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_rule_page_section)
            setPadding(18, 18, 18, 18)
        }
        val density = resources.displayMetrics.density
        val spanCount = 4
        val dialogContentWidth = resources.displayMetrics.widthPixels - (32 * density).toInt()
        val cellWidth = dialogContentWidth / spanCount.toFloat()
        val pointerWidth = (20 * density).toInt()
        val pointerHeight = (10 * density).toInt()
        val panelTopMargin = pointerHeight - (1 * density).toInt()
        val pointerLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (pointerWidth / 2f)).toInt().coerceAtLeast(0)
        val borderCutWidth = (22 * density).toInt()
        val borderCutHeight = (2 * density).toInt().coerceAtLeast(1)
        val borderCutLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (borderCutWidth / 2f)).toInt().coerceAtLeast(0)
        val allItems = parent.subs.toMutableList()
        val totalItems = allItems + listOf(null) // null 代表添加按钮

        totalItems.chunked(spanCount).forEach { rowItems ->
            val rowLayout = createRowLayout(spanCount)

            rowItems.forEach { item ->
                if (item != null) {
                    val itemView = createView(item, isSub = true)
                    // 长按二级分类（parent = parent）
                    itemView.setOnLongClickListener {
                        showActionMenu(item, parent, allCats)
                        true
                    }
                    rowLayout.addView(itemView)
                } else {
                    val addView = createAddSubButton(parent)
                    rowLayout.addView(addView)
                }
            }
            fillPlaceholder(rowLayout, spanCount - rowItems.size)
            panel.addView(rowLayout)
        }
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
            View(this).apply {
                setBackgroundColor(Color.parseColor("#F9FBFF"))
            },
            FrameLayout.LayoutParams(borderCutWidth, borderCutHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = borderCutLeft
                topMargin = panelTopMargin
            }
        )
        wrapper.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.bg_category_subpanel_pointer)
            },
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
                setMargins(6, 2, 6, 10)
            }
        }
    }

    private fun createRowLayout(weightSum: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            this.weightSum = weightSum.toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun fillPlaceholder(layout: LinearLayout, count: Int) {
        for (i in 0 until count) {
            layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
    }

    private fun createView(cat: CategoryNode, isSub: Boolean, isSelected: Boolean = false): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_category_grid, null)
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        view.setBackgroundResource(if (isSelected) R.drawable.bg_category_manage_item_selected else R.drawable.bg_category_manage_item)

        val nameView = view.findViewById<TextView>(R.id.tv_category_name)
        val iconContainer = view.findViewById<View>(R.id.layout_category_icon_container)
        nameView.text = cat.name
        val iv = view.findViewById<ImageView>(R.id.iv_category_icon)
        if (isSelected) {
            iv.setColorFilter(Color.parseColor("#2196F3"))
            nameView.setTextColor(Color.parseColor("#2196F3"))
            iconContainer.alpha = 1f
        } else {
            iv.setColorFilter(if (isSub) Color.parseColor("#667085") else Color.parseColor("#4F5D75"))
            nameView.setTextColor(Color.parseColor("#334155"))
            iconContainer.alpha = 1f
        }
        nameView.textSize = if (isSub) 12f else 13f
        if (cat.icon.isNotEmpty()) loadSafeCategoryIcon(cat.icon, iv)
        return view
    }

    private fun createAddSubButton(parent: CategoryNode): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_category_grid, null)
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        view.setBackgroundResource(R.drawable.bg_category_manage_item)
        view.findViewById<TextView>(R.id.tv_category_name).text = getString(R.string.add_subcategory)
        val iv = view.findViewById<ImageView>(R.id.iv_category_icon)
        iv.setImageResource(android.R.drawable.ic_menu_add)
        iv.setColorFilter(Color.parseColor("#2C74FF"), PorterDuff.Mode.SRC_IN)
        view.findViewById<TextView>(R.id.tv_category_name).apply {
            setTextColor(Color.parseColor("#2C74FF"))
            textSize = 13f
        }

        view.setOnClickListener {
            val intent = Intent(this, AddCategoryActivity::class.java)
            intent.putExtra("type", currentType)
            intent.putExtra("parentName", parent.name)
            startActivity(intent)
        }
        return view
    }

    // --- 核心新功能：显示操作菜单 ---
    private fun showActionMenu(target: CategoryNode, parent: CategoryNode?, allCats: List<CategoryNode>) {
        val isSubCategory = parent != null
        val options = buildList<OptionItem> {
            add(
                OptionItem(
                    title = getString(R.string.modify_category),
                    desc = getString(R.string.edit_category_desc)
                ) {
                    val intent = Intent(this@SettingsActivity, AddCategoryActivity::class.java)
                    intent.putExtra("type", currentType)
                    intent.putExtra("parentName", parent?.name)
                    intent.putExtra("isEdit", true)
                    intent.putExtra("oldName", target.name)
                    intent.putExtra("oldIcon", target.icon)
                    intent.putExtra("editId", target.id)
                    startActivity(intent)
                }
            )
            add(
                OptionItem(
                    title = getString(R.string.delete_category),
                    desc = getString(R.string.delete_category_desc),
                    highRisk = true
                ) {
                    handleDeleteCategory(target, parent)
                }
            )
            add(
                OptionItem(
                    title = getString(R.string.sort_category),
                    desc = if (isSubCategory) getString(R.string.enter_sub_sort_mode) else getString(R.string.enter_parent_sort_mode)
                ) {
                    enterSortMode(if (isSubCategory) parent?.id else null)
                }
            )
            if (isSubCategory) {
                add(
                    OptionItem(
                        title = getString(R.string.promote_to_parent),
                        desc = getString(R.string.promote_to_parent_desc)
                    ) {
                        showPromoteConfirm(target)
                    }
                )
            } else if (target.subs.isEmpty()) {
                add(
                    OptionItem(
                        title = getString(R.string.demote_to_child),
                        desc = getString(R.string.demote_to_child_desc)
                    ) {
                        showDemoteDialog(target, allCats)
                    }
                )
            }
        }

        showOptionDialog(
            title = getString(R.string.operate_category_fmt, target.name),
            desc = getString(R.string.select_operation),
            options = options
        )
    }

    /** 处理删除分类逻辑 */
    private fun handleDeleteCategory(target: CategoryNode, parent: CategoryNode?) {
    // 1. 一级分类有子分类时，禁止删除
        if (parent == null && target.subs.isNotEmpty()) {
            showCustomConfirmDialog(
                title = getString(R.string.cannot_delete),
                message = getString(R.string.cannot_delete_has_sub_fmt, target.name, target.subs.size),
                confirmText = getString(R.string.got_it),
                onConfirm = {}
            )
            return
        }

    // 2. 查询该分类下的账单数量，再弹出处理方式选择
        lifecycleScope.launch {
            val loading = showLoadingDialog(getString(R.string.counting_related_bills))
            val billCount = withContext(Dispatchers.IO) {
                categoryRepository.countBillsUnderCategory(target.id)
            }
            loading.dismiss()
            showDeleteWithBillHandlingDialog(target, billCount)
        }
    }

    /** 弹出“删除分类并处理账单”的对话框 */
    private fun showDeleteWithBillHandlingDialog(target: CategoryNode, billCount: Int) {
        val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
        if (billCount <= 0) {
            showFinalDeleteConfirm(target, getString(R.string.no_bills_direct_delete)) {
                lifecycleScope.launch(Dispatchers.IO) {
                    categoryRepository.deleteCategoryAndMigrateBills(target.id, null)
                    withContext(Dispatchers.Main) {
                        if (expandedParentName == target.name) expandedParentName = null
                        renderUI()
                    }
                }
            }
            return
        }

        showOptionDialog(
            title = getString(R.string.bill_handling_title),
            desc = getString(R.string.bill_handling_desc_fmt, target.name, billCount),
            options = listOf(
                OptionItem(
                    title = getString(R.string.migrate_to_new_category),
                    desc = getString(R.string.migrate_to_new_category_desc)
                ) {
                    showSelectTargetCategoryDialog(target, dbType)
                },
                OptionItem(
                    title = getString(R.string.delete_with_bills),
                    desc = getString(R.string.delete_with_bills_desc),
                    highRisk = true
                ) {
                    showFinalDeleteConfirm(target, getString(R.string.delete_with_bills)) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            categoryRepository.deleteCategoryAndBills(target.id, AppDatabase.getDatabase(this@SettingsActivity))
                            withContext(Dispatchers.Main) {
                                if (expandedParentName == target.name) expandedParentName = null
                                renderUI()
                            }
                        }
                    }
                }
            )
        )
    }

    /** 选择迁移目标分类 */
    private fun showSelectTargetCategoryDialog(target: CategoryNode, dbType: Int) {
        lifecycleScope.launch {
            val allCats = withContext(Dispatchers.IO) {
                categoryRepository.getCategoriesListByType(dbType)
            }
            // 过滤掉自身及其子分类
            val candidates = allCats.filter { it.id != target.id && it.parentId != target.id }

            if (candidates.isEmpty()) {
                Utils.toast(this@SettingsActivity, getString(R.string.no_migration_target))
                lifecycleScope.launch(Dispatchers.IO) {
                    categoryRepository.deleteCategoryAndMigrateBills(target.id, null)
                    withContext(Dispatchers.Main) {
                        if (expandedParentName == target.name) expandedParentName = null
                        renderUI()
                    }
                }
                return@launch
            }

            // 需要隐藏的 id 集合：目标分类自身 + 其所有子分类
            val excludeIds = allCats
                .filter { it.id == target.id || it.parentId == target.id }
                .map { it.id }
                .toSet()

            com.taostudio.tapaccounting.ui.dialog.OverlayDialogs.showMigrationTargetPicker(
                ctx = this@SettingsActivity,
                excludeIds = excludeIds,
                title = getString(R.string.select_migration_target_title),
                dbType = dbType
            ) { targetCat ->
                showFinalDeleteConfirm(target, getString(R.string.migrate_to_target_fmt, targetCat.name)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        categoryRepository.deleteCategoryAndMigrateBills(target.id, targetCat.id)
                        withContext(Dispatchers.Main) {
                            if (expandedParentName == target.name) expandedParentName = null
                            renderUI()
                        }
                    }
                }
            }
        }
    }

    /** 最终确认删除 */
    private fun showFinalDeleteConfirm(target: CategoryNode, handling: String, onConfirm: () -> Unit) {
        lifecycleScope.launch {
            val loading = showLoadingDialog(getString(R.string.loading_related_bills))
            val relatedBills = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(this@SettingsActivity).billDao()
                val byId = dao.getBillsByCategoryIdList(target.id)
                val byName = dao.getBillsByCategoryNameList(target.name)
                (byId + byName)
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<Bill> { normalizedTimeMillis(it.time) }
                            .thenByDescending { it.id }
                    )
            }
            loading.dismiss()
            showCategoryDeleteConfirmDialog(target, handling, relatedBills, onConfirm)
        }
    }

    private fun showLoadingDialog(message: String): AlertDialog {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackgroundResource(R.drawable.bg_delete_dialog_panel)
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val text = TextView(this).apply {
            this.text = message
            setTextColor(Color.parseColor("#374151"))
            textSize = 14f
            setPadding(dp(12), 0, 0, 0)
        }
        panel.addView(progress)
        panel.addView(text)

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setCancelable(false)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.72f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
        return dialog
    }

    private fun showCategoryDeleteConfirmDialog(
        target: CategoryNode,
        handling: String,
        relatedBills: List<Bill>,
        onConfirm: () -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = getString(R.string.confirm_delete_category_fmt, target.name)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = getString(R.string.handling_method_fmt, handling)

        if (relatedBills.isNotEmpty()) {
            val root = panel as LinearLayout
            val actionRow = root.getChildAt(root.childCount - 1)
            root.removeView(actionRow)

            val previewTitle = TextView(this).apply {
                text = getString(R.string.related_bills_count_fmt, relatedBills.size)
                setTextColor(Color.parseColor("#374151"))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(8))
            }

            val listWrap = ScrollView(this).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setBackgroundResource(R.drawable.bg_delete_dialog_cancel_btn)
                val rowHeight = dp(56)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight * 3
                )
            }
            val listContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            var loadedCount = 0
            var appending = false
            val batchSize = 20
            fun appendNextBatch() {
                if (appending || loadedCount >= relatedBills.size) return
                appending = true
                val endExclusive = min(loadedCount + batchSize, relatedBills.size)
                for (index in loadedCount until endExclusive) {
                    val bill = relatedBills[index]
                    val row = LayoutInflater.from(this).inflate(R.layout.item_delete_bill_preview, listContainer, false)
                    val icon = row.findViewById<ImageView>(R.id.iv_delete_bill_icon)
                    val title = row.findViewById<TextView>(R.id.tv_delete_bill_title)
                    val subtitle = row.findViewById<TextView>(R.id.tv_delete_bill_subtitle)
                    val amount = row.findViewById<TextView>(R.id.tv_delete_bill_amount)
                    val time = row.findViewById<TextView>(R.id.tv_delete_bill_time)

                    title.text = bill.remark.ifBlank { bill.categoryName.ifBlank { getString(R.string.uncategorized) } }
                    subtitle.text = bill.categoryName.ifBlank { getString(R.string.uncategorized) }
                    val amountPrefix = when (bill.type) {
                        Bill.TYPE_INCOME -> "+"
                        Bill.TYPE_TRANSFER -> ""
                        else -> "-"
                    }
                    amount.text = "$amountPrefix${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
                    amount.setTextColor(
                        when (bill.type) {
                            Bill.TYPE_INCOME -> BillColors.income
                            Bill.TYPE_TRANSFER -> Color.parseColor("#5B6B80")
                            else -> BillColors.expense
                        }
                    )
                    time.text = df.format(Date(normalizedTimeMillis(bill.time)))
                    when (bill.type) {
                        Bill.TYPE_TRANSFER -> icon.setImageResource(R.drawable.ic_transfer)
                        Bill.TYPE_INCOME -> icon.setImageResource(R.drawable.ic_trend_up)
                        else -> icon.setImageResource(R.drawable.ic_trend_down)
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        val iconUrl = runCatching {
                            CategoryIconHelper.findCategoryIcon(this@SettingsActivity, bill.categoryName, bill.type)
                        }.getOrDefault("")
                        if (iconUrl.isBlank()) return@launch
                        withContext(Dispatchers.Main) {
                            Glide.with(this@SettingsActivity)
                                .load(iconUrl)
                                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                .into(icon)
                        }
                    }
                    listContainer.addView(row)
                }
                loadedCount = endExclusive
                appending = false
            }
            appendNextBatch()
            listWrap.viewTreeObserver.addOnScrollChangedListener {
                val child = listWrap.getChildAt(0) ?: return@addOnScrollChangedListener
                val reachBottom = listWrap.scrollY + listWrap.height >= child.height - dp(24)
                if (reachBottom) appendNextBatch()
            }
            listWrap.addView(listContainer)
            root.addView(previewTitle, root.childCount)
            root.addView(listWrap, root.childCount)
            root.addView(actionRow)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = getString(R.string.confirm_delete_btn)
            setBackgroundResource(R.drawable.bg_delete_followup_danger_btn)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    /** 确认将二级分类提升为一级 */
    private fun showPromoteConfirm(target: CategoryNode) {
        showCustomConfirmDialog(
            title = getString(R.string.promote_to_parent),
            message = getString(R.string.promote_confirm_fmt, target.name),
            confirmText = getString(R.string.confirm_action),
            onConfirm = {
                lifecycleScope.launch(Dispatchers.IO) {
                    categoryRepository.promoteToParent(target.id)
                    withContext(Dispatchers.Main) {
                        renderUI()
                    }
                }
            }
        )
    }

    /** 选择目标父分类，将一级分类降级为子分类 */
    private fun showDemoteDialog(target: CategoryNode, allCats: List<CategoryNode>) {
        val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
    // 候选父分类：其他一级分类（不能是自己）
        val candidates = allCats.filter { it.id != target.id }
        if (candidates.isEmpty()) {
            Utils.toast(this, getString(R.string.no_other_parent_category))
            return
        }
        showOptionDialog(
            title = getString(R.string.select_parent_category),
            desc = getString(R.string.select_parent_category_desc, target.name),
            options = candidates.map { newParent ->
                OptionItem(
                    title = getString(R.string.adjust_to_subcategory, newParent.name),
                    desc = getString(R.string.adjust_to_subcategory_desc)
                ) {
                    showCustomConfirmDialog(
                        title = getString(R.string.adjust_to_subcategory_confirm),
                        message = getString(R.string.adjust_to_subcategory_confirm_fmt, target.name, newParent.name),
                        confirmText = getString(R.string.confirm_action)
                    ) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            categoryRepository.demoteToChild(target.id, newParent.id)
                            withContext(Dispatchers.Main) {
                                expandedParentName = newParent.name
                                renderUI()
                            }
                        }
                    }
                }
            }
        )
    }

    private fun showOptionDialog(
        title: String,
        desc: String,
        options: List<OptionItem>
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_option_picker, null, false)
        panel.findViewById<TextView>(R.id.tv_option_picker_title).text = title
        panel.findViewById<TextView>(R.id.tv_option_picker_desc).apply {
            text = desc
            visibility = View.VISIBLE
        }
        val listView = panel.findViewById<ListView>(R.id.lv_option_picker)
        val adapter = OptionActionAdapter(options)
        listView.adapter = adapter
        listView.divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#12000000"))
        listView.dividerHeight = 1
        val maxHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val estimatedItemHeight = (74 * resources.displayMetrics.density).toInt()
        val estimatedContentHeight = (options.size * estimatedItemHeight).coerceAtLeast(dp(1))
        listView.layoutParams = listView.layoutParams.apply {
            height = min(maxHeight, estimatedContentHeight)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in options.indices) {
                dialog.dismiss()
                options[position].onClick()
            }
        }
        panel.findViewById<TextView>(R.id.btn_option_picker_cancel).setOnClickListener { dialog.dismiss() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun showCustomConfirmDialog(
        title: String,
        message: String,
        confirmText: String = getString(R.string.confirm_action),
        isDanger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message
        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = confirmText
            setBackgroundResource(
                if (isDanger) R.drawable.bg_delete_followup_danger_btn
                else R.drawable.bg_delete_followup_primary_btn
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private inner class OptionActionAdapter(
        private val options: List<OptionItem>
    ) : BaseAdapter() {
        override fun getCount(): Int = options.size

        override fun getItem(position: Int): OptionItem = options[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val context = parent?.context ?: this@SettingsActivity
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_dialog_option_picker, parent, false)
            val titleView = view.findViewById<TextView>(R.id.tv_option_title)
            val subtitleView = view.findViewById<TextView>(R.id.tv_option_subtitle)
            val checkView = view.findViewById<TextView>(R.id.tv_option_check)
            val riskView = view.findViewById<TextView>(R.id.tv_option_risk)

            val item = getItem(position)
            titleView.text = item.title
            titleView.setTextColor(if (item.highRisk) Color.parseColor("#B42318") else Color.parseColor("#1F2A38"))
            subtitleView.text = item.desc
            subtitleView.visibility = if (item.desc.isBlank()) View.GONE else View.VISIBLE
            checkView.visibility = View.GONE
            riskView.visibility = if (item.highRisk) View.VISIBLE else View.GONE
            view.setBackgroundColor(Color.TRANSPARENT)
            return view
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun normalizedTimeMillis(rawTime: Long): Long {
        return if (rawTime in 1..9_999_999_999L) rawTime * 1000L else rawTime
    }

    override fun onBackPressed() {
        if (isSortMode) {
            exitSortMode(resetData = true)
        } else {
            super.onBackPressed()
        }
    }
}

