package com.thermal.tracker.data

import android.content.ContentResolver
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单文件 CSV 时间曲线记录器（用户通过系统文件选择器指定保存位置，导出为 CSV）。
 *
 * 列：时间, 点1(℃), 点2(℃), ..., 线1均值(℃), 线2均值(℃), ..., 框1max(℃), 框1min(℃), 框1avg(℃), ...
 * 带 UTF-8 BOM，用 Excel 打开中文不乱码；NaN / 空值写空单元格。
 *
 * 用法：采集期间反复 [addSample]，最后 [save] 一次性把 CSV 写入用户选择的 Uri。
 */
class CsvRecorder(
    private val resolver: ContentResolver,
    private val uri: Uri,
) {
    /** 一个采样时刻的全部标注快照。 */
    data class Sample(
        val timeMs: Long,
        val pointTemps: List<Double?>,
        val lineAvgs: List<Double>,
        val regionStats: List<Triple<Double, Double, Double>>, // (max, min, avg)
    )

    private val samples = mutableListOf<Sample>()
    private var pointCount = 0
    private var lineCount = 0
    private var regionCount = 0
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addSample(
        timeMs: Long,
        pointTemps: List<Double?>,
        lineAvgs: List<Double>,
        regionStats: List<Triple<Double, Double, Double>>,
    ) {
        pointCount = maxOf(pointCount, pointTemps.size)
        lineCount = maxOf(lineCount, lineAvgs.size)
        regionCount = maxOf(regionCount, regionStats.size)
        samples.add(Sample(timeMs, pointTemps, lineAvgs, regionStats))
    }

    fun uri(): Uri = uri

    fun size(): Int = samples.size

    /** 写出 CSV（UTF-8 + BOM）并关闭输出流。 */
    fun save() {
        val sb = StringBuilder()
        sb.append('\uFEFF') // UTF-8 BOM，Excel 识别中文

        // 表头
        val header = mutableListOf("时间")
        for (i in 0 until pointCount) header += "点${i + 1}(℃)"
        for (i in 0 until lineCount) header += "线${i + 1}均值(℃)"
        for (i in 0 until regionCount) {
            header += "框${i + 1}max(℃)"
            header += "框${i + 1}min(℃)"
            header += "框${i + 1}avg(℃)"
        }
        sb.append(header.joinToString(",") { csvEscape(it) }).append("\r\n")

        // 数据行
        for (s in samples) {
            val cells = mutableListOf<String>()
            cells += csvEscape(timeFmt.format(Date(s.timeMs)))
            for (i in 0 until pointCount) cells += num(s.pointTemps.getOrNull(i))
            for (i in 0 until lineCount) cells += num(s.lineAvgs.getOrNull(i))
            for (i in 0 until regionCount) {
                val st = s.regionStats.getOrNull(i)
                cells += num(st?.first)
                cells += num(st?.second)
                cells += num(st?.third)
            }
            sb.append(cells.joinToString(",")).append("\r\n")
        }

        val out = resolver.openOutputStream(uri)
            ?: error("无法打开保存位置: $uri")
        out.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun num(v: Double?): String =
        if (v == null || v.isNaN()) "" else String.format(Locale.US, "%.2f", v)

    /** 含逗号/引号/换行时用双引号包裹并转义。 */
    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
}
