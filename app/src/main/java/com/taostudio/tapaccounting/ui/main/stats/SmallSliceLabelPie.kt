package com.taostudio.tapaccounting.ui.main.stats

import android.graphics.Canvas
import android.graphics.Color
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.renderer.PieChartRenderer

internal const val PIE_MIN_LABEL_PERCENT = 2f

/**
 * 占比低于 [PIE_MIN_LABEL_PERCENT] 的扇区照常绘制，但不画外部引线和标签，避免小分类标签挤在一起。
 * MPAndroidChart 3.1.0 对每个扇区都会无条件画引线（颜色取 getColor），只能在绘制标签阶段把小扇区颜色置为透明。
 */
internal class SmallSliceLabelPieDataSet(
    entries: List<PieEntry>,
    private val total: Double,
) : PieDataSet(entries, "") {
    var drawingValues = false

    fun shouldShowLabel(entry: PieEntry): Boolean =
        total > 0 && entry.value / total * 100 >= PIE_MIN_LABEL_PERCENT

    override fun getEntryIndex(e: PieEntry?): Int = values.indexOf(e)

    override fun getColor(index: Int): Int =
        if (drawingValues && !shouldShowLabel(getEntryForIndex(index))) Color.TRANSPARENT
        else super.getColor(index)
}

internal fun PieChart.useSmallSliceLabelRenderer() {
    renderer = object : PieChartRenderer(this, animator, viewPortHandler) {
        override fun drawValues(c: Canvas) {
            val sets = mChart.data?.dataSets.orEmpty().filterIsInstance<SmallSliceLabelPieDataSet>()
            sets.forEach { it.drawingValues = true }
            try {
                super.drawValues(c)
            } finally {
                sets.forEach { it.drawingValues = false }
            }
        }
    }
    setMinAngleForSlices(3f)
}
