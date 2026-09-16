package com.taostudio.tapaccounting.ui.main.home

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Paint
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.utils.Utils
import com.github.mikephil.charting.utils.ViewPortHandler
import kotlin.math.min

class RoundedBarChartRenderer(
    chart: BarChart,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : BarChartRenderer(chart, animator, viewPortHandler) {

    /** If true, draw fully rounded bars (top+bottom). If false, only top corners rounded. */
    var fullRound: Boolean = false

    /**
     * 密集柱模式（最近15日）下启用：柱顶金额按实际文本宽度做防重叠绘制。
     * 从左到右逐个测量标签宽度，与上一个已绘制标签间隔不足或超出绘图区时自动跳过，
     * 保证任意屏宽/字号缩放下金额都不会互相压盖。
     */
    var denseValueLabels: Boolean = false

    private val barRect = RectF()
    private val cornerRadiusPx = Utils.convertDpToPixel(6f)
    private val minBarHeightPx = Utils.convertDpToPixel(3f)

    override fun drawDataSet(c: Canvas, dataSet: IBarDataSet, index: Int) {
        val trans = mChart.getTransformer(dataSet.axisDependency)
        val buffer = mBarBuffers[index]
        mBarBorderPaint.color = dataSet.barBorderColor
        mBarBorderPaint.strokeWidth = Utils.convertDpToPixel(dataSet.barBorderWidth)

        buffer.setPhases(mAnimator.phaseX, mAnimator.phaseY)
        buffer.setDataSet(index)
        buffer.setInverted(mChart.isInverted(dataSet.axisDependency))
        buffer.setBarWidth(mChart.barData.barWidth)
        buffer.feed(dataSet)
        trans.pointValuesToPixel(buffer.buffer)

        val isSingleColor = dataSet.colors.size == 1
        if (isSingleColor) {
            mRenderPaint.color = dataSet.color
        }

        var j = 0
        while (j < buffer.size()) {
            val left = buffer.buffer[j]
            val top = buffer.buffer[j + 1]
            val right = buffer.buffer[j + 2]
            val bottom = buffer.buffer[j + 3]

            if (!mViewPortHandler.isInBoundsLeft(right)) {
                j += 4
                continue
            }
            if (!mViewPortHandler.isInBoundsRight(left)) break

            if (!isSingleColor) {
                mRenderPaint.color = dataSet.getColor(j / 4)
            }

            val entry = dataSet.getEntryForIndex(j / 4) as? BarEntry
            if (entry == null || entry.y <= 0f) {
                j += 4
                continue
            }

            val rawHeight = bottom - top
            if (rawHeight <= 0f) {
                j += 4
                continue
            }

            barRect.set(left, top, right, bottom)
            if (barRect.height() < minBarHeightPx) {
                barRect.top = barRect.bottom - minBarHeightPx
            }

            val radius = min(cornerRadiusPx, min(barRect.width() / 2f, barRect.height() / 2f))

            // Create per-corner radii depending on fullRound flag.
            val radii = if (fullRound) {
                floatArrayOf(
                    radius, radius, // Top-left
                    radius, radius, // Top-right
                    radius, radius, // Bottom-right
                    radius, radius  // Bottom-left
                )
            } else {
                floatArrayOf(
                    radius, radius, // Top-left
                    radius, radius, // Top-right
                    0f, 0f,         // Bottom-right
                    0f, 0f          // Bottom-left
                )
            }

            val path = Path().apply {
                reset()
                addRoundRect(barRect, radii, Path.Direction.CW)
            }

            // Draw filled bar
            c.drawPath(path, mRenderPaint)

            // Draw border if needed
            if (dataSet.barBorderWidth > 0f) {
                c.drawPath(path, mBarBorderPaint)
            }

            j += 4
        }
    }

    /**
     * 复刻 MPAndroidChart v3.1.0 BarChartRenderer#drawValues 的非堆叠柱分支，
     * 在此基础上增加"标签防重叠"：绘制前用当前画笔测量文本宽度，
     * 与上一个已绘制标签的水平间距不足（<2dp）、或超出绘图区左右边界时跳过该标签。
     * 7日/本周等非密集模式直接走 super，绘制结果与库默认完全一致。
     */
    override fun drawValues(c: Canvas) {
        if (!denseValueLabels) {
            super.drawValues(c)
            return
        }
        val barData = mChart.barData ?: return
        if (barData.dataSetCount == 0) return
        if (barData.dataSets.any { it.isStacked }) {
            // 本卡片不使用堆叠柱；若未来出现则回退库默认逻辑，保证兼容。
            super.drawValues(c)
            return
        }

        val valueOffsetPlus = Utils.convertDpToPixel(4.5f)
        val labelGap = Utils.convertDpToPixel(2f)
        val edgeTolerance = Utils.convertDpToPixel(2f)
        val drawAbove = mChart.isDrawValueAboveBarEnabled
        val contentLeft = mViewPortHandler.contentLeft()
        val contentRight = mViewPortHandler.contentRight()

        for (i in 0 until barData.dataSetCount) {
            val dataSet = barData.dataSets[i]
            if (!dataSet.isDrawValuesEnabled && !dataSet.isDrawIconsEnabled) continue

            applyValueTextStyle(dataSet)
            mValuePaint.textAlign = Paint.Align.CENTER

            val isInverted = mChart.isInverted(dataSet.axisDependency)
            val valueTextHeight = Utils.calcTextHeight(mValuePaint, "8")
            var posOffset = if (drawAbove) -valueOffsetPlus else valueTextHeight + valueOffsetPlus
            var negOffset = if (drawAbove) valueTextHeight + valueOffsetPlus else -valueOffsetPlus
            if (isInverted) {
                posOffset = -posOffset - valueTextHeight
                negOffset = -negOffset - valueTextHeight
            }

            val buffer = mBarBuffers[i]
            val phaseX = mAnimator.phaseX
            var lastLabelRight = Float.NEGATIVE_INFINITY

            var j = 0
            while (j < buffer.buffer.size * phaseX) {
                val x = (buffer.buffer[j] + buffer.buffer[j + 2]) / 2f

                if (!mViewPortHandler.isInBoundsRight(x)) break
                if (!mViewPortHandler.isInBoundsY(buffer.buffer[j + 1]) || !mViewPortHandler.isInBoundsLeft(x)) {
                    j += 4
                    continue
                }

                val entry = dataSet.getEntryForIndex(j / 4) as? BarEntry
                if (entry != null && dataSet.isDrawValuesEnabled) {
                    val label = dataSet.valueFormatter.getBarLabel(entry)
                    val halfWidth = mValuePaint.measureText(label) / 2f
                    val left = x - halfWidth
                    val right = x + halfWidth
                    if (label.isNotEmpty() && entry.y > 0f &&
                        left > lastLabelRight + labelGap &&
                        left >= contentLeft - edgeTolerance &&
                        right <= contentRight + edgeTolerance
                    ) {
                        lastLabelRight = right
                        mValuePaint.color = dataSet.getValueTextColor(j / 4)
                        val y = if (entry.y >= 0f) {
                            buffer.buffer[j + 1] + posOffset
                        } else {
                            buffer.buffer[j + 3] + negOffset
                        }
                        c.drawText(label, x, y, mValuePaint)
                    }
                }
                j += 4
            }
        }
    }
}

