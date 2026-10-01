package com.taostudio.tapaccounting

import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/** 账单收支语义色的代码侧出口，取值以 colors.xml 为准。 */
object BillColors {

    @get:ColorInt
    val expense: Int
        get() = ContextCompat.getColor(TapApplication.app(), R.color.expense_color)

    @get:ColorInt
    val income: Int
        get() = ContextCompat.getColor(TapApplication.app(), R.color.income_color)
}
