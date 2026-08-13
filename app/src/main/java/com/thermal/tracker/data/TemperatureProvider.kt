package com.thermal.tracker.data

import com.thermal.tracker.hik.ThermalSdk
import com.thermal.tracker.model.BBox
import com.thermal.tracker.processing.TempCalibration
import org.opencv.core.Mat

/**
 * 温度提供器：
 *  - SDK 可用时，周期性抓拍全屏温度矩阵，热区温度直接取自矩阵（真实测温）；
 *  - SDK 不可用时，回退到灰度标定（TempCalibration）。
 *
 * 对应 Python 版 README 中「真实测温 + 标定回退」的策略。
 */
class TemperatureProvider(
    private val sdk: ThermalSdk?,
    private val calib: TempCalibration?,
    private val captureIntervalMs: Long = 2000L,
) {
    private var matrix: FloatArray? = null
    private var lastCapture = 0L

    val matrixWidth: Int get() = sdk?.tempMatrixWidth ?: 0
    val matrixHeight: Int get() = sdk?.tempMatrixHeight ?: 0

    /** 每帧调用：按间隔抓拍温度矩阵。 */
    fun onFrame(nowMs: Long) {
        val s = sdk ?: return
        if (nowMs - lastCapture >= captureIntervalMs) {
            s.captureTemperatureMatrix()?.let {
                matrix = it
                lastCapture = nowMs
            }
        }
    }

    fun hasMatrix(): Boolean = matrix != null

    fun currentMatrix(): FloatArray? = matrix

    /**
     * 热区温度：优先矩阵，其次标定。
     * @return (峰值℃, 均值℃)
     */
    fun regionTemps(bbox: BBox, frameW: Int, frameH: Int, grayPeak: Int, grayMean: Double): Pair<Double?, Double?> {
        val m = matrix
        val mw = matrixWidth
        val mh = matrixHeight
        if (m != null && mw > 0 && mh > 0) {
            val x0 = (bbox.x.toLong() * mw / frameW).toInt().coerceIn(0, mw - 1)
            val x1 = (((bbox.x + bbox.w).toLong() * mw / frameW) - 1).toInt().coerceIn(0, mw - 1)
            val y0 = (bbox.y.toLong() * mh / frameH).toInt().coerceIn(0, mh - 1)
            val y1 = (((bbox.y + bbox.h).toLong() * mh / frameH) - 1).toInt().coerceIn(0, mh - 1)
            var max = Double.NEGATIVE_INFINITY
            var sum = 0.0
            var n = 0
            for (r in y0..y1) {
                var base = r * mw
                for (c in x0..x1) {
                    val v = m[base + c].toDouble()
                    if (v > max) max = v
                    sum += v
                    n++
                }
            }
            if (n > 0) return max to sum / n
            return null to null
        }
        // 回退到标定
        return if (calib != null) {
            calib.toCelsius(grayPeak.toDouble()) to calib.toCelsius(grayMean)
        } else {
            null to null
        }
    }
}
