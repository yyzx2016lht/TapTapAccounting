package com.taostudio.tapaccounting.tap.aosp

import android.hardware.Sensor
import com.taostudio.tapaccounting.tap.BaseTapRT
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Deque

/*
 * 取自 crDroid/AOSP 的 ColumbusService：
 *   src/org/protonaosp/columbus/sensors/TapRT.kt
 *
 * 与原文的差异（全部因为「HE 档不做推理」）：
 *   1. 去掉 `context` 构造参数——它只被用来 new TfClassifier；
 *   2. 去掉 init 里的 `tflite = TfClassifier(context)`；
 *   3. 去掉纯 ML 成员：addToFeatureVector / recognizeTapML / processAccAndKeySignal /
 *      updateML / frameAlignPeak / framePriorPeak / wasPeakApproaching；
 *   4. updateData 不再按 heuristicMode 分叉，恒定走 updateHeuristic。
 *
 * 启发式路径本身（checkDoubleTapTiming / processKeySignalHeuristic /
 * recognizeTapHeuristic / updateHeuristic / reset）**逐字保留**。
 *
 * 已知代价：原文的 `resampleAcc.results` 是每次访问都新建 Sample3C 的 get()，
 * 且 slope→lowpass→highpass 三层各返回一个新 Point3f，所以每个重采样点
 * 比现有 1C Key 链多分配数个临时对象。这里先保持原样，行为可对照；
 * 若要省这份开销，在 processKeySignalHeuristic 里把 results 取一次存局部变量即可。
 */
open class AospTapRT(val sizeWindowNs: Long) :
    AospEventIMURT(sizeWindowNs, 50, 50 * 6), BaseTapRT {

    private val minTimeGapNs: Long = 100000000L
    private val maxTimeGapNs: Long = 500000000L
    var result: Int = TapClass.Front.ordinal
    val positivePeakDetector: PeakDetector = PeakDetector()
    val negativePeakDetector: PeakDetector = PeakDetector()
    val timestampsBackTap: Deque<Long> = ArrayDeque()
    var wasPeakApproaching: Boolean = true

    init {
        lowpassAcc.para = 1f
        lowpassGyro.para = 1f
        highpassAcc.para = 0.05f
        highpassGyro.para = 0.05f
    }

    override fun checkDoubleTapTiming(timestamp: Long): Int {
        val timestampFirst = timestampsBackTap.iterator()
        while (timestampFirst.hasNext()) {
            if (timestamp - timestampFirst.next() > maxTimeGapNs) {
                timestampFirst.remove()
            }
        }

        if (timestampsBackTap.isEmpty()) {
            return 0
        }

        val timestampSecond = timestampsBackTap.iterator()
        while (timestampSecond.hasNext()) {
            if (timestampsBackTap.last() - timestampSecond.next() > minTimeGapNs) {
                timestampsBackTap.clear()
                return 2
            }
        }

        return 1
    }

    fun processKeySignalHeuristic() {
        var update: Point3f =
            highpassAcc.update(
                lowpassAcc.update(
                    slopeAcc.update(
                        resampleAcc.results.point,
                        2400000f / resampleAcc.interval.toFloat(),
                    )
                )
            )
        positivePeakDetector.update(update.z.toFloat(), resampleAcc.results.time)
        negativePeakDetector.update(-update.z.toFloat(), resampleAcc.results.time)

        accZs.add(update.z.toFloat())

        val interval: Int = (sizeWindowNs / resampleAcc.interval).toInt()
        while (accZs.size > interval) {
            accZs.removeFirst()
        }
        if (accZs.size == interval) {
            recognizeTapHeuristic()
        }
        if (result == TapClass.Back.ordinal) {
            timestampsBackTap.addLast(resampleAcc.results.time)
        }
    }

    fun recognizeTapHeuristic() {
        val positivePeakId = positivePeakDetector.peakId
        val negativePeakId = negativePeakDetector.peakId - positivePeakId

        if (positivePeakId == 4) {
            featureVector = ArrayList(accZs)
            result =
                (if (negativePeakId > 0 && negativePeakId < 3) TapClass.Back else TapClass.Others)
                    .ordinal
        }
    }

    override fun reset(clearFv: Boolean) {
        super.reset()
        if (clearFv) {
            featureVector.clear()
        } else {
            featureVector = arrayListOf<Float>()
            for (i in 0 until numberFeature) {
                featureVector.add(0f)
            }
        }
    }

    override fun updateData(
        type: Int,
        lastX: Float,
        lastY: Float,
        lastZ: Float,
        lastT: Long,
        interval: Long,
        isHeuristic: Boolean
    ) {
        result = TapClass.Others.ordinal
        // crDroid 原文在这里按 heuristicMode 分叉到 updateHeuristic / updateML。
        // 本内核只实现启发式路径（HE 不做推理），所以恒定走 updateHeuristic。
        updateHeuristic(type, lastX, lastY, lastZ, lastT, interval)
    }

    fun updateHeuristic(
        sensorType: Int,
        rawLastX: Float,
        rawLastY: Float,
        rawLastZ: Float,
        rawLastT: Long,
        interval: Long,
    ) {
        if (sensorType == Sensor.TYPE_GYROSCOPE) {
            return
        }
        if (0L == syncTime) {
            syncTime = rawLastT
            resampleAcc.init(rawLastX, rawLastY, rawLastZ, rawLastT, interval)
            resampleAcc.resampledLastT = syncTime
            slopeAcc.init(resampleAcc.results.point)
            return
        }

        while (resampleAcc.update(rawLastX, rawLastY, rawLastZ, rawLastT)) {
            processKeySignalHeuristic()
        }
    }
}
