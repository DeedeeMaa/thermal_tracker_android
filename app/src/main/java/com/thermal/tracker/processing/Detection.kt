package com.thermal.tracker.processing

import com.thermal.tracker.model.BBox
import com.thermal.tracker.model.DetectionConfig
import com.thermal.tracker.model.Hotspot
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 热点检测：灰度化 -> 高斯模糊 -> 阈值分割 -> 形态学 -> 轮廓 -> 合并重叠。
 * 对应 Python 版 detection.py。
 */
object Detection {

    /** 兼容 RGBA / BGR / 灰度输入。 */
    fun toGray(frame: Mat?): Mat? {
        if (frame == null) return null
        return when (frame.channels()) {
            4 -> {
                val g = Mat()
                Imgproc.cvtColor(frame, g, Imgproc.COLOR_RGBA2GRAY)
                g
            }
            3 -> {
                val g = Mat()
                Imgproc.cvtColor(frame, g, Imgproc.COLOR_BGR2GRAY)
                g
            }
            else -> frame.clone() // 返回副本，避免外层 release 导致原 Mat 失效
        }
    }

    private fun iou(a: BBox, b: BBox): Double {
        val ix = maxOf(0, minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x))
        val iy = maxOf(0, minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y))
        val inter = ix.toDouble() * iy
        val union = a.w.toDouble() * a.h + b.w.toDouble() * b.h - inter
        return if (union > 0) inter / union else 0.0
    }

    /** 按面积降序，IoU 超阈值则丢弃较小的重叠热区。 */
    private fun mergeOverlap(dets: List<Hotspot>, iouThr: Double): List<Hotspot> {
        val sorted = dets.sortedByDescending { it.area }
        val merged = ArrayList<Hotspot>()
        for (d in sorted) {
            if (merged.any { iou(d.bbox, it.bbox) > iouThr }) continue
            merged.add(d)
        }
        return merged
    }

    /** 通过直方图计算灰度图某百分位的强度值（对应 np.percentile）。 */
    private fun percentile(gray: Mat, p: Double): Double {
        val hist = Mat()
        try {
            Imgproc.calcHist(
                listOf(gray),
                MatOfInt(0), Mat(), hist,
                MatOfInt(256), MatOfFloat(0f, 256f)
            )
            val total = gray.total().toDouble()
            var cum = 0.0
            for (i in 0 until 256) {
                cum += hist.get(i, 0)[0]
                if (cum >= total * p / 100.0) return i.toDouble()
            }
            return 255.0
        } finally {
            hist.release()
        }
    }

    /**
     * 输入灰度图，返回 (dets, mask, thresh)。
     */
    fun detectHotspots(
        gray: Mat?,
        cfg: DetectionConfig,
        calib: TempCalibration?,
    ): Triple<List<Hotspot>, Mat?, Double> {
        if (gray == null || gray.empty()) return Triple(emptyList(), null, 0.0)
        
        // 确保是单通道灰度图
        val workGray = if (gray.channels() > 1) {
            val g = Mat()
            Imgproc.cvtColor(gray, g, Imgproc.COLOR_BGR2GRAY)
            g
        } else {
            gray
        }

        val blur = Mat()
        Imgproc.GaussianBlur(workGray, blur, Size(cfg.blurKsize.toDouble(), cfg.blurKsize.toDouble()), 0.0)

        val th = if (cfg.threshMode == "fixed") {
            cfg.fixedThresh.toDouble()
        } else {
            maxOf(percentile(blur, cfg.percentile), cfg.minIntensity.toDouble())
        }

        val mask = Mat()
        Imgproc.threshold(blur, mask, th, 255.0, Imgproc.THRESH_BINARY)
        blur.release()

        val k = cfg.morphKsize
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)   // 去噪点
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)  // 填孔洞
        kernel.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val dets = ArrayList<Hotspot>()
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < cfg.minArea) continue
            
            val r = Imgproc.boundingRect(c)
            // 越界保护
            val x = r.x.coerceIn(0, gray.cols() - 1)
            val y = r.y.coerceIn(0, gray.rows() - 1)
            val w = r.width.coerceAtMost(gray.cols() - x)
            val h = r.height.coerceAtMost(gray.rows() - y)
            if (w <= 0 || h <= 0) continue

            val roi = workGray.submat(y, y + h, x, x + w)
            val peak = Core.minMaxLoc(roi).maxVal.toInt()
            val mean = Core.mean(roi).`val`[0]
            roi.release()

            val cx = r.x + r.width / 2.0
            val cy = r.y + r.height / 2.0
            dets.add(
                Hotspot(
                    bbox = BBox(r.x, r.y, r.width, r.height),
                    centroid = doubleArrayOf(cx, cy),
                    area = area,
                    peak = peak,
                    mean = mean,
                    peakC = calib?.toCelsius(peak.toDouble()),
                    meanC = calib?.toCelsius(mean),
                )
            )
            c.release()
        }

        if (workGray !== gray) workGray.release()

        return Triple(mergeOverlap(dets, cfg.mergeIou), mask, th)
    }
}
