package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * 待记 UI 统一设计 Token 与组件工厂（清爽金融卡片风，克制色彩、清晰层级、按压涟漪）。
 */
internal object CaptureUi {

    // 背景与容器
    val BG_PAGE = Color.parseColor("#F5F7FA")
    val BG_SURFACE = Color.WHITE
    val BG_SUBTLE = Color.parseColor("#F8FAFC")
    val BG_SEGMENT = Color.parseColor("#EEF2F6")
    val BORDER = Color.parseColor("#E5EAF0")
    val BORDER_FOCUS = Color.parseColor("#9FA8DA")
    val DIVIDER = Color.parseColor("#EEF2F6")

    // 文字
    val TEXT_PRIMARY = Color.parseColor("#0F172A")
    val TEXT_SECONDARY = Color.parseColor("#475569")
    val TEXT_MUTED = Color.parseColor("#6B7684")

    // 主色與語義色（与 App 的 brand_primary 对齐）
    val BRAND = Color.parseColor("#5C6BC0")
    val BRAND_DARK = Color.parseColor("#3F51B5")
    val BRAND_SOFT = Color.parseColor("#EDEFFA")
    val BRAND_BORDER = Color.parseColor("#C5CAE9")

    val SUCCESS = Color.parseColor("#059669")
    val SUCCESS_SOFT = Color.parseColor("#ECFDF5")
    val SUCCESS_BORDER = Color.parseColor("#A7F3D0")

    val WARNING = Color.parseColor("#D97706")
    val WARNING_SOFT = Color.parseColor("#FFFBEB")
    val WARNING_BORDER = Color.parseColor("#FDE68A")

    val DANGER = Color.parseColor("#DC2626")
    val DANGER_SOFT = Color.parseColor("#FEF2F2")
    val DANGER_BORDER = Color.parseColor("#FECACA")

    // 支付渠道品牌色
    val WECHAT = Color.parseColor("#07C160")
    val WECHAT_SOFT = Color.parseColor("#ECFDF5")
    val ALIPAY = Color.parseColor("#1677FF")
    val ALIPAY_SOFT = Color.parseColor("#EFF6FF")

    fun applyLightStatusBar(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            window.statusBarColor = Color.WHITE
            window.navigationBarColor = BG_PAGE
            // 必须先拿 decorView 触发 installDecor()，否则 PhoneWindow.getInsetsController() 内部 mDecor 为 null 会 NPE
            val decor = window.decorView ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                decor.windowInsetsController?.setSystemBarsAppearance(appearance, appearance)
            } else {
                var flags = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = decor.systemUiVisibility or flags
            }
        }
    }

    fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun icon(
        context: Context,
        @DrawableRes res: Int,
        sizeDp: Int = 18,
        tint: Int = TEXT_SECONDARY,
    ): ImageView = ImageView(context).apply {
        setImageDrawable(tintedDrawable(context, res, tint))
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    fun iconButton(
        context: Context,
        @DrawableRes res: Int,
        tint: Int = TEXT_SECONDARY,
        sizeDp: Int = 44,
        iconDp: Int = 20,
        contentDescription: String? = null,
        onClick: () -> Unit,
    ): FrameLayout {
        val size = dp(context, sizeDp)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = rippleBg(Color.TRANSPARENT, dp(context, 999))
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            setOnClickListener { onClick() }
            addView(
                icon(context, res, iconDp, tint),
                FrameLayout.LayoutParams(dp(context, iconDp), dp(context, iconDp), Gravity.CENTER),
            )
        }
    }

    /** 给 TextView 右侧挂一枚小图标；文字与图标使用同一 tint，避免基线漂移。 */
    fun applyTrailingIcon(
        textView: TextView,
        context: Context,
        @DrawableRes res: Int,
        tint: Int,
        sizeDp: Int = 11,
    ) {
        val drawable = tintedDrawable(context, res, tint) ?: return
        val size = dp(context, sizeDp)
        drawable.setBounds(0, 0, size, size)
        textView.setCompoundDrawables(null, null, drawable, null)
        textView.compoundDrawablePadding = dp(context, 4)
    }

    fun tintedDrawable(context: Context, @DrawableRes res: Int, tint: Int): Drawable? =
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(tint) }

    fun roundedBg(
        fillColor: Int,
        radiusPx: Int,
        strokeWidthPx: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = radiusPx.toFloat()
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }

    fun rippleBg(
        fillColor: Int,
        radiusPx: Int,
        strokeWidthPx: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
        rippleColor: Int = Color.parseColor("#1A0F172A"),
    ): RippleDrawable {
        val content = roundedBg(fillColor, radiusPx, strokeWidthPx, strokeColor)
        val mask = roundedBg(Color.WHITE, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun rippleBg(
        context: Context,
        fillColor: Int = BG_SURFACE,
        radiusDp: Int = 8,
    ): RippleDrawable = rippleBg(fillColor, dp(context, radiusDp))

    fun card(
        activity: Activity,
        paddingH: Int = 16,
        paddingV: Int = 14,
        radiusDp: Int = 16,
        fillColor: Int = BG_SURFACE,
        strokeColor: Int = BORDER,
    ): LinearLayout {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d + 0.5f).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(paddingH), px(paddingV), px(paddingH), px(paddingV))
            background = roundedBg(fillColor, px(radiusDp), px(1), strokeColor)
        }
    }

    fun button(
        activity: Activity,
        text: String,
        textColor: Int,
        bgColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        radiusDp: Int = 11,
        paddingH: Int = 14,
        paddingV: Int = 10,
        textSizeSp: Float = 13.5f,
        bold: Boolean = true,
        onClick: () -> Unit,
    ): TextView {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d + 0.5f).toInt()
        return TextView(activity).apply {
            this.text = text
            textSize = textSizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setPadding(px(paddingH), px(paddingV), px(paddingH), px(paddingV))
            background = rippleBg(
                fillColor = bgColor,
                radiusPx = px(radiusDp),
                strokeWidthPx = if (strokeColor != Color.TRANSPARENT) px(1) else 0,
                strokeColor = strokeColor,
            )
            setOnClickListener { onClick() }
        }
    }

    fun chip(
        activity: Activity,
        text: String,
        textColor: Int,
        bgColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        trailingIconRes: Int? = null,
        trailingIconTint: Int = textColor,
        trailingIconDp: Int = 10,
        onClick: (() -> Unit)? = null,
    ): TextView {
        val d = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * d + 0.5f).toInt()
        return TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(textColor)
            setPadding(px(10), px(5), px(10), px(5))
            val strokeW = if (strokeColor != Color.TRANSPARENT) px(1) else 0
            background = if (onClick != null) {
                rippleBg(bgColor, px(8), strokeW, strokeColor)
            } else {
                roundedBg(bgColor, px(8), strokeW, strokeColor)
            }
            if (onClick != null) setOnClickListener { onClick() }
            if (trailingIconRes != null) {
                applyTrailingIcon(this, activity, trailingIconRes, trailingIconTint, trailingIconDp)
            }
        }
    }
}
