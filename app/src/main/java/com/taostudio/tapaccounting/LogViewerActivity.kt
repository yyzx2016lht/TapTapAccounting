package com.taostudio.tapaccounting

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 分层日志查看器。
 *
 * 默认只展示关键信息（I/W/E）；打开「完整日志」后文件才会写入 D 级细节。
 * 界面提供级别筛选 + 关键词搜索，按时间倒序展示。
 */
class LogViewerActivity : AppCompatActivity() {

    companion object {
        private const val MAX_READ_BYTES = 1024 * 1024
        private const val MAX_ENTRIES = 500
    }

    private enum class Filter { ALL, ERROR, WARN, INFO, DEBUG }

    private lateinit var rvEntries: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var tabRuntime: TextView
    private lateinit var tabCrash: TextView
    private lateinit var switchDevFullLogging: SwitchMaterial

    private val adapter = LogEntryAdapter()
    private var showingCrash = false
    private var activeFilter = Filter.ALL
    private var allEntries: List<LogEntry> = emptyList()
    private var loadGeneration = 0

    private val chips by lazy {
        mapOf(
            Filter.ALL to findViewById<TextView>(R.id.chip_all),
            Filter.ERROR to findViewById<TextView>(R.id.chip_error),
            Filter.WARN to findViewById<TextView>(R.id.chip_warn),
            Filter.INFO to findViewById<TextView>(R.id.chip_info),
            Filter.DEBUG to findViewById<TextView>(R.id.chip_debug),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        rvEntries = findViewById(R.id.rv_log_entries)
        tvEmpty = findViewById(R.id.tv_empty_hint)
        tvCount = findViewById(R.id.tv_log_count)
        etSearch = findViewById(R.id.et_log_search)
        tabRuntime = findViewById(R.id.tab_runtime_log)
        tabCrash = findViewById(R.id.tab_crash_log)
        switchDevFullLogging = findViewById(R.id.switch_dev_full_logging)

        // 最新在上：adapter 数据已是倒序，不需要 reverseLayout
        rvEntries.layoutManager = LinearLayoutManager(this)
        rvEntries.adapter = adapter

        val btnShare = findViewById<ImageView>(R.id.btn_header_action)
        val btnClear = findViewById<ImageView>(R.id.btn_header_action_secondary)
        btnShare.setColorFilter(ContextCompat.getColor(this, R.color.brand_primary))
        btnClear.setColorFilter(ContextCompat.getColor(this, R.color.danger_color))

        if (intent.getBooleanExtra("show_crash", false)) {
            showingCrash = true
        }

        updateTabs()
        loadCurrentLog()

        tabRuntime.setOnClickListener {
            showingCrash = false
            updateTabs()
            loadCurrentLog()
        }
        tabCrash.setOnClickListener {
            showingCrash = true
            updateTabs()
            loadCurrentLog()
        }

        btnShare.setOnClickListener { shareLogs() }
        btnClear.setOnClickListener {
            if (showingCrash) Logger.getCrashFile(this).delete()
            else {
                Logger.getLogFile(this).delete()
                File(Logger.getLogFile(this).parent, Logger.getLogFile(this).name + ".bak").delete()
            }
            loadCurrentLog()
        }

        switchDevFullLogging.isChecked = Prefs.isDeveloperFullLoggingEnabled(this)
        switchDevFullLogging.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDeveloperFullLoggingEnabled(this, isChecked)
            if (isChecked) {
                // 打开完整日志时自动确保文件落盘
                if (!Prefs.isLoggingEnabled(this)) {
                    Prefs.setLoggingEnabled(this, true)
                }
                Utils.toast(this, getString(R.string.dev_log_on))
            } else {
                Utils.toast(this, getString(R.string.dev_log_off))
            }
        }

        chips.forEach { (filter, chip) ->
            chip.setOnClickListener {
                activeFilter = filter
                applyFilter()
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
    }

    private fun updateTabs() {
        val active = ContextCompat.getColor(this, R.color.brand_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_tertiary)
        val crashActive = ContextCompat.getColor(this, R.color.danger_color)

        tabRuntime.setTextColor(if (!showingCrash) active else inactive)
        tabRuntime.setTypeface(null, if (!showingCrash) Typeface.BOLD else Typeface.NORMAL)
        tabCrash.setTextColor(if (showingCrash) crashActive else inactive)
        tabCrash.setTypeface(null, if (showingCrash) Typeface.BOLD else Typeface.NORMAL)
        findViewById<View>(R.id.tab_indicator)
            .setBackgroundColor(if (showingCrash) crashActive else active)
    }

    private fun loadCurrentLog() {
        val file = if (showingCrash) Logger.getCrashFile(this) else Logger.getLogFile(this)
        val emptyHint = if (showingCrash) {
            getString(R.string.no_crash_log_hint)
        } else {
            getString(R.string.no_runtime_log_hint)
        }
        val generation = ++loadGeneration
        adapter.submit(emptyList())
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = getString(R.string.loading_log)
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                readEntries(file)
            }
            if (generation != loadGeneration) return@launch
            allEntries = entries
            applyFilter()
            if (allEntries.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = emptyHint
            }
        }
    }

    private fun readEntries(file: File): List<LogEntry> {
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return try {
            val text = readTailText(file, MAX_READ_BYTES)
            LogParser.parse(text).takeLast(MAX_ENTRIES).asReversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readTailText(file: File, maxBytes: Int): String {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val bytesToRead = minOf(length, maxBytes.toLong()).toInt()
            val start = length - bytesToRead
            val buffer = ByteArray(bytesToRead)
            raf.seek(start)
            raf.readFully(buffer)
            return buffer.toString(Charsets.UTF_8)
        }
    }

    private fun applyFilter() {
        val query = etSearch.text?.toString()?.trim().orEmpty()
        val filtered = allEntries.filter { entry ->
            val levelOk = when (activeFilter) {
                Filter.ALL -> true
                Filter.ERROR -> entry.level == Logger.Level.ERROR
                Filter.WARN -> entry.level == Logger.Level.WARN
                Filter.INFO -> entry.level == Logger.Level.INFO
                Filter.DEBUG -> entry.level == Logger.Level.DEBUG || entry.level == null
            }
            levelOk && entry.matches(query)
        }
        adapter.submit(filtered)
        tvCount.text = getString(R.string.log_count_fmt, filtered.size)
        if (allEntries.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            if (tvEmpty.text.isNullOrBlank() || tvEmpty.text == getString(R.string.loading_log)) {
                tvEmpty.text = if (showingCrash) {
                    getString(R.string.no_crash_log_hint)
                } else {
                    getString(R.string.no_runtime_log_hint)
                }
            }
        } else if (filtered.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = getString(R.string.log_filter_empty)
        } else {
            tvEmpty.visibility = View.GONE
        }
        chips.forEach { (filter, chip) -> styleChip(chip, filter == activeFilter) }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.bg_log_filter_chip_selected)
            chip.setTextColor(Color.WHITE)
            chip.setTypeface(null, Typeface.BOLD)
        } else {
            chip.setBackgroundResource(R.drawable.bg_log_filter_chip)
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            chip.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun shareLogs() {
        val file = if (showingCrash) Logger.getCrashFile(this) else Logger.getLogFile(this)
        if (!file.exists() || file.length() == 0L) {
            Utils.toast(this, getString(R.string.no_log_content))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                Intent.createChooser(
                    intent,
                    if (showingCrash) getString(R.string.share_crash_log)
                    else getString(R.string.share_runtime_log)
                )
            )
        } catch (e: Exception) {
            Utils.toast(this, getString(R.string.share_failed))
        }
    }

    private inner class LogEntryAdapter : RecyclerView.Adapter<LogEntryAdapter.VH>() {
        private val items = mutableListOf<LogEntry>()

        fun submit(list: List<LogEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvLevel: TextView = view.findViewById(R.id.tv_level)
            val tvTag: TextView = view.findViewById(R.id.tv_tag)
            val tvTime: TextView = view.findViewById(R.id.tv_time)
            val tvMessage: TextView = view.findViewById(R.id.tv_message)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_log_entry, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val level = item.level
            val (label, color) = when (level) {
                Logger.Level.ERROR -> "E" to 0xFFE53935.toInt()
                Logger.Level.WARN -> "W" to 0xFFFB8C00.toInt()
                Logger.Level.INFO -> "I" to 0xFF5C6BC0.toInt()
                Logger.Level.DEBUG -> "D" to 0xFF78909C.toInt()
                null -> "?" to 0xFF9E9E9E.toInt()
            }
            holder.tvLevel.text = label
            holder.tvLevel.setBackgroundColor(color)
            holder.tvTag.text = item.tag
            holder.tvTime.text = if (item.time.length >= 12) item.time.substring(11) else item.time
            holder.tvMessage.text = item.message
        }
    }
}
