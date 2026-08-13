package com.thermal.tracker.processing

import com.thermal.tracker.model.MeasurePoint
import com.thermal.tracker.model.MeasureRegion
import com.thermal.tracker.model.TempLine
import com.thermal.tracker.model.Track
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.hypot

/**
 * 伪彩色渲染与标注：铁虹(ironbow)/白热/Inferno/Jet + 色标 + 追踪框。
 * 对应 Python 版 visualizer.py。
 */
object Visualizer {

    // ---------------- 配色 ----------------

    /** 海康铁虹色 LUT（BGR, 256 项）。 */
    val ironbowLut: Mat by lazy {
        buildLut(
            arrayOf(
                intArrayOf(0, 0, 0), intArrayOf(0, 0, 139), intArrayOf(0, 0, 255),
                intArrayOf(0, 255, 255), intArrayOf(0, 255, 0), intArrayOf(255, 255, 0),
                intArrayOf(255, 128, 0), intArrayOf(255, 0, 0),
            )
        )
    }

    /** Siemens 风格 LUT（BGR）：黑 -> 蓝 -> 青 -> 白（西门子热像仪配色）。 */
    val siemensLut: Mat by lazy {
        buildLut(
            arrayOf(
                intArrayOf(0, 0, 0), intArrayOf(255, 0, 0), intArrayOf(255, 255, 0),
                intArrayOf(255, 255, 255),
            )
        )
    }

    /** 根据颜色断点（BGR）构建 256 项 LUT。 */
    private fun buildLut(stops: Array<IntArray>): Mat {
        val lut = Mat(256, 1, CvType.CV_8UC3)
        val buf = ByteArray(3)
        for (i in 0 until 256) {
            val t = i / 255.0 * (stops.size - 1)
            val idx = minOf(t.toInt(), stops.size - 2)
            val frac = t - idx
            val a = stops[idx]
            val b = stops[idx + 1]
            buf[0] = (a[0] + (b[0] - a[0]) * frac).toInt().toByte()   // B
            buf[1] = (a[1] + (b[1] - a[1]) * frac).toInt().toByte()   // G
            buf[2] = (a[2] + (b[2] - a[2]) * frac).toInt().toByte()   // R
            lut.put(i, 0, buf)
        }
        return lut
    }

    /** 铁虹色第 idx 个颜色（BGR 数组）。 */
    private fun ironbowColor(idx: Int): DoubleArray {
        return ironbowLut.get(idx.coerceIn(0, 255), 0) ?: doubleArrayOf(0.0, 0.0, 0.0)
    }

    /** 灰度图 -> 伪彩 BGR 图。 */
    fun applyPalette(gray: Mat, palette: String): Mat {
        val out = Mat()
        when (palette) {
            "whitehot" -> Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2BGR)
            "inferno" -> Imgproc.applyColorMap(gray, out, Imgproc.COLORMAP_INFERNO)
            "jet" -> Imgproc.applyColorMap(gray, out, Imgproc.COLORMAP_JET)
            "siemens" -> Imgproc.applyColorMap(gray, out, siemensLut)
            else -> {
                // 使用 applyColorMap 应用自定义 LUT (ironbow)
                // 这样可以支持 1 通道灰度 -> 3 通道伪彩的映射，避免 Core.LUT 的断言崩溃
                Imgproc.applyColorMap(gray, out, ironbowLut)
            }
        }
        return out
    }

    // ---------------- 绘制 ----------------

    /** 细字清晰文字：不加描边，1px 粗细（Hershey 不支持中文/°）。 */
    private fun drawText(img: Mat, text: String, org: Point, size: Double = 0.4, color: Scalar) {
        if (text.isEmpty()) return
        Imgproc.putText(img, text, org, Imgproc.FONT_HERSHEY_SIMPLEX, size, color, 1, Imgproc.LINE_AA, false)
    }

    /** 右侧色标条 + 阈值线 + 温度刻度。 */
    fun drawColorbar(img: Mat, th: Double, tempRange: Pair<Double, Double>?, calib: TempCalibration?) {
        val h = img.rows()
        val w = img.cols()
        if (h <= 0 || w <= 0) return

        val barW = 22
        val barX = w - barW - 8
        val barY0 = 46.0
        val barY1 = (h - 46).toDouble()

        // 铁虹渐变条（上热下冷）
        for (i in 0 until 256) {
            val y = barY0 + (barY1 - barY0) * i / 255.0
            val c = ironbowColor(255 - i)
            Imgproc.line(
                img,
                Point(barX.toDouble(), y), Point((barX + barW).toDouble(), y),
                Scalar(c[0], c[1], c[2]), 1
            )
        }

        // 阈值线
        var thY = 0.0
        if (th > 0) {
            thY = barY1 - (minOf(th, 255.0) / 255.0) * (barY1 - barY0)
            Imgproc.line(img, Point(barX - 3.0, thY), Point(barX + barW + 3.0, thY), Scalar(255.0, 255.0, 255.0), 1)
            drawText(img, "th", Point(barX - 24.0, thY + 6.0), color = Scalar(255.0, 255.0, 255.0))
        }

        // 温度刻度
        val (tLo, tHi) = when {
            tempRange != null -> tempRange
            calib != null -> calib.range()
            else -> null to null
        }
        if (tLo != null) {
            drawText(img, String.format(Locale.US, "%.1fC", tHi), Point(barX - 28.0, barY0 - 8.0), color = Scalar(255.0, 255.0, 255.0))
            drawText(img, String.format(Locale.US, "%.1fC", tLo), Point(barX - 28.0, barY1 + 20.0), color = Scalar(255.0, 255.0, 255.0))
            if (th > 0 && calib != null) {
                val tTh = calib.toCelsius(minOf(th, 255.0))
                drawText(img, String.format(Locale.US, "%.1fC", tTh), Point(barX - 46.0, thY + 6.0), color = Scalar(0.0, 255.0, 255.0))
            }
        } else {
            drawText(img, "255", Point(barX - 14.0, barY0 - 8.0), color = Scalar(255.0, 255.0, 255.0))
            drawText(img, "0", Point(barX - 8.0, barY1 + 20.0), color = Scalar(255.0, 255.0, 255.0))
        }
    }

    /** 右下角叠加分割掩码小窗口（热区红色高亮）。 */
    fun drawMaskPanel(img: Mat, mask: Mat) {
        if (mask.empty()) return
        val h = img.rows()
        val w = img.cols()
        val scale = 0.28
        val small = Mat()
        Imgproc.resize(mask, small, Size(mask.cols() * scale, mask.rows() * scale))
        val smallBgr = Mat()
        Imgproc.cvtColor(small, smallBgr, Imgproc.COLOR_GRAY2BGR)
        val channels = ArrayList<Mat>()
        Core.split(smallBgr, channels)
        channels[0].setTo(Scalar(0.0))          // B = 0
        Core.multiply(channels[1], Scalar(0.5), channels[1]) // G 减半
        Core.merge(channels, smallBgr)
        channels.forEach { it.release() }

        val x0 = w - smallBgr.cols() - 8
        val y0 = h - smallBgr.rows() - 8
        if (x0 >= 0 && y0 >= 0) {
            smallBgr.copyTo(img.submat(y0, y0 + smallBgr.rows(), x0, x0 + smallBgr.cols()))
            Imgproc.rectangle(
                img,
                Point(x0.toDouble(), y0.toDouble()),
                Point((x0 + smallBgr.cols()).toDouble(), (y0 + smallBgr.rows()).toDouble()),
                Scalar(255.0, 255.0, 255.0), 1
            )
        }
        small.release()
        smallBgr.release()
    }

    /** 绘制追踪框、ID、质心、轨迹。有温度数据时显示 ℃。 */
    fun drawTracks(img: Mat, tracks: List<Track>) {
        for (t in tracks) {
            val x = t.bbox.x
            val y = t.bbox.y
            val w = t.bbox.w
            val h = t.bbox.h
            Imgproc.rectangle(
                img,
                Point(x.toDouble(), y.toDouble()),
                Point((x + w).toDouble(), (y + h).toDouble()),
                Scalar(0.0, 255.0, 0.0), 2
            )
            Imgproc.circle(img, Point(t.centroid[0], t.centroid[1]), 3, Scalar(0.0, 255.0, 255.0), -1)

            // 轨迹
            if (t.history.size > 1) {
                val pts = MatOfPoint()
                pts.fromList(t.history.map { Point(it[0], it[1]) })
                Imgproc.polylines(img, listOf(pts), false, Scalar(0.0, 200.0, 255.0), 1)
                pts.release()
            }

            // 标签：ID + 温度（注意：OpenCV putText 用 Hershey 字体，不支持 ° 符号，只能用 ASCII "C"）
            val label = if (t.peakC != null) {
                String.format(Locale.US, "#%d %.1fC", t.id, t.peakC)
            } else {
                "#${t.id} ${t.peak}"
            }
            drawText(img, label, Point((x + 3).toDouble(), maxOf(0, y - 5).toDouble()), color = Scalar(0.0, 255.0, 255.0))
        }
    }

    /**
     * 手动测温框颜色（BGR，对应 PC 版 BOX_COLORS）。最多 20 个循环取色。
     */
    private val regionColors = listOf(
        doubleArrayOf(255.0, 0.0, 0.0),      // 红
        doubleArrayOf(0.0, 255.0, 0.0),      // 绿
        doubleArrayOf(255.0, 255.0, 0.0),    // 青
        doubleArrayOf(255.0, 0.0, 255.0),    // 品红
        doubleArrayOf(0.0, 255.0, 255.0),    // 黄
    )

    /** 温度线颜色（BGR，对应 PC 版 LINE_COLORS）。最多 4 条。 */
    private val lineColors = listOf(
        doubleArrayOf(0.0, 165.0, 255.0),    // 线1 橙
        doubleArrayOf(255.0, 229.0, 0.0),    // 线2 青
        doubleArrayOf(255.0, 0.0, 255.0),    // 线3 品红
        doubleArrayOf(0.0, 255.0, 0.0),      // 线4 绿
    )

    /** 绘制手动测温框：编号 + 框内平均温（真实温度优先，否则灰度均值）+ 峰/低温。 */
    fun drawMeasureRegions(img: Mat, regions: List<MeasureRegion>) {
        if (regions.isEmpty()) return
        for ((i, r) in regions.withIndex()) {
            val c = regionColors[i % regionColors.size]
            val color = Scalar(c[0], c[1], c[2])
            Imgproc.rectangle(
                img,
                Point(r.x.toDouble(), r.y.toDouble()),
                Point((r.x + r.w).toDouble(), (r.y + r.h).toDouble()),
                color, 2
            )
            // 注意：OpenCV Hershey 字体不支持中文/° 符号，只能纯 ASCII
            val label = if (r.meanC != null) {
                String.format(Locale.US, "%d avg %.1fC", i + 1, r.meanC)
            } else if (r.meanG != null) {
                String.format(Locale.US, "%d avg %.0f", i + 1, r.meanG)
            } else {
                "${i + 1}"
            }
            drawText(img, label, Point((r.x + 3).toDouble(), (r.y + 2).toDouble()), size = 0.35, color = color)
            if (r.maxC != null && r.minC != null) {
                val sub = String.format(Locale.US, "max %.1f min %.1f", r.maxC, r.minC)
                drawText(img, sub, Point((r.x + 3).toDouble(), (r.y + 14).toDouble()), size = 0.3, color = Scalar(255.0, 255.0, 255.0))
            }
        }
    }

    /** 绘制温度线：线段 + 端点 + 采样点 + 每点温度（最多 4 条，水平/竖直/斜线均可）。 */
    fun drawTempLines(img: Mat, lines: List<TempLine>) {
        if (lines.isEmpty()) return
        for ((i, ln) in lines.withIndex()) {
            val c = lineColors[i % lineColors.size]
            val color = Scalar(c[0], c[1], c[2])
            val p1 = Point(ln.x1.toDouble(), ln.y1.toDouble())
            val p2 = Point(ln.x2.toDouble(), ln.y2.toDouble())
            Imgproc.line(img, p1, p2, color, 2)
            Imgproc.circle(img, p1, 4, color, -1)
            Imgproc.circle(img, p2, 4, color, -1)

            // 线段单位法向量（标签沿垂直方向交替偏移，避免重叠）
            val dx = (ln.x2 - ln.x1).toDouble()
            val dy = (ln.y2 - ln.y1).toDouble()
            val l = hypot(dx, dy)
            val nx = if (l == 0.0) 0.0 else -dy / l
            val ny = if (l == 0.0) -1.0 else dx / l

            for ((j, pt) in ln.points.withIndex()) {
                val pp = Point(pt.x.toDouble(), pt.y.toDouble())
                Imgproc.circle(img, pp, 3, Scalar(0.0, 0.0, 255.0), -1)
                val txt = when {
                    pt.temp != null -> String.format(Locale.US, "%.1f", pt.temp)
                    pt.gray != null -> pt.gray.toString()
                    else -> continue
                }
                val side = if (j % 2 == 0) 1 else -1
                val ox = nx * 12 * side
                val oy = ny * 12 * side
                drawText(
                    img, txt,
                    Point(pp.x + ox, pp.y + oy),
                    size = 0.32, color = Scalar(255.0, 255.0, 0.0),
                )
            }
        }
    }

    /** 单点颜色（BGR，最多 10 个点，按点编号取色，与界面时间线颜色一致）。 */
    private val pointColors = listOf(
        doubleArrayOf(0.0, 255.0, 255.0),    // 1 黄
        doubleArrayOf(255.0, 255.0, 0.0),    // 2 青
        doubleArrayOf(0.0, 255.0, 0.0),      // 3 绿
        doubleArrayOf(255.0, 0.0, 255.0),    // 4 品红
        doubleArrayOf(0.0, 165.0, 255.0),    // 5 橙
        doubleArrayOf(0.0, 0.0, 255.0),      // 6 红
        doubleArrayOf(255.0, 255.0, 255.0),  // 7 白
        doubleArrayOf(255.0, 0.0, 0.0),      // 8 蓝
        doubleArrayOf(144.0, 238.0, 144.0),  // 9 浅绿
        doubleArrayOf(180.0, 105.0, 255.0),  // 10 粉
    )

    /** 绘制单点测温：彩色圆点 + 编号 + 温度（最多 10 个）。 */
    fun drawMeasurePoints(img: Mat, points: List<MeasurePoint>) {
        for (p in points) {
            val c = pointColors[(p.id - 1).mod(pointColors.size)]
            val color = Scalar(c[0], c[1], c[2])
            val pt = Point(p.x.toDouble(), p.y.toDouble())
            Imgproc.circle(img, pt, 6, color, 2)
            Imgproc.circle(img, pt, 2, color, -1)
            val txt = if (p.tempC != null) String.format(Locale.US, "P%d %.1fC", p.id, p.tempC) else "P${p.id}"
            drawText(img, txt, Point((p.x + 8).toDouble(), (p.y - 8).toDouble()), size = 0.35, color = color)
        }
    }

    /** 一键渲染：伪彩 + （可选）追踪框 + 测温框 + 温度线 + 单点。不画色标条/掩码窗。 */
    fun render(
        gray: Mat,
        tracks: List<Track>,
        mask: Mat?,
        th: Double,
        palette: String,
        calib: TempCalibration?,
        tempRange: Pair<Double, Double>?,
        regions: List<MeasureRegion> = emptyList(),
        tempLines: List<TempLine> = emptyList(),
        points: List<MeasurePoint> = emptyList(),
        showTracks: Boolean = true,
    ): Mat {
        val img = applyPalette(gray, palette)
        if (showTracks) drawTracks(img, tracks)
        drawMeasureRegions(img, regions)
        drawTempLines(img, tempLines)
        drawMeasurePoints(img, points)

        // BGR -> RGB 适配 Android Bitmap
        val rgb = Mat()
        Imgproc.cvtColor(img, rgb, Imgproc.COLOR_BGR2RGB)
        img.release()
        return rgb
    }
}
