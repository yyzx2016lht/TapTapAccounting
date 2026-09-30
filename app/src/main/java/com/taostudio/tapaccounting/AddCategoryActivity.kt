package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.ui.common.StatusBarStyle

class AddCategoryActivity : AppCompatActivity() {

    private var selectedIconUrl: String = ""
    private var userPickedIcon = false
    private var parentName: String? = null
    private var type: Int = Prefs.TYPE_EXPENSE

    private var isEdit = false
    private var oldName: String = ""
    private var editId: Long = 0L
    private var isMultiSelect = false
    private var isSaving = false
    private var lastAutoFilledName: String? = null
    private var selectedCategories: List<BuiltInCategory> = emptyList()

    private lateinit var allIcons: List<BuiltInCategory>
    private lateinit var adapter: BuiltInCategoryAdapter

    private val database by lazy {
        AppDatabase.getDatabase(this)
    }

    private val categoryRepository by lazy {
        CategoryRepository(database.categoryDao(), database.billDao(), database.recurringPatternDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_category)

        parentName = intent.getStringExtra("parentName")
        type = intent.getIntExtra("type", Prefs.TYPE_EXPENSE)

        isEdit = intent.getBooleanExtra("isEdit", false)
        oldName = intent.getStringExtra("oldName") ?: ""
        val oldIcon = intent.getStringExtra("oldIcon") ?: ""
        editId = intent.getLongExtra("editId", 0L)

        val etName = findViewById<EditText>(R.id.et_category_name)
        val etSearch = findViewById<EditText>(R.id.et_search)
        val tvTitle = findViewById<TextView>(R.id.tv_parent_info)
        val ivPreview = findViewById<ImageView>(R.id.iv_preview_icon)
        val rv = findViewById<RecyclerView>(R.id.rv_icon_library)
        val btn = findViewById<Button>(R.id.btn_create_category)
        val btnMultiSelect = findViewById<TextView>(R.id.btn_multi_select)
        val categoryEditor = findViewById<View>(R.id.layout_category_editor)
        val iconSectionTitle = findViewById<TextView>(R.id.tv_icon_section_title)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        val typeStr = if (type == Prefs.TYPE_EXPENSE) getString(R.string.expense) else getString(R.string.income)

        if (isEdit) {
            tvTitle.text = getString(R.string.edit_category_title, typeStr)
            btn.text = getString(R.string.save_changes)
            etName.setText(oldName)
            etName.setSelection(oldName.length)
            selectedIconUrl = oldIcon
            // 首字、三个点、四格都是系统自动给的，改名后要跟着新名称变化
            userPickedIcon = !CategoryIcons.isAutoAssigned(oldIcon)
            if (userPickedIcon) {
                Glide.with(this).load(oldIcon).into(ivPreview)
            } else {
                syncAutoIcon(oldName, ivPreview)
            }
            btnMultiSelect.visibility = View.GONE
        } else {
            tvTitle.text = if (parentName != null) getString(R.string.add_subcategory_title, typeStr) else getString(R.string.add_category_title, typeStr)
        }

        ivPreview.setColorFilter(Color.parseColor("#424242"), PorterDuff.Mode.SRC_IN)

        allIcons = JsonUtils.getBuiltInCategories(this)
        adapter = BuiltInCategoryAdapter(
            items = allIcons,
            onSelect = { selected ->
                val currentText = etName.text.toString()
                if (!isEdit && (currentText.isEmpty() || currentText == lastAutoFilledName)) {
                    etName.setText(selected.name)
                    etName.setSelection(selected.name.length)
                    lastAutoFilledName = selected.name
                }
                if (CategoryIcons.isPlaceholder(selected.icon)) {
                    // 三个点不是可选的真实图标，继续用当前名称的首字
                    userPickedIcon = false
                    syncAutoIcon(etName.text?.toString().orEmpty(), ivPreview)
                } else {
                    selectedIconUrl = selected.icon
                    userPickedIcon = true
                    Glide.with(this).load(selected.icon).into(ivPreview)
                }
            },
            onMultiSelectionChanged = { selected ->
                selectedCategories = selected
                btn.isEnabled = !isMultiSelect || selected.isNotEmpty()
                btn.text = if (selected.isEmpty()) {
                    getString(R.string.add_selected_categories_empty)
                } else {
                    getString(R.string.add_selected_categories, selected.size)
                }
            }
        )
        rv.layoutManager = GridLayoutManager(this, 5)
        rv.adapter = adapter
        if (selectedIconUrl.isNotEmpty()) {
            adapter.setSelectedIcon(selectedIconUrl)
        }

        btnMultiSelect.setOnClickListener {
            if (isSaving) return@setOnClickListener
            isMultiSelect = !isMultiSelect
            adapter.setMultiSelect(isMultiSelect)
            categoryEditor.visibility = if (isMultiSelect) View.GONE else View.VISIBLE
            btnMultiSelect.text = getString(if (isMultiSelect) R.string.cancel else R.string.multi_select)
            iconSectionTitle.text = getString(
                if (isMultiSelect) R.string.select_multiple_categories else R.string.select_icon
            )
            btn.text = getString(
                if (isMultiSelect) R.string.add_selected_categories_empty else R.string.save_category
            )
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s.toString().trim()
                val filtered = if (keyword.isEmpty()) {
                    allIcons
                } else {
                    allIcons.filter { it.name.contains(keyword, ignoreCase = true) }
                }
                adapter.updateList(filtered)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        // 名称即图标：没有挑选图标时，跟随输入实时生成「首字图标」预览
        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (userPickedIcon) return
                if (isMultiSelect) return
                syncAutoIcon(s?.toString().orEmpty(), ivPreview)
            }
        })

        btn.setOnClickListener {
            if (isMultiSelect) {
                saveMultipleCategories(btn, btnMultiSelect)
                return@setOnClickListener
            }

            val newName = etName.text.toString().trim()
            if (newName.isEmpty()) {
                Utils.toast(this, getString(R.string.input_name_icon))
                return@setOnClickListener
            }
            // 未手动选图标时： 「其他」用四格，其余用名称首字
            if (selectedIconUrl.isEmpty()) {
                selectedIconUrl = CategoryIcons.fallback("", newName).ifEmpty {
                    Utils.toast(this, getString(R.string.input_name_icon))
                    return@setOnClickListener
                }
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0
                if (isEdit) {
                    if (editId != 0L) {
                        val existing = categoryRepository.getCategoriesListByType(dbType)
                            .find { it.id == editId }
                        if (existing != null) {
                            categoryRepository.updateCategory(
                                existing.copy(name = newName, iconId = selectedIconUrl)
                            )
                        }
                    }
                } else {
                    val parentId: Long? = if (parentName != null) {
                        categoryRepository.getCategoryByName(parentName!!)?.id
                    } else {
                        null
                    }
                    categoryRepository.addCategory(
                        Category(
                            name = newName,
                            type = dbType,
                            parentId = parentId,
                            iconId = selectedIconUrl
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@AddCategoryActivity, if (isEdit) getString(R.string.edit_success) else getString(R.string.save_success))
                    finish()
                }
            }
        }
    }

    /**
     * 用户没有手动挑选图标时，按当前输入的名称生成「首字图标」并预览。
     * 名称清空则退回原来的占位图。
     */
    private fun syncAutoIcon(name: String, preview: ImageView) {
        val icon = CategoryIcons.fallback("", name)
        if (icon.isEmpty()) {
            selectedIconUrl = ""
            preview.setImageResource(R.drawable.ic_profile_category)
            return
        }
        selectedIconUrl = icon
        Glide.with(this)
            .load(icon)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
            .into(preview)
    }

    private fun saveMultipleCategories(button: Button, multiSelectButton: TextView) {
        if (selectedCategories.isEmpty()) {
            Utils.toast(this, getString(R.string.select_at_least_one_category))
            return
        }

        if (isSaving) return
        isSaving = true
        button.isEnabled = false
        multiSelectButton.isEnabled = false
        adapter.setInteractionEnabled(false)
        val selection = selectedCategories
            .distinctBy { it.name.trim() }
            .filter { it.name.isNotBlank() }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0
                val existing = categoryRepository.getCategoriesListByType(dbType)
                val parentId = parentName?.let { name ->
                    existing.firstOrNull { it.parentId == null && it.name == name }?.id
                }

                if (parentName != null && parentId == null) {
                    withContext(Dispatchers.Main) {
                        Utils.toast(this@AddCategoryActivity, getString(R.string.parent_category_not_found))
                    }
                    return@launch
                }

                val existingNames = existing
                    .asSequence()
                    .filter { it.parentId == parentId }
                    .map { it.name.trim() }
                    .toSet()
                val categoriesToAdd = selection.filter { it.name.trim() !in existingNames }

                database.withTransaction {
                    categoriesToAdd.forEach { selected ->
                        categoryRepository.addCategory(
                            Category(
                                name = selected.name.trim(),
                                type = dbType,
                                parentId = parentId,
                                iconId = CategoryIcons.fallback(selected.icon, selected.name.trim())
                            )
                        )
                    }
                }

                val skippedCount = selection.size - categoriesToAdd.size
                withContext(Dispatchers.Main) {
                    when {
                        categoriesToAdd.isEmpty() ->
                            Utils.toast(this@AddCategoryActivity, getString(R.string.selected_categories_exist))
                        skippedCount > 0 ->
                            Utils.toast(
                                this@AddCategoryActivity,
                                getString(
                                    R.string.categories_added_with_skipped,
                                    categoriesToAdd.size,
                                    skippedCount
                                )
                            )
                        else ->
                            Utils.toast(
                                this@AddCategoryActivity,
                                getString(R.string.categories_added, categoriesToAdd.size)
                            )
                    }
                    if (categoriesToAdd.isNotEmpty()) finish()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("AddCategoryActivity", "Failed to add selected categories", error)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@AddCategoryActivity, getString(R.string.categories_add_failed))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        isSaving = false
                        button.isEnabled = true
                        multiSelectButton.isEnabled = true
                        adapter.setInteractionEnabled(true)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StatusBarStyle.applyByColor(window, Color.WHITE)
    }
}

