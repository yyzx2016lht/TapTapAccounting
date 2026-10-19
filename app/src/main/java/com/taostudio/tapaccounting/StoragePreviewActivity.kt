package com.taostudio.tapaccounting

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.ui.widget.SecondaryPageHeaderView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StoragePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        const val EXTRA_OLDER_ONLY = "extra_older_only"

        // 兼容旧入口（若仍有勾选后统一预览）
        const val EXTRA_INCLUDE_VOICE = "extra_include_voice"
        const val EXTRA_INCLUDE_IMAGES = "extra_include_images"
        const val EXTRA_INCLUDE_CHAT_AVATAR = "extra_include_chat_avatar"
        const val EXTRA_INCLUDE_BOOK_COVER_BG = "extra_include_book_cover_bg"
        const val EXTRA_INCLUDE_TEMP_CACHE = "extra_include_temp_cache"
        const val EXTRA_INCLUDE_SCREEN_CAPTURES = "extra_include_screen_captures"
    }

    private enum class PreviewTab { IMAGES, AUDIOS }

    private lateinit var header: SecondaryPageHeaderView
    private lateinit var btnImages: MaterialButton
    private lateinit var btnAudios: MaterialButton
    private lateinit var layoutTabs: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var recyclerView: RecyclerView

    private var imageFiles: List<File> = emptyList()
    private var audioFiles: List<File> = emptyList()
    private var otherFiles: List<File> = emptyList()
    private var currentTab: PreviewTab = PreviewTab.IMAGES

    private val imageAdapter = ImageGridAdapter { _, index -> openImageViewer(index) }
    private val audioAdapter = AudioListAdapter { file -> togglePlay(file) }
    private val fileAdapter = FileListAdapter()

    private var mediaPlayer: MediaPlayer? = null
    private var playingPath: String? = null
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_preview)

        header = findViewById(R.id.secondary_header)
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        btnImages = findViewById(R.id.btn_tab_images)
        btnAudios = findViewById(R.id.btn_tab_audios)
        layoutTabs = findViewById(R.id.layout_preview_tabs)
        tvSummary = findViewById(R.id.tv_preview_summary)
        tvEmpty = findViewById(R.id.tv_preview_empty)
        recyclerView = findViewById(R.id.rv_preview)

        btnImages.setOnClickListener { switchTab(PreviewTab.IMAGES) }
        btnAudios.setOnClickListener { switchTab(PreviewTab.AUDIOS) }

        loadFiles()
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    private fun loadFiles() {
        val category = StorageCleanupCategory.fromId(intent.getStringExtra(EXTRA_CATEGORY_ID))
        val olderOnly = intent.getBooleanExtra(EXTRA_OLDER_ONLY, false)

        lifecycleScope.launch(Dispatchers.IO) {
            val files = if (category != null) {
                StorageCleanupCatalog.listGroup(this@StoragePreviewActivity, category, olderOnly).files
            } else {
                // 旧多选入口兜底
                buildLegacySelection(olderOnly)
            }.sortedByDescending { it.lastModified() }

            val images = files.filter { StorageCleanupCatalog.isImageFile(it) }
            val audios = files.filter { StorageCleanupCatalog.isAudioFile(it) }
            val others = files.filterNot {
                StorageCleanupCatalog.isImageFile(it) || StorageCleanupCatalog.isAudioFile(it)
            }

            withContext(Dispatchers.Main) {
                header.setTitle(
                    category?.let { getString(it.titleRes) } ?: getString(R.string.preview_cleanup)
                )
                imageFiles = images
                audioFiles = audios
                otherFiles = others
                val bytes = files.sumOf { it.length() }
                tvSummary.text = getString(
                    R.string.cleanup_category_stats_fmt,
                    files.size,
                    StorageCleanupCatalog.formatBytes(bytes)
                )

                val showTabs = images.isNotEmpty() && audios.isNotEmpty()
                layoutTabs.visibility = if (showTabs) View.VISIBLE else View.GONE
                when {
                    images.isNotEmpty() -> switchTab(PreviewTab.IMAGES)
                    audios.isNotEmpty() -> switchTab(PreviewTab.AUDIOS)
                    others.isNotEmpty() -> showOtherFiles()
                    else -> {
                        recyclerView.adapter = null
                        tvEmpty.visibility = View.VISIBLE
                        tvEmpty.text = getString(R.string.cleanup_category_empty)
                    }
                }
            }
        }
    }

    private fun buildLegacySelection(olderOnly: Boolean): List<File> {
        val selected = mutableListOf<File>()
        fun include(cat: StorageCleanupCategory, key: String, default: Boolean) {
            if (intent.getBooleanExtra(key, default)) {
                selected += StorageCleanupCatalog.listGroup(this, cat, olderOnly).files
            }
        }
        include(StorageCleanupCategory.VOICE, EXTRA_INCLUDE_VOICE, true)
        include(StorageCleanupCategory.IMAGES, EXTRA_INCLUDE_IMAGES, true)
        include(StorageCleanupCategory.CHAT_AVATAR, EXTRA_INCLUDE_CHAT_AVATAR, false)
        include(StorageCleanupCategory.BOOK_COVER_BG, EXTRA_INCLUDE_BOOK_COVER_BG, false)
        include(StorageCleanupCategory.SCREEN_CAPTURES, EXTRA_INCLUDE_SCREEN_CAPTURES, true)
        include(StorageCleanupCategory.TEMP_CACHE, EXTRA_INCLUDE_TEMP_CACHE, true)
        return selected.distinctBy { it.absolutePath }
    }

    private fun switchTab(tab: PreviewTab) {
        currentTab = tab
        stopPlayback()
        updateTabStyle()
        when (tab) {
            PreviewTab.IMAGES -> {
                recyclerView.layoutManager = GridLayoutManager(this, 3)
                recyclerView.adapter = imageAdapter
                imageAdapter.submit(imageFiles)
                tvEmpty.visibility = if (imageFiles.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty.text = getString(R.string.no_preview_image)
            }
            PreviewTab.AUDIOS -> {
                recyclerView.layoutManager = LinearLayoutManager(this)
                recyclerView.adapter = audioAdapter
                audioAdapter.submit(audioFiles, playingPath)
                tvEmpty.visibility = if (audioFiles.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty.text = getString(R.string.no_preview_audio)
            }
        }
    }

    private fun showOtherFiles() {
        layoutTabs.visibility = View.GONE
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = fileAdapter
        fileAdapter.submit(otherFiles)
        tvEmpty.visibility = if (otherFiles.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = getString(R.string.cleanup_category_empty)
    }

    private fun updateTabStyle() {
        val selectedBg = 0xFF5C80EA.toInt()
        val selectedText = 0xFFFFFFFF.toInt()
        val normalBg = 0xFFEAF0FB.toInt()
        val normalText = 0xFF44556E.toInt()
        if (currentTab == PreviewTab.IMAGES) {
            btnImages.setBackgroundColor(selectedBg)
            btnImages.setTextColor(selectedText)
            btnAudios.setBackgroundColor(normalBg)
            btnAudios.setTextColor(normalText)
        } else {
            btnAudios.setBackgroundColor(selectedBg)
            btnAudios.setTextColor(selectedText)
            btnImages.setBackgroundColor(normalBg)
            btnImages.setTextColor(normalText)
        }
    }

    private fun openImageViewer(index: Int) {
        if (imageFiles.isEmpty()) return
        startActivity(
            Intent(this, StorageImageViewerActivity::class.java).apply {
                putStringArrayListExtra(
                    StorageImageViewerActivity.EXTRA_IMAGE_PATHS,
                    ArrayList(imageFiles.map { it.absolutePath })
                )
                putExtra(StorageImageViewerActivity.EXTRA_INDEX, index)
            }
        )
    }

    private fun togglePlay(file: File) {
        val path = file.absolutePath
        if (playingPath == path && mediaPlayer?.isPlaying == true) {
            stopPlayback()
            audioAdapter.submit(audioFiles, playingPath)
            return
        }
        stopPlayback()
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(path)
            player.prepare()
            player.setOnCompletionListener {
                stopPlayback()
                audioAdapter.submit(audioFiles, playingPath)
            }
            player.start()
            mediaPlayer = player
            playingPath = path
            audioAdapter.submit(audioFiles, playingPath)
        }.onFailure {
            runCatching { player.release() }
            Utils.toast(this, getString(R.string.play_failed))
        }
    }

    private fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        playingPath = null
    }

    private inner class ImageGridAdapter(
        private val onClick: (File, Int) -> Unit
    ) : RecyclerView.Adapter<ImageGridAdapter.VH>() {
        private val data = mutableListOf<File>()

        fun submit(list: List<File>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_storage_preview_image, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = data[position]
            holder.tvName.text = file.name
            holder.ivThumb.setImageBitmap(decodeSampledBitmap(file, 320))
            holder.itemView.setOnClickListener { onClick(file, position) }
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_preview_thumb)
            val tvName: TextView = v.findViewById(R.id.tv_preview_thumb_name)
        }
    }

    private inner class AudioListAdapter(
        private val onPlayClick: (File) -> Unit
    ) : RecyclerView.Adapter<AudioListAdapter.VH>() {
        private val data = mutableListOf<File>()
        private var currentPlayingPath: String? = null

        fun submit(list: List<File>, playingPath: String?) {
            data.clear()
            data.addAll(list)
            currentPlayingPath = playingPath
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_storage_preview_audio, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = data[position]
            val modified = if (file.lastModified() > 0) {
                dateFormat.format(Date(file.lastModified()))
            } else {
                getString(R.string.unknown_time)
            }
            holder.tvTitle.text = file.name
            holder.tvMeta.text = "${StorageCleanupCatalog.formatBytes(file.length())} · $modified"
            holder.btnPlay.text =
                if (file.absolutePath == currentPlayingPath) getString(R.string.stop) else getString(R.string.play)
            holder.btnPlay.setOnClickListener { onPlayClick(file) }
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tv_audio_title)
            val tvMeta: TextView = v.findViewById(R.id.tv_audio_meta)
            val btnPlay: MaterialButton = v.findViewById(R.id.btn_audio_play)
        }
    }

    private inner class FileListAdapter : RecyclerView.Adapter<FileListAdapter.VH>() {
        private val data = mutableListOf<File>()

        fun submit(list: List<File>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_storage_preview_audio, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = data[position]
            val modified = if (file.lastModified() > 0) {
                dateFormat.format(Date(file.lastModified()))
            } else {
                getString(R.string.unknown_time)
            }
            holder.tvTitle.text = file.name
            holder.tvMeta.text = "${StorageCleanupCatalog.formatBytes(file.length())} · $modified"
            holder.btnPlay.visibility = View.GONE
        }

        override fun getItemCount(): Int = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tv_audio_title)
            val tvMeta: TextView = v.findViewById(R.id.tv_audio_meta)
            val btnPlay: MaterialButton = v.findViewById(R.id.btn_audio_play)
        }
    }

    private fun decodeSampledBitmap(file: File, reqSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var inSampleSize = 1
        val height = bounds.outHeight
        val width = bounds.outWidth
        if (height > reqSize || width > reqSize) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqSize && halfWidth / inSampleSize >= reqSize) {
                inSampleSize *= 2
                halfHeight /= 2
                halfWidth /= 2
            }
        }
        val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize.coerceAtLeast(1) }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }
}
