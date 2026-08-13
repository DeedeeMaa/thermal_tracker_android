package com.thermal.tracker.model

/** 矩形框（像素坐标）。 */
data class BBox(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * 单帧检测出的热点（对应 Python 版 detection.py 的 det）。
 *
 * @param centroid 质心 [cx, cy]（浮点）
 * @param peakC / meanC 摄氏度（可为 null，表示未标定/无温度矩阵）
 */
data class Hotspot(
    val bbox: BBox,
    val centroid: DoubleArray,
    val area: Double,
    val peak: Int,
    val mean: Double,
    val peakC: Double?,
    val meanC: Double?,
)
