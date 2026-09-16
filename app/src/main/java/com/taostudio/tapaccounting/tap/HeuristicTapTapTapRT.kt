package com.taostudio.tapaccounting.tap

import android.os.SystemClock

class HeuristicTapTapTapRT(
    sizeWindowNs: Long,
    private val isTripleTapEnabled: Boolean
) : TapRT(sizeWindowNs) {

    companion object {
        private const val mMaxTimeGapTripleNs = 750_000_000L
    }

    override fun checkDoubleTapTiming(timestamp: Long): Int {
        if (!isTripleTapEnabled) {
            return super.checkDoubleTapTiming(timestamp)
        }

        val firstPassIterator = _tBackTapTimestamps.iterator()
        while (firstPassIterator.hasNext()) {
            val pastTimestamp = firstPassIterator.next()
            if (timestamp - pastTimestamp <= mMaxTimeGapTripleNs) continue
            firstPassIterator.remove()
        }
        if (_tBackTapTimestamps.isEmpty()) return 0

        var tapCount = 0
        val secondPassIterator = _tBackTapTimestamps.iterator()
        val timeNow = SystemClock.elapsedRealtimeNanos()
        while (secondPassIterator.hasNext()) {
            val pastTimestamp = secondPassIterator.next()
            if (_tBackTapTimestamps.last() - pastTimestamp <= mMinTimeGapNs) continue
            tapCount++
        }

        if (tapCount >= 3 || timeNow - _tBackTapTimestamps.first() > mMaxTimeGapTripleNs) {
            _tBackTapTimestamps.clear()
            if (tapCount == 1) return 2
            if (tapCount >= 2) return 3
        }
        return 1
    }
}

