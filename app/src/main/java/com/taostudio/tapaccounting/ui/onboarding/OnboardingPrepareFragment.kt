package com.taostudio.tapaccounting.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.taostudio.tapaccounting.R

/**
 * 引导第 3 页：准备项。
 *
 * 状态检测统一走 [GestureReadiness]，与设置页的
 * [com.taostudio.tapaccounting.GesturePermissionGuideActivity] 保持同一口径。
 * 点击「去完成准备项」交给宿主打开那个页面（它已经是完整的逐项引导）。
 */
class OnboardingPrepareFragment : Fragment() {

    private lateinit var summary: TextView
    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_prepare, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        summary = view.findViewById(R.id.tv_ob_ready_summary)
        container = view.findViewById(R.id.container_ob_prepare_items)
        render()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置页回来，回到前台就重新检测
        if (::container.isInitialized) render()
    }

    fun render() {
        val ctx = requireContext()
        val overlayReady = GestureReadiness.hasOverlay(ctx)
        val backgroundReady = GestureReadiness.isBackgroundReady(ctx)
        val notificationReady = GestureReadiness.isNotificationReady(ctx)
        val readyCount = GestureReadiness.requiredReadyCount(ctx)

        summary.text = when {
            readyCount >= GestureReadiness.REQUIRED_TOTAL ->
                getString(R.string.ob_page3_ready_all)
            readyCount == 0 ->
                getString(R.string.ob_page3_ready_none)
            else -> getString(
                R.string.ob_page3_ready_partial,
                readyCount,
                GestureReadiness.REQUIRED_TOTAL
            )
        }

        container.removeAllViews()
        addRow(
            getString(R.string.overlay_permission_title),
            getString(R.string.overlay_permission_desc),
            overlayReady,
            required = true
        )
        addRow(
            getString(R.string.background_run_title),
            getString(R.string.background_run_desc),
            backgroundReady,
            required = true
        )
        // 自启/锁后台无法自动判定，始终以「建议确认」呈现
        addRow(
            getString(R.string.autostart_lock_title),
            getString(R.string.autostart_lock_desc),
            ready = null,
            required = true
        )
        addRow(
            getString(R.string.notification_permission_title),
            getString(R.string.notification_permission_desc),
            notificationReady,
            required = false
        )
    }

    private fun addRow(title: String, desc: String, ready: Boolean?, required: Boolean) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_card))
            setPadding(
                (16 * dp).toInt(), (12 * dp).toInt(),
                (16 * dp).toInt(), (12 * dp).toInt()
            )
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val (label, textColor, bgColor) = when (ready) {
            true -> Triple(getString(R.string.ready_label), "#1B7F4B", "#E8F7EF")
            false -> Triple(getString(R.string.pending_label), "#C24132", "#FDECE9")
            null -> Triple(getString(R.string.suggest_confirm_label), "#9A6500", "#FFF5D8")
        }
        titleRow.addView(TextView(ctx).apply {
            text = if (required) label else getString(R.string.optional_label)
            setTextColor(android.graphics.Color.parseColor(
                if (required) textColor else "#607D8B"
            ))
            textSize = 11f
            setPadding((9 * dp).toInt(), (4 * dp).toInt(), (9 * dp).toInt(), (4 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor(
                    if (required) bgColor else "#EDF4F7"
                ))
                cornerRadius = 10 * dp
            }
        })
        row.addView(titleRow)

        row.addView(TextView(ctx).apply {
            text = desc
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 12f
            setLineSpacing(3 * dp, 1f)
            setPadding(0, (5 * dp).toInt(), 0, 0)
        })

        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (8 * dp).toInt()
        })
    }
}
