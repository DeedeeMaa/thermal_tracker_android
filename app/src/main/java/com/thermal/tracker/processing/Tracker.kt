package com.thermal.tracker.processing

import com.thermal.tracker.model.Hotspot
import com.thermal.tracker.model.Track
import com.thermal.tracker.model.TrackerConfig
import kotlin.math.hypot

/**
 * 轻量质心追踪器：速度预测 + 贪心距离匹配，为每个热区分配稳定 ID。
 * 对应 Python 版 tracker.py 的 CentroidTracker。
 */
class CentroidTracker(private val cfg: TrackerConfig) {

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 1

    /** 输入当前帧检测结果，输出稳定轨道列表。 */
    fun update(dets: List<Hotspot>): List<Track> {
        val usedTracks = HashSet<Int>()

        if (tracks.isNotEmpty()) {
            // 1) 预测所有轨道位置
            // 2) 计算所有 (轨道, 检测) 距离对，贪心匹配
            val pairs = ArrayList<Triple<Double, Int, Int>>()
            for ((tid, t) in tracks) {
                val p = t.predict()
                for ((i, d) in dets.withIndex()) {
                    val dist = hypot(p[0] - d.centroid[0], p[1] - d.centroid[1])
                    pairs.add(Triple(dist, tid, i))
                }
            }
            pairs.sortBy { it.first }

            val usedDets = HashSet<Int>()
            for ((dist, tid, i) in pairs) {
                if (dist > cfg.matchDist) break
                if (tid in usedTracks || i in usedDets) continue
                tracks[tid]?.update(dets[i])
                usedTracks.add(tid)
                usedDets.add(i)
            }

            // 3) 未匹配的检测 -> 新轨道
            for ((i, d) in dets.withIndex()) {
                if (i !in usedDets) newTrack(d)
            }
        } else {
            dets.forEach { newTrack(it) }
        }

        // 4) 未匹配的轨道 -> miss；超时删除
        val it = tracks.entries.iterator()
        while (it.hasNext()) {
            val (tid, t) = it.next()
            if (tid !in usedTracks) t.miss()
            if (!t.alive) it.remove()
        }

        return tracks.values.filter { it.stable }
    }

    private fun newTrack(det: Hotspot) {
        tracks[nextId] = Track(nextId, det, cfg)
        nextId++
    }
}
