package com.taostudio.tapaccounting.tap.aosp

import java.util.ArrayDeque
import java.util.ArrayList

/*
 * 逐字取自 crDroid/AOSP 的 ColumbusService：
 *   src/org/protonaosp/columbus/sensors/EventIMURT.kt
 * 唯一改动：删掉 `var tflite: TfClassifier?` 字段。
 * HE 档不做推理，所以这套内核里根本不需要分类器——
 * 这样 HE 路径完全不接触 TFLite 解释器，也就没有并发释放那个崩溃面。
 */

open class AospEventIMURT(val _sizeWindowNs: Long, val sizeFeatureWindow: Int, val numberFeature: Int) {
    var featureVector: ArrayList<Float> = arrayListOf<Float>()
    val accXs: ArrayDeque<Float> = ArrayDeque<Float>()
    val accYs: ArrayDeque<Float> = ArrayDeque<Float>()
    val accZs: ArrayDeque<Float> = ArrayDeque<Float>()
    val gyroXs: ArrayDeque<Float> = ArrayDeque<Float>()
    val gyroYs: ArrayDeque<Float> = ArrayDeque<Float>()
    val gyroZs: ArrayDeque<Float> = ArrayDeque<Float>()
    var gotAcc: Boolean = false
    var gotGyro: Boolean = false
    var syncTime: Long = 0L
    val resampleAcc: Resample3C = Resample3C()
    val resampleGyro: Resample3C = Resample3C()
    val slopeAcc: Slope3C = Slope3C()
    val slopeGyro: Slope3C = Slope3C()
    val lowpassAcc: Lowpass3C = Lowpass3C()
    val lowpassGyro: Lowpass3C = Lowpass3C()
    val highpassAcc: Highpass3C = Highpass3C()
    val highpassGyro: Highpass3C = Highpass3C()

    fun processAcc() {
        val sample = resampleAcc.results
        var update: Point3f =
            highpassAcc.update(
                lowpassAcc.update(
                    slopeAcc.update(sample.point, 2400000f / resampleAcc.interval.toFloat())
                )
            )
        accXs.add(update.x.toFloat())
        accYs.add(update.y.toFloat())
        accZs.add(update.z.toFloat())
        val interval: Int = (_sizeWindowNs / resampleAcc.interval).toInt()
        while (accXs.size > interval) {
            accXs.removeFirst()
            accYs.removeFirst()
            accZs.removeFirst()
        }
    }

    fun processGyro() {
        val sample = resampleGyro.results
        var update: Point3f =
            highpassGyro.update(
                lowpassGyro.update(
                    slopeGyro.update(sample.point, 2400000f / resampleGyro.interval.toFloat())
                )
            )
        gyroXs.add(update.x.toFloat())
        gyroYs.add(update.y.toFloat())
        gyroZs.add(update.z.toFloat())
        val interval: Int = (_sizeWindowNs / resampleGyro.interval).toInt()
        while (gyroXs.size > interval) {
            gyroXs.removeFirst()
            gyroYs.removeFirst()
            gyroZs.removeFirst()
        }
    }

    fun reset() {
        accXs.clear()
        accYs.clear()
        accZs.clear()
        gyroXs.clear()
        gyroYs.clear()
        gyroZs.clear()
        gotAcc = false
        gotGyro = false
        syncTime = 0L
    }
}
