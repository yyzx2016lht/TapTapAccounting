package com.taostudio.tapaccounting

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StorageCleanupActivity : AppCompatActivity() {

    private lateinit var switchOlder30: MaterialSwitch
    private lateinit var tvTotalSize: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var layoutCategories: LinearLayout
    private lateinit var layoutCleanupBar: View
    private lateinit var tvBarTitle: TextView
    private lateinit var tvBarSubtitle: TextView

    private var latestGroups: Map<StorageCleanupCategory, StorageCleanupGroup> = emptyMap()
    private val selectedCategories = linkedSetOf(
        StorageCleanupCategory.VOICE,
        StorageCleanupCategory.IMAGES,
        StorageCleanupCategory.SCREEN_CAPTURES,
        StorageCleanupCategory.TEMP_CACHE
    )
    private val categoryRows = linkedMapOf<StorageCleanupCategory, CategoryRowViews>()

    private data class CategoryRowViews(
        val card: MaterialCardView,
        val checkbox: MaterialCheckBox,
        val icon: ImageView,
        val title: TextView,
        val size: TextView,
        val count: TextView,
        val progress: ProgressBar
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_cleanup)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        switchOlder30 = findViewById(R.id.switch_older_30)
        tvTotalSize = findViewById(R.id.tv_cleanup_total_size)
        tvTotalCount = findViewById(R.id.tv_cleanup_total_count)
        layoutCategories = findViewById(R.id.layout_cleanup_categories)
        layoutCleanupBar = findViewById(R.id.layout_cleanup_bar)
        tvBarTitle = findViewById(R.id.tv_cleanup_bar_title)
        tvBarSubtitle = findViewById(R.id.tv_cleanup_bar_subtitle)

        inflateCategoryRows()

        findViewById<ImageView>(R.id.btn_cleanup_refresh).setOnClickListener { refreshStats() }
        findViewById<MaterialButton>(R.id.btn_cleanup_do).setOnClickListener { confirmAndCleanup() }
        switchOlder30.setOnCheckedChangeListener { _, _ -> refreshStats() }

        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun inflateCategoryRows() {
        layoutCategories.removeAllViews()
        categoryRows.clear()
        val inflater = LayoutInflater.from(this)
        StorageCleanupCategory.values().forEach { category ->
            val row = inflater.inflate(R.layout.item_storage_cleanup_category, layoutCategories, false) as MaterialCardView
            val checkbox = row.findViewById<MaterialCheckBox>(R.id.cb_category)
            val icon = row.findViewById<ImageView>(R.id.iv_category_icon)
            val title = row.findViewById<TextView>(R.id.tv_category_title)
            val size = row.findViewById<TextView>(R.id.tv_category_size)
            val count = row.findViewById<TextView>(R.id.tv_category_count)
            val progress = row.findViewById<ProgressBar>(R.id.progress_category)

            title.text = getString(category.titleRes)
            icon.setImageResource(category.iconRes)
            checkbox.isChecked = category in selectedCategories
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedCategories += category else selectedCategories -= category
                updateCleanupBar()
            }
            // 点卡片主体浏览；勾选框自己处理选中
            row.setOnClickListener { openCategoryPreview(category) }
            checkbox.setOnClickListener { /* consume, avoid opening preview */ }

            layoutCategories.addView(row)
            categoryRows[category] = CategoryRowViews(row, checkbox, icon, title, size, count, progress)
        }
        updateCleanupBar()
    }

    private fun openCategoryPreview(category: StorageCleanupCategory) {
        val group = latestGroups[category]
        if (group == null || group.count == 0) {
            Utils.toast(this, getString(R.string.cleanup_category_empty))
            return
        }
        startActivity(
            Intent(this, StoragePreviewActivity::class.java).apply {
                putExtra(StoragePreviewActivity.EXTRA_CATEGORY_ID, category.id)
                putExtra(StoragePreviewActivity.EXTRA_OLDER_ONLY, switchOlder30.isChecked)
            }
        )
    }

    private fun refreshStats() {
        val olderOnly = switchOlder30.isChecked
        lifecycleScope.launch(Dispatchers.IO) {
            val groups = StorageCleanupCatalog.listAll(this@StorageCleanupActivity, olderOnly)
            withContext(Dispatchers.Main) {
                latestGroups = groups
                bindStats(groups)
                updateCleanupBar()
            }
        }
    }

    private fun bindStats(groups: Map<StorageCleanupCategory, StorageCleanupGroup>) {
        val totalCount = groups.values.sumOf { it.count }
        val totalBytes = groups.values.sumOf { it.bytes }
        val maxBytes = groups.values.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L

        tvTotalSize.text = StorageCleanupCatalog.formatBytes(totalBytes)
        tvTotalCount.text = getString(R.string.cleanup_total_count_fmt, totalCount)

        groups.forEach { (category, group) ->
            val row = categoryRows[category] ?: return@forEach
            row.size.text = StorageCleanupCatalog.formatBytes(group.bytes)
            row.count.text = getString(R.string.cleanup_file_count_fmt, group.count)
            row.progress.progress = ((group.bytes * 100L) / maxBytes).toInt().coerceIn(0, 100)
            row.card.alpha = if (group.count == 0) 0.55f else 1f
        }
    }

    private fun updateCleanupBar() {
        val selectedFiles = selectedCategories
            .mapNotNull { latestGroups[it] }
            .flatMap { it.files }
            .distinctBy { it.absolutePath }
        if (selectedFiles.isEmpty()) {
            layoutCleanupBar.visibility = View.GONE
            return
        }
        layoutCleanupBar.visibility = View.VISIBLE
        tvBarTitle.text = getString(R.string.cleanup_bar_title_fmt, selectedCategories.size)
        tvBarSubtitle.text = getString(
            R.string.cleanup_bar_subtitle_fmt,
            selectedFiles.size,
            StorageCleanupCatalog.formatBytes(selectedFiles.sumOf { it.length() })
        )
    }

    private fun confirmAndCleanup() {
        val selectedFiles = selectedCategories
            .mapNotNull { latestGroups[it] }
            .flatMap { it.files }
            .distinctBy { it.absolutePath }
        if (selectedFiles.isEmpty()) {
            Utils.toast(this, getString(R.string.select_cleanup_content))
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_cleanup_title))
            .setMessage(
                getString(
                    R.string.confirm_cleanup_message_fmt,
                    selectedFiles.size,
                    StorageCleanupCatalog.formatBytes(selectedFiles.sumOf { it.length() })
                )
            )
            .setPositiveButton(getString(R.string.start_cleanup)) { _, _ -> runCleanup(selectedFiles) }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun runCleanup(files: List<File>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = 0
            files.forEach { file ->
                if (file.exists() && file.isFile && runCatching { file.delete() }.getOrDefault(false)) {
                    deleted++
                }
            }
            withContext(Dispatchers.Main) {
                Utils.toast(this@StorageCleanupActivity, getString(R.string.cleanup_done_fmt, deleted))
                refreshStats()
            }
        }
    }
}
