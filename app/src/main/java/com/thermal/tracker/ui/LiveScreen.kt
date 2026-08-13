package com.thermal.tracker.ui

import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thermal.tracker.model.TempLine
import com.thermal.tracker.viewmodel.LiveViewModel
import com.thermal.tracker.viewmodel.PointSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** 按住触发 start，松开触发 stop 的按钮（用于连续调焦）。
 * 用 awaitEachGesture 精确捕获按下/抬起：按住期间每 250ms 重发一次 start，
 * 保证电机持续转动（即使某次 stop 被误触发，下一轮 start 会重新驱动）。
 */
@Composable
private fun HoldButton(
    text: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    Button(
        onClick = { /* 空操作，按住由手势处理 */ },
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                pressed = true
                onStart()
                var lastSend = System.currentTimeMillis()
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { !it.pressed }) break
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= 250) {
                        lastSend = now
                        onStart()
                    }
                }
                pressed = false
                onStop()
            }
        },
    ) {
        Text(if (pressed) "$text…" else text)
    }
}

/** 直播页：实时画面 + 手动调焦 + 测温框/温度线 + 温度线分布图 + 截图/录像。 */
@Composable
fun LiveScreen(vm: LiveViewModel) {
    val stats by vm.stats.collectAsState()
    val palette by vm.palette.collectAsState()
    val csvEnabled by vm.csvEnabled.collectAsState()
    val sdkStatus by vm.sdkStatus.collectAsState()
    val captureInfo by vm.captureInfo.collectAsState()
    val measureMode by vm.measureMode.collectAsState()
    val lineMode by vm.lineMode.collectAsState()
    val pointMode by vm.pointMode.collectAsState()
    val showTracks by vm.showTracks.collectAsState()
    val chartLines by vm.tempLineChart.collectAsState()
    val pointTimeline by vm.pointTimeline.collectAsState()
    val scrollState = rememberScrollState()

    // 时间曲线导出：打开开关后先弹系统文件选择器选保存位置（SAF），选定后才开始记录 CSV
    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) vm.startCsv(uri)
    }

    // 上下各留 150 像素空白（像素->dp 换算，适配 2640x1216 等屏幕）
    val densityPx = LocalDensity.current.density
    val topPad = Dp(150f / densityPx)
    val bottomPad = Dp(150f / densityPx)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(top = topPad, bottom = bottomPad)
    ) {
        // ---- 实时画面（固定在上方，帧更新只重组画面；绿色荧光外轮廓）----
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1.2f)
                .background(Color.Black)
                .border(2.dp, Color(0xFF00A651))
        ) {
            VideoPreview(vm, Modifier.fillMaxSize())
            if (measureMode || lineMode || pointMode) {
                AnnotationGestureLayer(vm, Modifier.fillMaxSize())
            }
        }

        // ---- 下方控制区：可上下滑动，按功能分区整理 ----
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(bottom = 8.dp)
        ) {
            // 状态栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("FPS ${stats.fps.toInt()}", style = MaterialTheme.typography.bodySmall)
                Text("热区 ${stats.hotspotCount}", style = MaterialTheme.typography.bodySmall)
                Text("温度 ${stats.tempRange}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(
                    sdkStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sdkStatus.contains("海康SDK")) Color(0xFF4CAF50) else Color(0xFFEF5350),
                )
            }

            // ---- 显示：配色 + 追踪框开关 ----
            SectionHeader("显示")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaletteSelector(vm = vm, current = palette, modifier = Modifier.weight(1f))
                Text("追踪框", style = MaterialTheme.typography.bodySmall)
                Switch(checked = showTracks, onCheckedChange = { vm.toggleShowTracks() })
            }

            // ---- 标注：测温框/温度线下拉 + 清空 ----
            SectionHeader("标注")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnnotationModeDropdown(
                    mode = when {
                        measureMode -> 1
                        lineMode -> 2
                        pointMode -> 3
                        else -> 0
                    },
                    onSelect = { vm.setAnnotationMode(it) },
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedButton(onClick = { vm.clearAnnotations() }, modifier = Modifier.weight(1f)) {
                    Text("清空标注", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- 调焦：按住持续调，松开停止 ----
            SectionHeader("调焦")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HoldButton("调近 ◀", onStart = { vm.focusNearStart() }, onStop = { vm.focusNearStop() }, modifier = Modifier.weight(1f))
                HoldButton("调远 ▶", onStart = { vm.focusFarStart() }, onStop = { vm.focusFarStop() }, modifier = Modifier.weight(1f))
            }

            // ---- 采集：断开（西门子淡红色按钮）----
            SectionHeader("采集")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { vm.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE57373),
                        contentColor = Color.White,
                    ),
                ) { Text("断开") }
            }

            // ---- 时间曲线导出（CSV，先选保存位置）----
            SectionHeader("时间曲线导出")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = csvEnabled,
                    onCheckedChange = { on ->
                        if (on) csvPicker.launch("time_curve_${System.currentTimeMillis()}.csv")
                        else vm.stopCsv()
                    },
                )
                Text("导出CSV 点温/温度线/温度框", style = MaterialTheme.typography.bodySmall)
            }

            // 导出结果提示
            if (captureInfo.isNotEmpty()) {
                Text(
                    captureInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9ED9B0),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            // ---- 温度线分布图（2s 刷新）----
            SectionHeader("温度线分布")
            TempLineChart(
                lines = chartLines,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )

            // ---- 单点温度时间线（最长 30 分钟）----
            if (pointTimeline.isNotEmpty()) {
                SectionHeader("单点时间线")
                PointTimelineChart(
                    timeline = pointTimeline,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 独立预览组件：只在此处收集 preview，帧更新时不会触发整个直播页重组。 */
@Composable
private fun VideoPreview(vm: LiveViewModel, modifier: Modifier = Modifier) {
    val preview by vm.preview.collectAsState()
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        },
        update = { it.setImageBitmap(preview) },
        modifier = modifier,
    )
}

/**
 * 标注手势层：测温框/温度线模式下，拖拽添加、点击删除。
 * 屏幕坐标按 FIT_CENTER 逆变换换算到图像坐标。
 */
@Composable
private fun AnnotationGestureLayer(vm: LiveViewModel, modifier: Modifier = Modifier) {
    val preview by vm.preview.collectAsState()
    var boxW by remember { mutableStateOf(0) }
    var boxH by remember { mutableStateOf(0) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var end by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier
            .onSizeChanged { boxW = it.width; boxH = it.height }
            .pointerInput(vm.measureMode.value, vm.lineMode.value, vm.pointMode.value) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    start = down.position
                    end = down.position
                    dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            end = change.position
                            if ((end - start).getDistance() > 10f) dragging = true
                            change.consume()
                        } else break
                    }
                    val bmp = preview
                    if (bmp != null && boxW > 0 && boxH > 0) {
                        val (x1, y1) = toImage(start, boxW, boxH, bmp.width, bmp.height)
                        val (x2, y2) = toImage(end, boxW, boxH, bmp.width, bmp.height)
                        if (dragging) {
                            if (vm.measureMode.value) vm.addMeasureRegion(x1, y1, x2, y2)
                            else if (vm.lineMode.value) vm.addTempLine(x1, y1, x2, y2)
                        } else {
                            if (vm.pointMode.value) vm.tapPoint(x1, y1)
                            else vm.deleteAnnotationAt(x1, y1)
                        }
                    }
                }
            }
    ) {
        // 拖拽中的预览图形
        Canvas(Modifier.fillMaxSize()) {
            if (dragging) {
                if (vm.measureMode.value) {
                    val l = min(start.x, end.x)
                    val t = min(start.y, end.y)
                    drawRect(
                        color = Color(0xFFFF6F00),
                        topLeft = Offset(l, t),
                        size = Size(abs(end.x - start.x), abs(end.y - start.y)),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                } else if (vm.lineMode.value) {
                    drawLine(
                        color = Color(0xFFFFA726),
                        start = start,
                        end = end,
                        strokeWidth = 3.dp.toPx(),
                    )
                }
            }
        }
    }
}

/** 屏幕坐标 -> 图像坐标（ImageView FIT_CENTER 逆变换）。 */
private fun toImage(p: Offset, boxW: Int, boxH: Int, bmpW: Int, bmpH: Int): Pair<Float, Float> {
    if (bmpW <= 0 || bmpH <= 0 || boxW <= 0 || boxH <= 0) return p.x to p.y
    val scale = min(boxW.toFloat() / bmpW, boxH.toFloat() / bmpH)
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val offX = (boxW - drawW) / 2f
    val offY = (boxH - drawH) / 2f
    return ((p.x - offX) / scale) to ((p.y - offY) / scale)
}

@Composable
private fun PaletteSelector(vm: LiveViewModel, current: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("配色: $current") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("siemens", "whitehot", "inferno", "jet").forEach { p ->
                DropdownMenuItem(
                    text = { Text(p) },
                    onClick = {
                        vm.setPalette(p)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 标注模式下拉：0=无 1=测温框 2=温度线 3=单点。
 * 统一由一个下拉管理，避免多个开关按钮混乱。
 */
@Composable
private fun AnnotationModeDropdown(mode: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val label = when (mode) {
        1 -> "标注: 测温框"
        2 -> "标注: 温度线"
        3 -> "标注: 单点"
        else -> "标注: 无"
    }
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("无") }, onClick = { onSelect(0); expanded = false })
            DropdownMenuItem(text = { Text("测温框") }, onClick = { onSelect(1); expanded = false })
            DropdownMenuItem(text = { Text("温度线") }, onClick = { onSelect(2); expanded = false })
            DropdownMenuItem(text = { Text("单点") }, onClick = { onSelect(3); expanded = false })
        }
    }
}

/** 分区标题：蓝色小标题 + 分隔线，让下方控制区有条理。 */
@Composable
private fun SectionHeader(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color(0xFF00A651))
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = Color(0xFF3A3A3E))
    }
}

/**
 * 温度线分布图：每条温度线一个折线，横轴为采样点序号，纵轴为温度（℃）。
 * 数据来自 vm.tempLineChart（2s 刷新），颜色与画面上的温度线一致。
 */
@Composable
private fun TempLineChart(lines: List<TempLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val allTemps = lines.flatMap { it.points.mapNotNull { p -> p.temp } }
    if (allTemps.isEmpty()) return
    val tMax = allTemps.max()
    val tMin = allTemps.min()
    val span = if (tMax - tMin > 0.01) (tMax - tMin) else 1.0
    val colors = listOf(Color(0xFFFFA500), Color(0xFF00E5FF), Color(0xFFFF00FF), Color(0xFF00FF00))

    Column(modifier) {
        // 标题 + 量程
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("温度线分布（2s刷新）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFFFFF))
            Text(
                String.format(Locale.US, "%.1f~%.1fC", tMin, tMax),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFFFFF),
            )
        }

        // 折线图
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 4.dp)) {
            val padL = 10f
            val padR = 10f
            val padT = 6f
            val padB = 6f
            val pw = size.width - padL - padR
            val ph = size.height - padT - padB
            if (pw <= 0 || ph <= 0) return@Canvas
            // 网格线（5 格）
            for (i in 0..4) {
                val y = padT + ph * i / 4f
                drawLine(Color(0xFF3A3A3E), Offset(padL, y), Offset(padL + pw, y), 1f)
            }
            // 各线
            for ((idx, ln) in lines.withIndex()) {
                val color = colors[idx % colors.size]
                val pts = ln.points.mapIndexedNotNull { i, p ->
                    p.temp?.let { t ->
                        val x = padL + pw * i / maxOf(1, ln.points.size - 1).toFloat()
                        val y = padT + ph * ((tMax - t) / span).toFloat()
                        Offset(x, y)
                    }
                }
                if (pts.size >= 2) {
                    for (i in 0 until pts.size - 1) {
                        drawLine(color, pts[i], pts[i + 1], 2f)
                    }
                }
                pts.forEach { drawCircle(color, 2.5f, it) }
            }
        }

        // 图例
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            lines.forEachIndexed { idx, _ ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(3.dp).background(colors[idx % colors.size]))
                    Text(
                        " 线${idx + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD0D0D0),
                    )
                }
            }
        }
    }
}

/**
 * 单点温度时间线：每个点一条彩色折线（横轴为时间，纵轴为温度 ℃）。
 * 数据来自 vm.pointTimeline（2s 采样，最长 30 分钟，最多 10 个点）。
 * 颜色与画面上的点一致（按点编号取色）。
 */
@Composable
private fun PointTimelineChart(timeline: List<PointSample>, modifier: Modifier = Modifier) {
    if (timeline.size < 2) return
    val pointIds = timeline.map { it.pointId }.distinct().sorted()
    val tMin = timeline.minOf { it.tempC }
    val tMax = timeline.maxOf { it.tempC }
    val span = if (tMax - tMin > 0.01) (tMax - tMin) else 1.0
    val t0 = timeline.first().timeMs
    val t1 = timeline.last().timeMs
    val dur = maxOf(1L, t1 - t0)
    val colors = listOf(
        Color(0xFFFFFF00), // 1 黄
        Color(0xFF00FFFF), // 2 青
        Color(0xFF00FF00), // 3 绿
        Color(0xFFFF00FF), // 4 品红
        Color(0xFFFFA500), // 5 橙
        Color(0xFFFF0000), // 6 红
        Color(0xFFFFFFFF), // 7 白
        Color(0xFF0000FF), // 8 蓝
        Color(0xFF90EE90), // 9 浅绿
        Color(0xFFFF69B4), // 10 粉
    )

    Column(modifier) {
        // 标题 + 量程 + 时长
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "单点温度时间线(${pointIds.size}点)",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFFFFF),
            )
            Text(
                String.format(Locale.US, "%.1f~%.1fC  %d分%d秒", tMin, tMax, dur / 60000, (dur % 60000) / 1000),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFFFFF),
            )
        }

        // 折线图
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 4.dp)) {
            val padL = 10f
            val padR = 10f
            val padT = 6f
            val padB = 6f
            val pw = size.width - padL - padR
            val ph = size.height - padT - padB
            if (pw <= 0 || ph <= 0) return@Canvas
            // 网格线（5 格）
            for (i in 0..4) {
                val y = padT + ph * i / 4f
                drawLine(Color(0xFF3A3A3E), Offset(padL, y), Offset(padL + pw, y), 1f)
            }
            // 每个点一条折线
            for (pid in pointIds) {
                val color = colors[(pid - 1).mod(colors.size)]
                val pts = timeline.filter { it.pointId == pid }
                for (i in 0 until pts.size - 1) {
                    val p0 = pts[i]
                    val p1 = pts[i + 1]
                    val x0 = padL + pw * (p0.timeMs - t0).toFloat() / dur
                    val x1 = padL + pw * (p1.timeMs - t0).toFloat() / dur
                    val y0 = padT + ph * ((tMax - p0.tempC) / span).toFloat()
                    val y1 = padT + ph * ((tMax - p1.tempC) / span).toFloat()
                    drawLine(color, Offset(x0, y0), Offset(x1, y1), 2f)
                }
                for (p in pts) {
                    val x = padL + pw * (p.timeMs - t0).toFloat() / dur
                    val y = padT + ph * ((tMax - p.tempC) / span).toFloat()
                    drawCircle(color, 2.5f, Offset(x, y))
                }
            }
        }

        // 图例
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pointIds.forEach { pid ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(10.dp)
                            .height(3.dp)
                            .background(colors[(pid - 1).mod(colors.size)])
                    )
                    Text(
                        " P$pid",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD0D0D0),
                    )
                }
            }
        }

        // 时间刻度
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatClock(t0),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD0D0D0),
            )
            Text(
                formatClock(t1),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD0D0D0),
            )
        }
    }
}

private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

/** 毫秒时间戳 -> 当日真实时间 HH:MM:SS。 */
private fun formatClock(ms: Long): String = clockFmt.format(Date(ms))

