package com.taostudio.tapaccounting.logic

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.capture.bridge.RuleLearningManager
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * “规则学习方式 = 问一次”的统一入口。
 *
 * 所有入账路径（悬浮窗单笔/批量、拆分、待记列表、气泡）在成功后调用这里；
 * 只有 ASK 模式才弹，AUTO/OFF 直接返回 false，由 RuleLearningManager 或调用方处理。
 */
object RuleAskCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun showIfNeeded(
        ctx: Context,
        entries: List<RuleLearningManager.RuleLearnEntry>,
        onFinished: () -> Unit = {},
    ): Boolean {
        if (CapturePrefs.getRuleLearnMode(ctx) != CapturePrefs.RULE_LEARN_ASK) return false
        val eligible = entries.filter { entry ->
            val note = entry.note.trim()
            val category = entry.categoryName.trim()
            if (note.isEmpty() || category.isEmpty()) return@filter false
            if (entry.billType != 0 && entry.billType != 1) return@filter false
            val leaf = category.substringAfterLast(" - ").substringAfterLast(" > ").trim()
            leaf.isNotEmpty() && leaf != "其他" && leaf != "差额调整"
        }
        if (eligible.isEmpty()) return false

        val title = if (eligible.size == 1) "记住这条规则？" else "记住这 ${eligible.size} 条规则？"
        val message = if (eligible.size == 1) {
            val entry = eligible.first()
            "以后「${entry.note.trim().take(30)}」自动归入「${entry.categoryName.trim()}」？"
        } else {
            eligible.take(8).joinToString("\n") { entry ->
                val leaf = entry.categoryName.substringAfterLast(" - ")
                    .substringAfterLast(" > ").trim()
                "· ${entry.note.trim().take(16)} → $leaf"
            } + if (eligible.size > 8) "\n… 还有 ${eligible.size - 8} 条" else ""
        }

        val themeCtx = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val dialog = AlertDialog.Builder(themeCtx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("记住") { d, _ ->
                d.dismiss()
                scope.launch {
                    runCatching {
                        RuleLearningManager.learnEntries(ctx, eligible)
                    }.onFailure {
                        Toast.makeText(ctx, "规则学习失败，已忽略", Toast.LENGTH_SHORT).show()
                    }
                }
                onFinished()
            }
            .setNegativeButton("不用") { d, _ ->
                d.dismiss()
                onFinished()
            }
            .setOnCancelListener { onFinished() }
            .create()

        if (ctx is Activity) {
            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.88f,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = true,
            )
        } else {
            OverlayDialogs.showOverlayCenterDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.88f,
                cancelOnTouchOutside = true,
                useSolidPanelBackground = true,
            )
        }
        return true
    }
}
