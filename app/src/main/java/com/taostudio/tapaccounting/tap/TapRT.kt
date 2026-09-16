package com.taostudio.tapaccounting.tap

import java.util.*

enum class TapClass {
    Front, Back, Left, Right, Top, Bottom, Others
}

open class TapRT(
    val sizeWindowNs: Long,
    private val minTimeGapNs: Long = mMinTimeGapNs,
    private val maxTimeGapNs: Long = mMaxTimeGapNs
) : EventIMURT(), BaseTapRT {

    companion object {
        const val mMinTimeGapNs = 100000000L
        const val mMaxTimeGapNs = 500000000L
        const val HEURISTIC_MIN_TIME_GAP_NS = 180000000L
        private const val mFrameAlignPeak = 12
    }

    private val _lowpassKey = Lowpass1C()
    private val _highpassKey = Highpass1C()
    private val _peakDetectorPositive = PeakDetector()
    private val _peakDetectorNegative = PeakDetector()
    protected val _tBackTapTimestamps = ArrayDeque<Long>()
    private var _wasPeakApproaching = true
    private var _result = 0
    protected var _tflite = TfClassifier()

    fun setClassifier(classifier: TfClassifier) {
        // 换分类器前先切断旧解释器的推理入口，但原生释放要回到传感器线程上做，
        // 这里只做"交接"；正常流程下分类器在整个会话内复用，不会走到这一步。
        if (_tflite !== classifier) {
            (_tflite as? TapTfClassifier)?.handOff()
        }
        _tflite = classifier
    }

    /**
     * 释放本实例持有的原生解释器。
     *
     * [sensorHandler] 是解释器真正的创建/调用线程。原生解释器只能在创建它的线程上关闭，
     * 否则一旦与正在执行的 `Interpreter.run()` 并发就会 SIGSEGV 杀掉整个进程。
     * [fallbackHandler] 仅作为目标线程可能已经退出时的兜底。
     */
    fun releaseClassifier(sensorHandler: android.os.Handler?, fallbackHandler: android.os.Handler?) {
        val classifier = _tflite as? TapTfClassifier ?: return
        _tflite = TfClassifier()
        classifier.closeOn(sensorHandler, fallbackHandler)
    }

    init {
        _sizeWindowNs = sizeWindowNs
        _sizeFeatureWindow = 50
        _numberFeature = 300
        _lowpassAcc.setPara(1f)
        _lowpassGyro.setPara(1f)
        _highpassAcc.setPara(0.05f)
        _highpassGyro.setPara(0.05f)
        _lowpassKey.setPara(0.2f)
        _highpassKey.setPara(0.2f)
    }

    private fun addToFeatureVector(vector: ArrayDeque<Float>, size: Int, start: Int) {
        if (vector.isEmpty() || start >= _fv.size) return
        var startIndex = start
        val iterator = vector.iterator()
        var index = 0
        while (iterator.hasNext()) {
            if (index < size) {
                iterator.next()
            } else {
                if (index >= _sizeFeatureWindow + size) {
                    return
                }
                if (startIndex >= _fv.size) return
                val featureVector = _fv
                val next = iterator.next()
                featureVector[startIndex] = next
                startIndex++
            }
            index++
        }
    }

    override fun checkDoubleTapTiming(timestamp: Long): Int {
        val v0 = _tBackTapTimestamps.iterator()
        while (v0.hasNext()) {
            val v1 = v0.next()
            if (timestamp - v1 <= maxTimeGapNs) {
                continue
            }
            v0.remove()
        }
        if (_tBackTapTimestamps.isEmpty()) {
            return 0
        }

        val v6 = _tBackTapTimestamps.iterator()
        while (v6.hasNext()) {
            val v0_1 = _tBackTapTimestamps.last
            val v7 = v6.next()
            if (v0_1 - v7 <= minTimeGapNs) {
                continue
            }

            _tBackTapTimestamps.clear()
            return 2
        }

        return 1
    }

    fun getHighpassKey(): Highpass1C {
        return _highpassKey
    }

    fun getLowpassKey(): Lowpass1C {
        return _lowpassKey
    }

    fun getNegativePeakDetection(): PeakDetector {
        return _peakDetectorNegative
    }

    fun getPositivePeakDetector(): PeakDetector {
        return _peakDetectorPositive
    }

    private fun processAccAndKeySignal() {
        val resamplePoint = _resampleAcc.results.point
        val resampleInterval = 2500000.0f / _resampleAcc.getInterval()
        val slopeAcc = _slopeAcc.update(resamplePoint, resampleInterval)
        val lowpassAcc = _lowpassAcc.update(slopeAcc)
        val highpassAcc = _highpassAcc.update(lowpassAcc)
        _xsAcc.add(highpassAcc.x)
        _ysAcc.add(highpassAcc.y)
        _zsAcc.add(highpassAcc.z)
        val interval = _resampleAcc.getInterval()
        val sizeWindow = (sizeWindowNs / interval).toInt()
        while (_xsAcc.size > sizeWindow) {
            if (_xsAcc.isNotEmpty()) _xsAcc.removeFirst()
            if (_ysAcc.isNotEmpty()) _ysAcc.removeFirst()
            if (_zsAcc.isNotEmpty()) _zsAcc.removeFirst()
        }

        val lowpassKey = _lowpassKey.update(slopeAcc.z)
        val highpassKey = _highpassKey.update(lowpassKey)
        _peakDetectorPositive.update(highpassKey)
    }

    private fun processKeySignalHeursitic(timestamp: Long) {
        val resamplePoint = _resampleAcc.results.point
        val scaledInterval = 2500000.0f / _resampleAcc.getInterval().toFloat()
        val slopeAcc = _slopeAcc.update(resamplePoint, scaledInterval)
        val lowpassKey = _lowpassKey.update(slopeAcc.z)
        val highpassKey = _highpassKey.update(lowpassKey)
        _peakDetectorPositive.update(highpassKey)
        _peakDetectorNegative.update(-highpassKey)
        _zsAcc.add(highpassKey)
        val resampleInterval = _resampleAcc.getInterval()
        val scaledResampleInverval = (sizeWindowNs / resampleInterval).toInt()
        while (_zsAcc.size > scaledResampleInverval) {
            _zsAcc.removeFirst()
        }

        if (_zsAcc.size == scaledResampleInverval) {
            recognizeTapHeuristic()
        }

        if (_result == TapClass.Back.ordinal) {
            _tBackTapTimestamps.addLast(timestamp)
        }
    }

    private fun recognizeTapHeuristic() {
        val positiveIdMajorPeak = _peakDetectorPositive.getIdMajorPeak()
        val negativeIdMajorPeak = _peakDetectorNegative.getIdMajorPeak() - positiveIdMajorPeak
        if (positiveIdMajorPeak == 4) {
            _fv = ArrayList(_zsAcc)
            _result = (if (negativeIdMajorPeak <= 0 || negativeIdMajorPeak >= 3) TapClass.Others else TapClass.Back).ordinal
        }
    }

    fun recognizeTapML() {
        val resampleInterval = _resampleAcc.getInterval()
        val resampleT = ((_resampleAcc.results.t - _resampleGyro.results.t) / resampleInterval).toInt()
        val majorPeakId = _peakDetectorPositive.getIdMajorPeak()
        if (majorPeakId > mFrameAlignPeak) {
            _wasPeakApproaching = true
        }

        val adjustedMajorPeakId = majorPeakId - 6
        val adjustedT = adjustedMajorPeakId - resampleT
        val zAccSize = _zsAcc.size
        if (adjustedMajorPeakId >= 0 && adjustedT >= 0 && adjustedMajorPeakId + _sizeFeatureWindow < zAccSize && _sizeFeatureWindow + adjustedT < zAccSize && _wasPeakApproaching && majorPeakId <= mFrameAlignPeak) {
            _wasPeakApproaching = false
            addToFeatureVector(_xsAcc, adjustedMajorPeakId, 0)
            addToFeatureVector(_ysAcc, adjustedMajorPeakId, _sizeFeatureWindow)
            addToFeatureVector(_zsAcc, adjustedMajorPeakId, _sizeFeatureWindow * 2)
            addToFeatureVector(_xsGyro, adjustedT, _sizeFeatureWindow * 3)
            addToFeatureVector(_ysGyro, adjustedT, _sizeFeatureWindow * 4)
            addToFeatureVector(_zsGyro, adjustedT, _sizeFeatureWindow * 5)
            val featureVector = _fv
            scaleGyroData(featureVector, 10.0f)
            _fv = featureVector
            val probs = _tflite.predict(featureVector, 7)
            if (probs.isNotEmpty()) {
                // 分类器不可用时 predict 返回空列表，必须判空，
                // 否则 first() 会抛 NoSuchElementException 把整个手势链路打断
                _result = Util.getMaxId(probs.first())
            }
        }
    }

    override fun reset(justClearFv: Boolean) {
        super.reset()
        if (justClearFv) {
            _fv.clear()
        } else {
            _fv = ArrayList(_numberFeature)
            for (i in 0 until _numberFeature) {
                _fv.add(0f)
            }
        }
    }

    override fun updateData(type: Int, lastX: Float, lastY: Float, lastZ: Float, lastT: Long, interval: Long, isHeuristic: Boolean) {
        _result = TapClass.Others.ordinal
        if (isHeuristic) {
            updateHeuristic(type, lastX, lastY, lastZ, lastT, interval)
        } else {
            updateML(type, lastX, lastY, lastZ, lastT, interval)
        }
    }

    private fun updateHeuristic(type: Int, lastX: Float, lastY: Float, lastZ: Float, lastT: Long, interval: Long) {
        if (type != 4) {
            if (0L == _syncTime) {
                _syncTime = lastT
                _resampleAcc.init(lastX, lastY, lastZ, lastT, interval)
                _resampleAcc.setSyncTime(_syncTime)
                _slopeAcc.init(_resampleAcc.results.point)
                _lowpassKey.init(0.0f)
                _highpassKey.init(0.0f)
                return
            }
            while (_resampleAcc.update(lastX, lastY, lastZ, lastT)) {
                processKeySignalHeursitic(lastT)
            }
        }
    }

    private fun updateML(type: Int, lastX: Float, lastY: Float, lastZ: Float, lastT: Long, interval: Long) {
        when (type) {
            1 -> {
                _gotAcc = true
                if (_syncTime == 0L) {
                    _resampleAcc.init(lastX, lastY, lastZ, lastT, interval)
                }

                if (!_gotGyro) {
                    return
                }
            }
            4 -> {
                _gotGyro = true
                if (_syncTime == 0L) {
                    _resampleGyro.init(lastX, lastY, lastZ, lastT, interval)
                }

                if (!_gotAcc) {
                    return
                }
            }
        }

        if (0L == _syncTime) {
            _syncTime = lastT
            _resampleAcc.setSyncTime(lastT)
            _resampleGyro.setSyncTime(_syncTime)
            _slopeAcc.init(_resampleAcc.results.point)
            _slopeGyro.init(_resampleGyro.results.point)
            _lowpassAcc.init(Point3f(0.0f, 0.0f, 0.0f))
            _lowpassGyro.init(Point3f(0.0f, 0.0f, 0.0f))
            _highpassAcc.init(Point3f(0.0f, 0.0f, 0.0f))
            _highpassGyro.init(Point3f(0.0f, 0.0f, 0.0f))
            _lowpassKey.init(0.0f)
            _highpassKey.init(0.0f)
            return
        }

        if (type == 1) {
            while (_resampleAcc.update(lastX, lastY, lastZ, lastT)) {
                processAccAndKeySignal()
            }
        } else if (type == 4) {
            while (_resampleGyro.update(lastX, lastY, lastZ, lastT)) {
                processGyro()
            }
        }

        recognizeTapML()
        if (_result == TapClass.Back.ordinal) {
            _tBackTapTimestamps.addLast(java.lang.Long.valueOf(lastT))
        }
    }
}

