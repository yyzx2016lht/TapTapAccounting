package com.taostudio.tapaccounting.ui.recurring

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import com.taostudio.tapaccounting.logic.RecurringBillingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 周期记账新发现提示：每次进程最多扫一次；扫描最近账单，把新识别为
 * SUGGESTED 且没通知过的 pattern 数量弹 Snackbar，点进去直达建议 Tab。
 *
 * 已通知 id 持久化在独立 SharedPreferences；每次顺手把已失效的 id 清掉，
 * 避免集合无限增长。
 */
object RecurringSuggestionNotice {
    private const val PREFS_NAME = "recurring_due_prompt_prefs"
    private const val KEY_NOTIFIED_IDS = "suggestion_notified_ids"

    private var scannedThisSession = false

    fun maybeShow(activity: AppCompatActivity, anchor: View?) {
        if (scannedThisSession || !Prefs.isRecurringAutoDetectEnabled(activity)) return
        scannedThisSession = true
        val appContext = activity.applicationContext
        activity.lifecycleScope.launch {
            val newIds = withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.getDatabase(appContext)
                    RecurringBillingService(db).scanRecentBills(
                        amountTolerance = Prefs.getRecurringDetectAmountTolerance(appContext)
                    )
                    val suggested = db.recurringPatternDao()
                        .getByStatus(RecurringStatus.SUGGESTED)
                        .map { it.id.toString() }
                        .toSet()
                    val notified = notifiedIds(appContext)
                    saveNotifiedIds(appContext, notified intersect suggested)
                    suggested - notified
                }.getOrDefault(emptySet())
            }
            if (newIds.isEmpty() || activity.isFinishing || activity.isDestroyed) return@launch
            saveNotifiedIds(appContext, notifiedIds(appContext) + newIds)
            Snackbar.make(
                activity.findViewById(android.R.id.content),
                activity.getString(R.string.recurring_suggestion_found_fmt, newIds.size),
                Snackbar.LENGTH_SHORT
            ).setAction(R.string.recurring_suggestion_view) {
                activity.startActivity(
                    Intent(activity, RecurringBillsActivity::class.java)
                        .putExtra(RecurringBillsActivity.EXTRA_OPEN_SUGGESTED, true)
                )
            }.apply {
                if (anchor != null && anchor.visibility == View.VISIBLE) {
                    setAnchorView(anchor)
                }
            }.show()
        }
    }

    private fun notifiedIds(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_NOTIFIED_IDS, emptySet())
            ?.toSet().orEmpty()
    }

    private fun saveNotifiedIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_NOTIFIED_IDS, ids).apply()
    }
}
