package com.taostudio.tapaccounting.capture.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * P5 后插件的唯一界面：说明模块作用 + 打开主应用。
 *
 * 真正的待记收件箱/规则/历史都在敲敲记账主应用里，插件不再持有第二套 UI。
 */
class ModuleStatusActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        root.addView(TextView(this).apply {
            text = "敲敲记账 · 待记 Hook"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        })

        root.addView(TextView(this).apply {
            text = "这个 APK 只负责在 LSPosed 里 Hook 微信/支付宝/短信/系统通知，" +
                "把支付原始报文转发给敲敲记账主应用。待记列表、规则、气泡、通知和历史都在主应用里。"
            textSize = 14f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        root.addView(sectionCard(
            title = "LSPosed 作用域",
            body = "请勾选：微信、支付宝、android、com.android.phone。\n" +
                "com.android.phone 负责 SMS 和 relay 兜底队列；android 负责系统通知。",
            dp = ::dp,
        ))

        root.addView(sectionCard(
            title = "更新顺序",
            body = "先装本插件，再装/更新敲敲记账主应用。\n" +
                "旧插件的 capture.db 会保留，主应用首次启动会自动迁移；迁移完成后旧数据仍可回滚。",
            dp = ::dp,
        ))

        root.addView(TextView(this).apply {
            text = "同步规则/旧数据到敲敲记账"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1D4ED8"))
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
            }
            setOnClickListener {
                notifyAppSync()
                Toast.makeText(this@ModuleStatusActivity, "已通知敲敲记账拉取", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(18) })

        root.addView(TextView(this).apply {
            text = "打开敲敲记账"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage("com.taostudio.tapaccounting")
                if (intent != null) {
                    startActivity(intent)
                }
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(22) })

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)

        // 用户打开插件时进程在前台，这时通知主应用回拉规则/旧 DB 一定成功。
        notifyAppSync()
    }

    private fun notifyAppSync() {
        for (action in listOf(
            "com.taostudio.tapaccounting.action.CAPTURE_PULL_RULES",
            "com.taostudio.tapaccounting.action.CAPTURE_PULL_LEGACY",
        )) {
            runCatching {
                sendBroadcast(
                    Intent(action).setPackage("com.taostudio.tapaccounting")
                )
            }
        }
    }

    private fun sectionCard(
        title: String,
        body: String,
        dp: (Int) -> Int,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#E5EAF0"))
        }
        addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        })
        addView(TextView(context).apply {
            text = body
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) }
    }
}
