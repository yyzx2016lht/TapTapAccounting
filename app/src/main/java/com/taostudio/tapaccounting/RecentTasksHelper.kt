package com.taostudio.tapaccounting

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 最近任务卡片策略。
 *
 * 历史背景：这里原先提供"隐藏后台卡片"开关（`setExcludeFromRecents(true)`），
 * 假设是"卡片消失能少被系统清理"。实测结论相反——卡片是 ColorOS/Osense 判断
 * "用户还在使用"的输入之一，卡片被摘掉后进程进入"无任务"状态并反复被杀。
 *
 * 因此现在只做一件事：**无条件保证卡片存在**。这样即使用户在旧版本里开过
 * "隐藏后台卡片"，升级后打开一次 App 就会自动恢复。
 */
object RecentTasksHelper {
    fun ensureTaskVisible(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        try {
            activityManager.appTasks.forEach { task ->
                try {
                    task.setExcludeFromRecents(false)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
