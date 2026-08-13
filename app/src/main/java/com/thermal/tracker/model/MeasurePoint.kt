package com.thermal.tracker.model

/**
 * 单点测温标记：最多 10 个点，每个点实时显示温度，
 * 并记录各自的温度时间线（最长 30 分钟）。
 */
data class MeasurePoint(
    /** 点编号（1 起；删除后其它点编号保持稳定，用于时间线/导出列对应）。 */
    val id: Int,
    val x: Int,
    val y: Int,
    /** 该点当前温度（℃），无矩阵时为 null。 */
    val tempC: Double? = null,
)
