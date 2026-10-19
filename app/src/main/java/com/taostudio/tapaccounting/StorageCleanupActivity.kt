package com.taostudio.tapaccounting

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StorageCleanupActivity : AppCompatActivity() {

    private lateinit var cbOlder30: MaterialCheckBox
    private lateinit var tvTotal: TextView
    private lateinit var layoutCategories: LinearLayout

    private var latestGroups: Map<StorageCleanupCategory, StorageCleanupGroup> = emptyMap()
    private val selectedCategories = mutableSetOf<StorageCleanupCategory>().apply {
        add(StorageCleanupCategory.VOICE)
        add(StorageCleanupCategory.IMAGES)
        add(StorageCleanupCategory.SCREEN_CAPTURES)
        add(StorageCleanupCategory.TEMP_CACHE)
    }
    private val categoryViews = linkedMapOf<StorageCleanupCategory, CategoryRow>()

    private data class CategoryRow(
        val root: View,
        val checkbox: MaterialCheckBox,
        val title: TextView,
        val stats: TextView,
        val openArea: View
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_cleanup)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        cbOlder30 = findViewById(R.id.cb_cleanup_older_30)
        tvTotal = findViewById(R.id.tv_cleanup_total_stats)
        layoutCategories = findViewById(R.id.layout_cleanup_categories)

        inflateCategoryRows()

        findViewById<MaterialButton>(R.id.btn_cleanup_refresh).setOnClickListener { refreshStats() }
        findViewById<MaterialButton>(R.id.btn_cleanup_do).setOnClickListener { confirmAndCleanup() }
        cbOlder30.setOnCheckedChangeListener { _, _ -> refreshStats() }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_history_bills).setOnClickListener {
            startActivity(Intent(this, com.taostudio.tapaccounting.ui.activity.HistoryBillActivity::class.java))
        }

        refreshStats()
        loadHistoryBillsCount()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun inflateCategoryRows() {
        layoutCategories.removeAllViews()
        categoryViews.clear()
        val inflater = LayoutInflater.from(this)
        StorageCleanupCategory.values().forEach { category ->
            val row = inflater.inflate(R.layout.item_storage_cleanup_category, layoutCategories, false)
            val checkbox = row.findViewById<MaterialCheckBox>(R.id.cb_category)
            val title = row.findViewById<TextView>(R.id.tv_category_title)
            val stats = row.findViewById<TextView>(R.id.tv_category_stats)
            val openArea = row.findViewById<View>(R.id.layout_category_open)
            title.text = getString(category.titleRes)
            checkbox.isChecked = category in selectedCategories
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedCategories += category else selectedCategories -= category
            }
            openArea.setOnClickListener { openCategoryPreview(category) }
            layoutCategories.addView(row)
            categoryViews[category] = CategoryRow(row, checkbox, title, stats, openArea)
        }
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
                putExtra(StoragePreviewActivity.EXTRA_OLDER_ONLY, cbOlder30.isChecked)
            }
        )
    }

    private fun loadHistoryBillsCount() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@StorageCleanupActivity)
            val count = db.deletedBillDao().getCount()
            withContext(Dispatchers.Main) {
                val tvCount = findViewById<TextView>(R.id.tv_history_bills_count)
                tvCount.text = if (count > 0) {
                    getString(R.string.deleted_bill_count_fmt, count)
                } else {
                    getString(R.string.view_deleted_bills)
                }
            }
        }
    }

    private fun refreshStats() {
        val olderOnly = cbOlder30.isChecked
        lifecycleScope.launch(Dispatchers.IO) {
            val groups = StorageCleanupCatalog.listAll(this@StorageCleanupActivity, olderOnly)
            withContext(Dispatchers.Main) {
                latestGroups = groups
                bindStats(groups)
            }
        }
    }

    private fun bindStats(groups: Map<StorageCleanupCategory, StorageCleanupGroup>) {
        var totalCount = 0
        var totalBytes = 0L
        groups.forEach { (category, group) ->
            totalCount += group.count
            totalBytes += group.bytes
            categoryViews[category]?.stats?.text = getString(
                R.string.cleanup_category_stats_fmt,
                group.count,
                StorageCleanupCatalog.formatBytes(group.bytes)
            )
        }
        tvTotal.text = getString(
            R.string.cleanable_total,
            totalCount,
            StorageCleanupCatalog.formatBytes(totalBytes)
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
        val selectedBytes = selectedFiles.sumOf { it.length() }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_cleanup_title))
            .setMessage(
                getString(
                    R.string.confirm_cleanup_message_fmt,
                    selectedFiles.size,
                    StorageCleanupCatalog.formatBytes(selectedBytes)
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
