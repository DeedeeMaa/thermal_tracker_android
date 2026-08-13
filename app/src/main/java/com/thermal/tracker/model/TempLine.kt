package com.thermal.tracker.model

/**
 * 温度线上一个采样点（对应 PC 版 gui_worker 的 _line_stats 采样点）。
 */
data class TempLinePoint(
    val x: Int,
    val y: Int,
    /** 该点温度（真实矩阵），无矩阵时为 null。 */
    val temp: Double? = null,
    /** 该点灰度（回退显示用）。 */
    val gray: Int? = null,
)

/**
 * 温度线（对应 PC 版 gui_worker 的 temp_lines）。
 * 坐标基于当前预览帧图像坐标。最多 4 条，可水平/竖直/斜线。
 */
data class TempLine(
    val id: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val points: List<TempLinePoint> = emptyList(),
)
