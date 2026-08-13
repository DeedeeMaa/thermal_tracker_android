package com.thermal.tracker.processing

import com.thermal.tracker.model.CalibrationConfig

/**
 * 温度标定：把热成像灰度(0~255)映射为摄氏度。
 * 对应 Python 版 temp_calib.py。
 */
class TempCalibration private constructor(
    val slope: Double,
    val intercept: Double,
) {
    companion object {
        /** 从 CalibrationConfig 构造；启用两点校准时用两点拟合。 */
        fun fromConfig(cfg: CalibrationConfig): TempCalibration {
            val r1i = cfg.ref1Intensity.toDouble()
            val r2i = cfg.ref2Intensity.toDouble()
            val r1c = cfg.ref1Celsius
            val r2c = cfg.ref2Celsius
            if (cfg.useTwoPoint && r2i != r1i) {
                val s = (r2c - r1c) / (r2i - r1i)
                return TempCalibration(s, r1c - s * r1i)
            }
            return TempCalibration((cfg.tmax - cfg.tmin) / 255.0, cfg.tmin)
        }
    }

    /** 单个灰度值 -> ℃。 */
    fun toCelsius(intensity: Double): Double = slope * intensity + intercept

    /** ℃ -> 灰度。 */
    fun intensityFor(celsius: Double): Int {
        if (slope == 0.0) return 128
        return ((celsius - intercept) / slope).toInt().let { if (it < 0) 0 else if (it > 255) 255 else it }
    }

    /** 返回 (最低温, 最高温) 摄氏度。 */
    fun range(): Pair<Double, Double> = toCelsius(0.0) to toCelsius(255.0)
}
