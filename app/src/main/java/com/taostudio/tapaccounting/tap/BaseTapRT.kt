package com.taostudio.tapaccounting.tap

import android.os.Handler

interface BaseTapRT {
    fun updateData(type: Int, lastX: Float, lastY: Float, lastZ: Float, lastT: Long, interval: Long, isHeuristic: Boolean)
    fun checkDoubleTapTiming(timestamp: Long): Int
    fun reset(justClearFv: Boolean)

    /**
     * 交还原生 TFLite 解释器。
     *
     * 只有 TapTap 血统那套（ML 用的 [TapRT]）持有一个 [TapTfClassifier]，需要把
     * `Interpreter.close()` 排回传感器线程队尾；AOSP 内核的启发式运行时**不带分类器**，
     * 所以这里给一个空实现，让 [TapDetector] 能统一处理两种运行时。
     */
    fun releaseClassifier(sensorHandler: Handler?, fallbackHandler: Handler?) {}
}
