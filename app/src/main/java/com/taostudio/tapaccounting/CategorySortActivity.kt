package com.taostudio.tapaccounting

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.ui.widget.SecondaryPageHeaderView
import java.io.File

class CategorySortActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_PARENT_ID = "parent_id"
        private const val EXTRA_PARENT_NAME = "parent_name"

        fun createIntent(
            ctx: Context,
            type: Int,
            parentId: Long? = null,
            parentName: String? = null
        ): Intent {
            return Intent(ctx, CategorySortActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                if (parentId != null) putExtra(EXTRA_PARENT_ID, parentId)
                if (parentName != null) putExtra(EXTRA_PARENT_NAME, parentName)
            }
        }
    }

    private lateinit var rgType: RadioGroup
    private lateinit var secondaryHeader: SecondaryPageHeaderView
    private lateinit var rvCategories: RecyclerView
    private lateinit var btnSave: TextView
    private lateinit var btnCancel: TextView

    private val repo by lazy { CategoryRepository(AppDatabase.getDatabase(this).categoryDao()) }
    private val items = mutableListOf<Category>()
    private val childCountByParentId = mutableMapOf<Long, Int>()
    private var currentType = Prefs.TYPE_EXPENSE
    private var parentId: Long? = null
    private var parentName: String? = null

    private val adapter = object : RecyclerView.Adapter<SortCardVH>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = items[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SortCardVH {
            val view = LayoutInflater.from(this@CategorySortActivity)
                .inflate(R.layout.item_category_sort_card, parent, false)
            return SortCardVH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: SortCardVH, position: Int) {
            val item = items[position]
            holder.title.text = item.name
            loadSafeCategoryIcon(CategoryIcons.fallback(item.iconId, item.name), holder.icon)

            if (isParentMode()) {
                val childCount = childCountByParentId[item.id] ?: 0
                holder.subtitle.visibility = View.VISIBLE
                holder.subtitle.text = if (childCount > 0) {
                    getString(R.string.child_category_count_fmt, childCount)
                } else {
                    getString(R.string.no_child_category)
                }
                holder.arrow.visibility = View.VISIBLE
                holder.dragHint.visibility = View.GONE
                holder.itemView.setOnClickListener {
                    startActivity(
                        createIntent(
                            this@CategorySortActivity,
                            currentType,
                            parentId = item.id,
                            parentName = item.name
                        )
                    )
                }
            } else {
                holder.subtitle.visibility = View.VISIBLE
                holder.subtitle.text = getString(R.string.long_press_sort_hint)
                holder.arrow.visibility = View.GONE
                holder.dragHint.visibility = View.VISIBLE
                holder.itemView.setOnClickListener(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_sort)

        currentType = intent.getIntExtra(EXTRA_TYPE, Prefs.TYPE_EXPENSE)
        parentId = intent.extras?.let {
            if (it.containsKey(EXTRA_PARENT_ID)) it.getLong(EXTRA_PARENT_ID) else null
        }
        parentName = intent.getStringExtra(EXTRA_PARENT_NAME)

        secondaryHeader = findViewById(R.id.secondary_header)
        rgType = findViewById(R.id.rg_sort_type)
        rvCategories = findViewById(R.id.rv_sort_categories)
        btnSave = findViewById(R.id.btn_save_sort_page)
        btnCancel = findViewById(R.id.btn_cancel_sort_page)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnCancel.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveSort() }

        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false
                val moved = items.removeAt(from)
                items.add(to, moved)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()
                        ?.scaleX(0.97f)
                        ?.scaleY(0.97f)
                        ?.alpha(0.82f)
                        ?.setDuration(120)
                        ?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(120)
                    .start()
            }
        }).attachToRecyclerView(rvCategories)

        if (isParentMode()) {
            rgType.visibility = View.VISIBLE
            rgType.check(if (currentType == Prefs.TYPE_INCOME) R.id.rb_sort_income else R.id.rb_sort_expense)
            rgType.setOnCheckedChangeListener { _, checkedId ->
                currentType = if (checkedId == R.id.rb_sort_income) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
                loadData()
            }
            secondaryHeader.setTitle(getString(R.string.sort_category))
            secondaryHeader.setSubtitle(getString(R.string.category_sort_hint))
        } else {
            rgType.visibility = View.GONE
            secondaryHeader.setTitle(parentName ?: getString(R.string.sort_subcategory))
            secondaryHeader.setSubtitle(getString(R.string.category_sub_sort_hint))
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        StatusBarStyle.applyByColor(window, Color.WHITE)
    }

    private fun isParentMode(): Boolean = parentId == null

    private fun loadData() {
        lifecycleScope.launch {
            val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
            val allOfType = withContext(Dispatchers.IO) { repo.getCategoriesListByType(dbType) }
            val data = if (parentId == null) {
                allOfType.filter { it.parentId == null }
            } else {
                allOfType.filter { it.parentId == parentId }
            }
            childCountByParentId.clear()
            allOfType.filter { it.parentId != null }.forEach { child ->
                val key = child.parentId ?: return@forEach
                childCountByParentId[key] = (childCountByParentId[key] ?: 0) + 1
            }
            items.clear()
            items.addAll(data)
            adapter.notifyDataSetChanged()
        }
    }

    private fun saveSort() {
        lifecycleScope.launch(Dispatchers.IO) {
            repo.saveOrderedCategories(items.toList())
            withContext(Dispatchers.Main) {
                Utils.toast(this@CategorySortActivity, getString(R.string.sort_saved))
                finish()
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

    private class SortCardVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_sort_icon)
        val title: TextView = view.findViewById(R.id.tv_sort_name)
        val subtitle: TextView = view.findViewById(R.id.tv_sort_subtitle)
        val arrow: ImageView = view.findViewById(R.id.iv_sort_arrow)
        val dragHint: TextView = view.findViewById(R.id.tv_sort_drag)
    }
}

