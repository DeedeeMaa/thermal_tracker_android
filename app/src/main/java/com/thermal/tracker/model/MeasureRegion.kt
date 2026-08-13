package com.thermal.tracker.model

/**
 * 手动测温框（对应 PC 版 gui_worker 的 measure_regions）。
 * 坐标基于当前预览帧图像坐标。最多 20 个。
 */
data class MeasureRegion(
    val id: Int,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** 框内平均温（真实矩阵），无矩阵时为 null。 */
    val meanC: Double? = null,
    /** 框内最高温。 */
    val maxC: Double? = null,
    /** 框内最低温。 */
    val minC: Double? = null,
    /** 框内灰度均值（回退显示用）。 */
    val meanG: Double? = null,
)
