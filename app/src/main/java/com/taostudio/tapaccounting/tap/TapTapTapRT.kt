package com.taostudio.tapaccounting.tap

import android.os.SystemClock

class TapTapTapRT(
    sizeWindowNs: Long,
    private val isTripleTapEnabled: Boolean,
    private val sensitivity: Float,
    classifier: TfClassifier
) : TapRT(sizeWindowNs) {

    companion object {
        const val mMaxTimeGapTripleNs = 750_000_000L
    }

    init {
        _tflite = classifier
    }

    override fun checkDoubleTapTiming(timestamp: Long): Int {
        if (!isTripleTapEnabled) {
            return super.checkDoubleTapTiming(timestamp)
        }

        val firstPassIterator = _tBackTapTimestamps.iterator()
        while (firstPassIterator.hasNext()) {
            val pastTimestamp = firstPassIterator.next()
            if (timestamp - pastTimestamp <= mMaxTimeGapTripleNs) {
                continue
            }
            firstPassIterator.remove()
        }

        if (_tBackTapTimestamps.isEmpty()) {
            return 0
        }

        // P2-2: 按相邻间隔计数，避免把非连续击打叠成三击
        var tapCount = 1
        val timestamps = _tBackTapTimestamps.toList()
        for (i in 1 until timestamps.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            if (gap > mMinTimeGapNs && gap <= mMaxTimeGapTripleNs) {
                tapCount++
            } else {
                tapCount = 1
            }
        }

        val timeNow = SystemClock.elapsedRealtimeNanos()
        if (tapCount >= 3) {
            _tBackTapTimestamps.clear()
            return 3
        }
        if (timeNow.minus(_tBackTapTimestamps.first()) > mMaxTimeGapTripleNs) {
            _tBackTapTimestamps.clear()
            // P2-2: 超时且仅 1 击，不得当作双击
            return if (tapCount >= 2) 2 else 0
        }

        return 1
    }

    override fun reset(justClearFv: Boolean) {
        getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
        super.reset(justClearFv)
    }
}

