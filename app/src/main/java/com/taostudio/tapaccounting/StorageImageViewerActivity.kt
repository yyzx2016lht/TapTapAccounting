package com.taostudio.tapaccounting

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File

class StorageImageViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATHS = "extra_image_paths"
        const val EXTRA_INDEX = "extra_index"
    }

    private lateinit var pager: ViewPager2
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView

    private var imagePaths: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_image_viewer)

        pager = findViewById(R.id.pager_images)
        tvTitle = findViewById(R.id.tv_viewer_title)
        tvHint = findViewById(R.id.tv_viewer_hint)
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)
            .coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0))

        if (imagePaths.isEmpty()) {
            tvTitle.text = getString(R.string.no_image)
            tvHint.visibility = android.view.View.GONE
            return
        }

        pager.adapter = ImagePagerAdapter(imagePaths)
        pager.setCurrentItem(startIndex, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bindTitle(position)
            }
        })
        bindTitle(startIndex)
        tvHint.visibility = if (imagePaths.size > 1) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun bindTitle(index: Int) {
        val file = File(imagePaths[index])
        tvTitle.text = "${index + 1}/${imagePaths.size}  ·  ${file.name}"
    }

    private class ImagePagerAdapter(
        private val paths: List<String>
    ) : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val imageView = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return VH(imageView)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val path = paths[position]
            holder.imageView.setImageBitmap(
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            )
        }

        override fun getItemCount(): Int = paths.size

        class VH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
    }
}
