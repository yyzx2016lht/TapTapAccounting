package com.taostudio.tapaccounting.ui.main.home

import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File

internal class HomeBannerController(
    private val fragment: Fragment,
    private val headerBannerLayout: View,
    private val ivHeaderBanner: ImageView,
    private val vBannerTopScrim: View,
    private val vBannerGradient: View,
    private val tvMonthSelector: TextView,
    private val tvMonthExpense: TextView,
    private val tvMonthExpenseLabel: TextView,
    private val tvMonthIncomeLabel: TextView,
    private val tvMonthIncome: TextView,
    private val tvMonthBalanceLabel: TextView,
    private val tvMonthBalance: TextView,
    private val progressBudgetBar: LinearProgressIndicator,
    private val ivBookSwitcher: ImageView,
    private val ivCalendarView: ImageView,
    private val ivSearchBill: ImageView,
    private val getSelectedBookName: () -> String,
    private val requestPickBannerImage: () -> Unit,
    private val dismissKeyboardForDialog: () -> Unit,
    private val configureDialogWindow: (Dialog, Int, Float) -> Unit,
) {
    fun setupBannerLongPress() {
        headerBannerLayout.setOnLongClickListener {
            dismissKeyboardForDialog()
            val selectedBookName = getSelectedBookName()
            val hasBanner = BookAccountManager.getBookBannerPath(fragment.requireContext(), selectedBookName) != null
            val themeCtx = ContextThemeWrapper(fragment.requireContext(), R.style.Theme_TapAccounting)
            val panel = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.dialog_delete_followup_picker, null, false)
            val dialog = AlertDialog.Builder(themeCtx)
                .setView(panel)
                .create()
            panel.findViewById<TextView>(R.id.tv_followup_picker_title).text = "「$selectedBookName」外观设置"
            val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_followup_picker_options)

            fun addOption(label: String, onClick: () -> Unit) {
                val item = LayoutInflater.from(fragment.requireContext())
                    .inflate(R.layout.item_delete_followup_picker_option, optionsContainer, false)
                item.findViewById<TextView>(R.id.tv_followup_picker_option).text = label
                item.setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
                optionsContainer.addView(item)
            }

            addOption(if (hasBanner) "更换封面图" else "设置封面图") { requestPickBannerImage() }
            if (hasBanner) {
                addOption("移除封面图") { removeBanner() }
            }
            addOption("修改主题颜色") { showColorPickerDialog() }

            panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).text = "取消"
            panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).setOnClickListener { dialog.dismiss() }
            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = fragment.requireContext(),
                widthRatio = 0.86f,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = false
            )
            true
        }
    }

    fun updateHeaderBanner() {
        if (!fragment.isAdded) return
        val ctx = fragment.requireContext()
        val selectedBookName = getSelectedBookName()
        val bannerPath = BookAccountManager.getBookBannerPath(ctx, selectedBookName)
        val bookColor = BookAccountManager.getBookColor(ctx, selectedBookName)
        headerBannerLayout.setBackgroundColor(bookColor)
        if (!bannerPath.isNullOrEmpty()) {
            val file = File(bannerPath)
            if (file.exists()) {
                ivHeaderBanner.visibility = View.VISIBLE
                // 拆分上下遮罩：顶部保护胶囊/图标，底部保护文本，避免“一层糊全局”
                vBannerTopScrim.visibility = View.VISIBLE
                vBannerTopScrim.alpha = 0.82f
                vBannerGradient.visibility = View.VISIBLE
                vBannerGradient.alpha = 0.78f
                applyTopBarChipBackgroundStyle(useStrong = true)
                applyBannerTextColor(useLightText = true)
                applyBannerTextContrastEnhancement(enabled = true)
                Glide.with(fragment)
                    .load(file)
                    .centerCrop()
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                    .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                    .placeholder(ivHeaderBanner.drawable)
                    .error(ivHeaderBanner.drawable)
                    .into(ivHeaderBanner)
                return
            }
        }
        ivHeaderBanner.visibility = View.GONE
        vBannerTopScrim.visibility = View.GONE
        vBannerGradient.visibility = View.GONE
        Glide.with(fragment).clear(ivHeaderBanner)
        applyTopBarChipBackgroundStyle(useStrong = false)
        val r = Color.red(bookColor)
        val g = Color.green(bookColor)
        val b = Color.blue(bookColor)
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        applyBannerTextColor(useLightText = luminance < 160)
        applyBannerTextContrastEnhancement(enabled = false)
    }

    private fun showColorPickerDialog() {
        dismissKeyboardForDialog()
        val ctx = fragment.requireContext()
        val selectedBookName = getSelectedBookName()
        val currentColor = BookAccountManager.getBookColor(ctx, selectedBookName)
        val density = fragment.resources.displayMetrics.density

        fun px(value: Int): Int = (value * density).toInt()

        val colorOptions = listOf(
            "蓝色" to 0xFF4080FF.toInt(),
            "深蓝" to 0xFF1A56CC.toInt(),
            "天蓝" to 0xFF29A8E0.toInt(),
            "青色" to 0xFF29A8A8.toInt(),
            "绿色" to 0xFF2FA36B.toInt(),
            "深绿" to 0xFF1E7A50.toInt(),
            "橙色" to 0xFFE07A30.toInt(),
            "红色" to 0xFFE05A5A.toInt(),
            "深红" to 0xFFC0392B.toInt(),
            "粉色" to 0xFFE0609A.toInt(),
            "紫色" to 0xFF8A4FD1.toInt(),
            "深紫" to 0xFF5E3596.toInt(),
            "棕色" to 0xFF8D5524.toInt(),
            "深灰" to 0xFF555555.toInt(),
            "炭黑" to 0xFF222222.toInt(),
        )

        val themeCtx = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_delete_followup_picker, null, false)
        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        panel.findViewById<TextView>(R.id.tv_followup_picker_title).text = "选择主题颜色"
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_followup_picker_options)

        colorOptions.forEach { (name, color) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_delete_option_item)
                minimumHeight = px(52)
                setPadding(px(12), px(10), px(12), px(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(8) }
            }

            val swatch = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(px(20), px(20))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                }
            }
            val nameView = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = px(10) }
                text = name
                setTextColor(Color.parseColor("#243146"))
                textSize = 14f
            }
            val checkView = TextView(ctx).apply {
                text = if (color == currentColor) "✓" else ""
                setTextColor(Color.parseColor("#1F2937"))
                textSize = 16f
            }

            row.addView(swatch)
            row.addView(nameView)
            row.addView(checkView)
            row.setOnClickListener {
                BookAccountManager.setBookColor(ctx, getSelectedBookName(), color)
                updateHeaderBanner()
                dialog.dismiss()
            }
            optionsContainer.addView(row)
        }

        panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).text = "取消"
        panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).setOnClickListener { dialog.dismiss() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun removeBanner() {
        BookAccountManager.setBookBannerPath(fragment.requireContext(), getSelectedBookName(), null)
        updateHeaderBanner()
        Toast.makeText(fragment.requireContext(), "已移除封面图", Toast.LENGTH_SHORT).show()
    }

    private fun applyBannerTextContrastEnhancement(enabled: Boolean) {
        val shadowColor = 0x66000000.toInt()
        if (enabled) {
            tvMonthExpense.setShadowLayer(1f, 0f, 1f, shadowColor)
            tvMonthSelector.setShadowLayer(1.5f, 0f, 1f, shadowColor)
            tvMonthExpenseLabel.setShadowLayer(1.25f, 0f, 1f, shadowColor)
            tvMonthIncomeLabel.setShadowLayer(1f, 0f, 1f, shadowColor)
            tvMonthIncome.setShadowLayer(1.25f, 0f, 1f, shadowColor)
            tvMonthBalanceLabel.setShadowLayer(1f, 0f, 1f, shadowColor)
            tvMonthBalance.setShadowLayer(1.25f, 0f, 1f, shadowColor)
        } else {
            tvMonthExpense.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            tvMonthSelector.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            tvMonthExpenseLabel.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            tvMonthIncomeLabel.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            tvMonthIncome.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            tvMonthBalanceLabel.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            tvMonthBalance.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private fun applyTopBarChipBackgroundStyle(useStrong: Boolean) {
        tvMonthSelector.background = null
    }

    fun applyBannerTextColor(useLightText: Boolean) {
        val primary = if (useLightText) Color.WHITE else Color.parseColor("#1A1A1A")
        val secondary = if (useLightText) 0xE8FFFFFF.toInt() else 0xC0333333.toInt()
        val valueAccent = if (useLightText) 0xF6FFFFFF.toInt() else Color.parseColor("#1F2D3D")
        val tintList = android.content.res.ColorStateList.valueOf(primary)

        tvMonthSelector.setTextColor(primary)
        tvMonthSelector.compoundDrawablesRelative.forEach { d ->
            d?.mutate()?.setTint(primary)
        }
        tvMonthExpense.setTextColor(primary)
        tvMonthExpenseLabel.setTextColor(secondary)
        tvMonthIncomeLabel.setTextColor(secondary)
        tvMonthIncome.setTextColor(valueAccent)
        tvMonthBalanceLabel.setTextColor(secondary)
        tvMonthBalance.setTextColor(valueAccent)
        // 预算进度条跟着明暗走：底色是半透明 track + 实色 indicator，两者在 16 色账本底色下都得读得出
        progressBudgetBar.trackColor = if (useLightText) 0x33FFFFFF else 0x1F000000
        progressBudgetBar.setIndicatorColor(valueAccent)
        ImageViewCompat.setImageTintList(ivBookSwitcher, tintList)
        ImageViewCompat.setImageTintList(ivCalendarView, tintList)
        ImageViewCompat.setImageTintList(ivSearchBill, tintList)
    }
}

