package com.thermal.tracker.model

/**
 * 追踪轨道（对应 Python 版 tracker.py 的 Track）。
 * 速度外推预测 + 贪心匹配，稳定命中后获得唯一 ID。
 */
class Track(
    val id: Int,
    det: Hotspot,
    private val cfg: TrackerConfig,
) {
    var bbox: BBox = det.bbox
    var centroid: DoubleArray = det.centroid
    var peak: Int = det.peak
    var mean: Double = det.mean
    var peakC: Double? = det.peakC
    var meanC: Double? = det.meanC
    var area: Double = det.area
    var hits: Int = 1
    var misses: Int = 0
    var velX: Double = 0.0
    var velY: Double = 0.0
    val history = mutableListOf<DoubleArray>(doubleArrayOf(centroid[0], centroid[1]))

    /** 按速度外推预测下一帧位置。 */
    fun predict(): DoubleArray = doubleArrayOf(centroid[0] + velX, centroid[1] + velY)

    fun update(det: Hotspot) {
        val p = predict()
        val a = cfg.velAlpha
        velX = a * velX + (1 - a) * (det.centroid[0] - p[0])
        velY = a * velY + (1 - a) * (det.centroid[1] - p[1])
        centroid = det.centroid
        bbox = det.bbox
        peak = det.peak
        mean = det.mean
        peakC = det.peakC
        meanC = det.meanC
        area = det.area
        hits++
        misses = 0
        history.add(doubleArrayOf(centroid[0], centroid[1]))
        while (history.size > cfg.historyLen) history.removeAt(0)
    }

    fun miss() {
        misses++
    }

    val alive: Boolean get() = misses <= cfg.maxMiss
    val stable: Boolean get() = hits >= cfg.minHits
}
