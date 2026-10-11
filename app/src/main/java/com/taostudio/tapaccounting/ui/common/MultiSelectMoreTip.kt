package com.taostudio.tapaccounting.ui.common

import android.content.Context
import com.taostudio.tapaccounting.Prefs

/**
 * 资产详情多选「更多」的新手提示：小气泡 + 红点。
 * 点过一次「更多」立刻关掉；没点过则最多出现 [MAX_SHOWS] 次。
 */
object MultiSelectMoreTip {
    const val MAX_SHOWS = 3
    private const val DISMISSED = 99

    fun shouldShow(ctx: Context): Boolean =
        Prefs.getMultiSelectMoreTipCount(ctx) < MAX_SHOWS

    fun onEnteredMultiSelect(ctx: Context) {
        val count = Prefs.getMultiSelectMoreTipCount(ctx)
        if (count < MAX_SHOWS) {
            Prefs.setMultiSelectMoreTipCount(ctx, count + 1)
        }
    }

    fun dismiss(ctx: Context) {
        Prefs.setMultiSelectMoreTipCount(ctx, DISMISSED)
    }
}
