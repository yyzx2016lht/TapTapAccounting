package com.taostudio.tapaccounting.capture.notify

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.CaptureSource
import com.taostudio.tapaccounting.capture.data.PaymentCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 付款瞬间的底部微胶囊气泡（默认 4 秒自动收起，支持点击原地展开补一句备注）。
 */
object CaptureBubbleManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var dismissRunnable: Runnable? = null

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun showBubble(context: Context, capture: PaymentCapture, pendingCount: Int) {
        val app = context.applicationContext
        if (!canDrawOverlays(app)) return
        mainHandler.post {
            showBubbleOnMain(app, capture, pendingCount)
        }
    }

    private fun showBubbleOnMain(app: Context, capture: PaymentCapture, pendingCount: Int) {
        dismissImmediate(app)
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val density = app.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FA1E293B"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            elevation = dp(10).toFloat()
        }

        val topRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badgeColor = when (capture.source) {
            CaptureSource.WECHAT -> "#07C160"
            CaptureSource.ALIPAY -> "#1677FF"
            else -> "#3B82F6"
        }
        val badgeText = when (capture.source) {
            CaptureSource.WECHAT -> "微信"
            CaptureSource.ALIPAY -> "支付宝"
            else -> "支付"
        }

        val badge = TextView(app).apply {
            text = badgeText
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(badgeColor))
                cornerRadius = dp(10).toFloat()
            }
        }
        topRow.addView(badge)

        val amountStr = capture.amountCents?.let {
            String.format(Locale.CHINA, "¥%.2f", it / 100.0)
        } ?: "金额待补"
        val assetStr = capture.assetName ?: capture.paymentMethodRaw
        val merchantStr = capture.merchant

        val summaryText = buildString {
            append("  ").append(amountStr)
            if (!merchantStr.isNullOrBlank()) append(" · ").append(merchantStr.take(10))
            if (!assetStr.isNullOrBlank()) append(" (").append(assetStr.take(8)).append(")")
            append("  · 待记 ").append(pendingCount).append(" 笔")
        }

        val tvSummary = TextView(app).apply {
            text = summaryText
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(tvSummary)

        val editRow = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }

        val etNote = EditText(app).apply {
            hint = "买什么了？如：青菜（回车保存）"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        fun submitNote() {
            val noteText = etNote.text?.toString()?.trim().orEmpty()
            scope.launch {
                val updated = withContext(Dispatchers.IO) {
                    CaptureApp.from(app).repository.updateNoteAndReclassify(app, capture.id, noteText)
                }
                CaptureNotifier.refreshAll(app)
                val catHint = updated?.categoryName?.let { " · 自动归类：$it" }.orEmpty()
                Toast.makeText(
                    app,
                    if (noteText.isNotEmpty()) "已留底备注：$noteText$catHint" else "已保存",
                    Toast.LENGTH_SHORT,
                ).show()
                dismissImmediate(app)
            }
        }

        etNote.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitNote()
                true
            } else {
                false
            }
        }

        val btnSaveNote = TextView(app).apply {
            text = "保存"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3B82F6"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { submitNote() }
        }

        editRow.addView(etNote)
        editRow.addView(btnSaveNote)

        val btnAddNote = TextView(app).apply {
            text = "✎ 补一句"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E3A8A"))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                cancelAutoDismiss()
                visibility = View.GONE
                editRow.visibility = View.VISIBLE
                enableWindowFocus(app, etNote)
            }
        }
        topRow.addView(btnAddNote)

        val btnClose = TextView(app).apply {
            text = " ✕ "
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(6), dp(4), dp(2), dp(4))
            setOnClickListener { dismissImmediate(app) }
        }
        topRow.addView(btnClose)

        root.addView(topRow)
        root.addView(editRow)

        val params = WindowManager.LayoutParams().apply {
            width = (app.resources.displayMetrics.widthPixels * 0.92f).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(68)
        }

        try {
            root.alpha = 0f
            root.translationY = dp(24).toFloat()
            wm.addView(root, params)
            currentView = root
            currentParams = params
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
            scheduleAutoDismiss(app, 4_000L)
        } catch (_: Throwable) {
            currentView = null
            currentParams = null
        }
    }

    private fun enableWindowFocus(app: Context, targetEdit: EditText) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = currentView ?: return
        val params = currentParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        runCatching { wm.updateViewLayout(view, params) }
        targetEdit.requestFocus()
        mainHandler.postDelayed({
            val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(targetEdit, InputMethodManager.SHOW_IMPLICIT)
        }, 80L)
    }

    private fun scheduleAutoDismiss(app: Context, delayMs: Long) {
        cancelAutoDismiss()
        val runnable = Runnable { dismissImmediate(app) }
        dismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
    }

    fun dismissImmediate(context: Context) {
        cancelAutoDismiss()
        val view = currentView ?: return
        currentView = null
        currentParams = null
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeViewImmediate(view) }
    }
}
