package com.thermal.tracker.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.thermal.tracker.camera.RtspStream
import com.thermal.tracker.data.CsvRecorder
import com.thermal.tracker.data.TemperatureProvider
import com.thermal.tracker.hik.HikSdkNative
import com.thermal.tracker.hik.MockThermalSdk
import com.thermal.tracker.hik.ThermalSdk
import com.thermal.tracker.model.AppConfig
import com.thermal.tracker.model.CameraConfig
import com.thermal.tracker.model.MeasurePoint
import com.thermal.tracker.model.MeasureRegion
import com.thermal.tracker.model.TempLine
import com.thermal.tracker.model.TempLinePoint
import com.thermal.tracker.model.Track
import com.thermal.tracker.processing.CentroidTracker
import com.thermal.tracker.processing.Detection
import com.thermal.tracker.processing.TempCalibration
import com.thermal.tracker.processing.Visualizer
import com.thermal.tracker.processing.matToBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.core.Core
import org.opencv.core.Mat

/** 实时统计信息。 */
data class Stats(
    val fps: Double = 0.0,
    val hotspotCount: Int = 0,
    val tempRange: String = "",
)

/** 单点时间线采样：某时刻某个测温点的温度。 */
data class PointSample(val timeMs: Long, val pointId: Int, val tempC: Double)

/**
 * 直播处理管线（对应 Python 版 gui_worker.py）：
 * 取流 -> 灰度 -> 检测 -> 追踪 -> 温度 -> 渲染 -> 预览 / CSV / 录像。
 *
 * 所有相机/图像操作都在独立 HandlerThread 上执行，避免阻塞 UI。
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _palette = MutableStateFlow("siemens")
    val palette: StateFlow<String> = _palette.asStateFlow()

    private val _csvEnabled = MutableStateFlow(false)
    val csvEnabled: StateFlow<Boolean> = _csvEnabled.asStateFlow()

    private val _csvFile = MutableStateFlow<String?>(null)
    val csvFile: StateFlow<String?> = _csvFile.asStateFlow()

    // 操作提示（时间曲线导出结果等）
    private val _captureInfo = MutableStateFlow("")
    val captureInfo: StateFlow<String> = _captureInfo.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pingResult = MutableStateFlow<String?>(null)
    val pingResult: StateFlow<String?> = _pingResult.asStateFlow()

    private val _connectionStatus = MutableStateFlow("未连接")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    // SDK 状态：真实海康 SDK 还是模拟回退
    private val _sdkStatus = MutableStateFlow("SDK 未连接")
    val sdkStatus: StateFlow<String> = _sdkStatus.asStateFlow()

    // 手动测温框 / 温度线（对应 PC 版 gui_worker 的 measure_regions / temp_lines）
    private val _measureMode = MutableStateFlow(false)
    val measureMode: StateFlow<Boolean> = _measureMode.asStateFlow()

    private val _lineMode = MutableStateFlow(false)
    val lineMode: StateFlow<Boolean> = _lineMode.asStateFlow()

    private val _measureRegions = MutableStateFlow<List<MeasureRegion>>(emptyList())
    val measureRegions: StateFlow<List<MeasureRegion>> = _measureRegions.asStateFlow()

    private val _tempLines = MutableStateFlow<List<TempLine>>(emptyList())
    val tempLines: StateFlow<List<TempLine>> = _tempLines.asStateFlow()

    // 单点测温：单点模式 + 多个测温点（最多 10 个）+ 各点温度时间线（最长 30 分钟）
    private val _pointMode = MutableStateFlow(false)
    val pointMode: StateFlow<Boolean> = _pointMode.asStateFlow()

    private val _measurePoints = MutableStateFlow<List<MeasurePoint>>(emptyList())
    val measurePoints: StateFlow<List<MeasurePoint>> = _measurePoints.asStateFlow()

    private val _pointTimeline = MutableStateFlow<List<PointSample>>(emptyList())
    val pointTimeline: StateFlow<List<PointSample>> = _pointTimeline.asStateFlow()

    // 自动追踪绿框显示开关（默认开，可关）
    private val _showTracks = MutableStateFlow(true)
    val showTracks: StateFlow<Boolean> = _showTracks.asStateFlow()

    // 温度线分布图数据（2s 刷新一次，用于下方分布图）
    private val _tempLineChart = MutableStateFlow<List<TempLine>>(emptyList())
    val tempLineChart: StateFlow<List<TempLine>> = _tempLineChart.asStateFlow()

    val cfg = AppConfig()

    // 处理线程（帧抓取 / OpenCV / 编码都在这里）
    private val processThread = HandlerThread("ThermalProcessing").apply { start() }
    private val handler = Handler(processThread.looper)

    private var stream: RtspStream? = null
    private var sdk: ThermalSdk? = null
    private var tracker: CentroidTracker? = null
    private var calib: TempCalibration? = null
    private var tempProvider: TemperatureProvider? = null
    private var csv: CsvRecorder? = null
    private var running = false
    private var latestGray: Mat? = null
    // 时间曲线导出采样状态（2s 一次）
    private var lastExportSampleMs = 0L
    // 手动测温框 / 温度线（仅在 handler 线程访问，避免并发）
    private val regions = mutableListOf<MeasureRegion>()
    private val tempLineList = mutableListOf<TempLine>()
        private var nextRegionId = 1
    private var nextLineId = 1
    private val lineSamplePoints = 9
    private var lastChartUpdateMs = 0L
    // 单点时间线采样状态
    private var lastPointSampleMs = 0L
    private val pointTimelineMaxMs = 30 * 60 * 1000L
    // 处理循环状态（processStep 链式调度用）
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var firstFrameSeen = false
    private var lastFrameAt = 0L
    private var lastFrameNs = 0L
    private var fps = 0.0
    // UI 限流：预览 ~30FPS，统计/表格 ~5Hz，防止高频重组把主线程拖垮导致按钮卡顿
    private var lastPreviewEmitMs = 0L
    private var lastStatsEmitMs = 0L

    override fun onCleared() {
        super.onCleared()
        handler.post { disconnectInternal() }
        processThread.quitSafely()
    }

    // ---------------- 连接 ----------------

    fun connect(cameraCfg: CameraConfig) {
        handler.post {
            disconnectInternal()
            cfg.camera = cameraCfg
            calib = TempCalibration.fromConfig(cfg.calibration)
            tracker = CentroidTracker(cfg.tracker)

            // 优先真实海康 SDK（JNA，8000 端口登录）；失败自动回退模拟测温，不影响 RTSP 画面
            val sdkInst: ThermalSdk = try {
                val s = HikSdkNative(cfg.camera.ip, 8000, cfg.camera.username, cfg.camera.password)
                if (s.login(cfg.camera.ip, 8000, cfg.camera.username, cfg.camera.password)) {
                    Log.i(TAG, "海康 SDK 登录成功，真实测温/调焦已启用")
                    s
                } else {
                    Log.w(TAG, "海康 SDK 登录失败，回退到模拟测温")
                    MockThermalSdk({ latestGray })
                }
            } catch (t: Throwable) {
                Log.e(TAG, "海康 SDK 初始化异常，回退到模拟测温", t)
                MockThermalSdk({ latestGray })
            }
            sdk = sdkInst
            tempProvider = TemperatureProvider(sdkInst, calib!!)
            _sdkStatus.value =
                if (sdkInst.name == "HikSDK") "海康SDK测温"
                else "模拟测温(未登录SDK)"

            val urls = cameraCfg.getRtspUrls()
            tryConnectUrls(urls, 0)
        }
    }

    private fun tryConnectUrls(urls: List<String>, index: Int) {
        if (index >= urls.size) {
            _error.value = "所有 RTSP 地址均连接失败"
            _connectionStatus.value = "连接失败"
            _connected.value = false
            return
        }

        this.urls = urls
        urlIndex = index
        val url = urls[index]
        _connectionStatus.value = "尝试路线 ${index + 1}/${urls.size}"
        Log.i(TAG, "尝试连接 RTSP: ${url.replace(cfg.camera.password, "******")}")

        val st = RtspStream(
            getApplication(), url, cfg.camera.frameWidth, cfg.camera.frameHeight,
            useTcp = cfg.camera.rtspTransport == "tcp"
        ) { errMsg ->
            handler.post {
                if (stream == null) return@post // 已经处理过或已手动断开
                _error.value = errMsg
                _connectionStatus.value = "错误: $errMsg"
                Log.w(TAG, "码流报错，尝试下一个: $errMsg")
                
                // 停止当前循环并清理
                running = false
                stream?.release()
                stream = null
                
                // 延迟尝试下一个，避免过于频繁
                handler.postDelayed({
                    if (!connected.value || index >= urls.size) return@postDelayed
                    tryConnectUrls(urls, index + 1)
                }, 500)
            }
        }
        
        if (!st.start()) {
            Log.e(TAG, "RtspStream.start() 失败: $url")
            handler.postDelayed({
                tryConnectUrls(urls, index + 1)
            }, 500)
            return
        }

        stream = st
        running = true
        _connected.value = true
        _error.value = null
        _connectionStatus.value = "已握手，等待画面..."
        firstFrameSeen = false
        lastFrameAt = System.currentTimeMillis()
        lastFrameNs = System.nanoTime()
        fps = 0.0

        // 注意：用 postDelayed 链式调度代替 while 死循环，
        // 否则会阻塞 HandlerThread 的 Looper，饿死 SurfaceTexture 的
        // onFrameAvailable 回调，导致永远抓不到帧（黑屏无图像）。
        handler.post(::processStep)
    }

    fun disconnect() {
        handler.post { disconnectInternal() }
    }

    /** 测试网络连通性及 RTSP 端口。 */
    fun ping(ip: String) {
        handler.post {
            _pingResult.value = "正在测试 $ip..."
            try {
                // 1. ICMP Ping
                val process = Runtime.getRuntime().exec("ping -c 1 -w 2 $ip")
                val icmpOk = process.waitFor() == 0
                
                // 2. TCP 554 端口测试
                val socket = java.net.Socket()
                val portOk = try {
                    socket.connect(java.net.InetSocketAddress(ip, 554), 2000)
                    true
                } catch (_: Exception) {
                    false
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }

                when {
                    icmpOk && portOk -> _pingResult.value = "成功：IP 可达且 RTSP 端口 554 已开启"
                    icmpOk -> _pingResult.value = "警告：IP 可达但 554 端口关闭（请检查相机 RTSP 设置）"
                    else -> _pingResult.value = "失败：IP 不可达，请检查 WiFi 连接"
                }
            } catch (e: Exception) {
                _pingResult.value = "测试出错: ${e.message}"
            }
        }
    }

    private fun disconnectInternal() {
        running = false
        stopCsvInternal()
        stream?.release()
        stream = null
        sdk?.logout()
        sdk = null
        tracker = null
        tempProvider = null
        latestGray?.release()
        latestGray = null
        _connected.value = false
        _preview.value = null
        _tracks.value = emptyList()
        _stats.value = Stats()
    }

    // ---------------- 处理循环 ----------------

    /**
     * 处理单帧并链式调度下一帧（非阻塞）。
     *
     * 必须用 postDelayed 而不是 while 死循环：本方法运行在 HandlerThread 上，
     * 若阻塞其 Looper，SurfaceTexture 的 onFrameAvailable 回调会被饿死，
     * 帧永远无法被标记，画面就是黑的。
     */
    private fun processStep() {
        if (!running) return
        val st = stream ?: return

        try {
            val mat = st.grabFrame()

            if (mat == null) {
                // 10 秒未收到首帧 -> 该地址无画面，尝试下一个
                if (!firstFrameSeen && System.currentTimeMillis() - lastFrameAt > 10_000) {
                    Log.w(TAG, "10秒内未收到任何帧，尝试下一个 RTSP 地址")
                    stream?.release()
                    stream = null
                    running = false
                    tryConnectUrls(urls, urlIndex + 1)
                    return
                }
                handler.postDelayed(::processStep, 10)
                return
            }

            if (!firstFrameSeen) {
                firstFrameSeen = true
                _connectionStatus.value = "直播中"
            }
            lastFrameAt = System.currentTimeMillis()

            val nowMs = System.currentTimeMillis()
            val curNs = System.nanoTime()
            val dt = (curNs - lastFrameNs) / 1e9
            lastFrameNs = curNs
            if (dt > 0) fps = fps * 0.9 + (1.0 / dt) * 0.1

            val gray = Detection.toGray(mat)
            mat.release()
            if (gray == null) {
                handler.post(::processStep)
                return
            }

            latestGray?.release()
            latestGray = gray

            // 温度矩阵（SDK 周期抓拍）
            tempProvider?.onFrame(nowMs)

            // 检测 + 温度覆盖
            val (dets, mask, th) = Detection.detectHotspots(gray, cfg.detection, calib)
            val detsT = dets.map { d ->
                val tp = tempProvider
                if (tp != null) {
                    val (pc, mc) = tp.regionTemps(d.bbox, gray.cols(), gray.rows(), d.peak, d.mean)
                    d.copy(peakC = pc ?: d.peakC, meanC = mc ?: d.meanC)
                } else d
            }
            val tracks = tracker?.update(detsT) ?: emptyList()

            // 渲染（含手动测温框/温度线标注；追踪框可选）
            val (tLo, tHi) = calib?.range() ?: (0.0 to 0.0)
            val rendered = Visualizer.render(
                gray, tracks, mask, th, _palette.value, calib, tLo to tHi,
                _measureRegions.value, _tempLines.value,
                _measurePoints.value, _showTracks.value,
            )
            mask?.release()

            // 限流 UI 更新：预览最多 ~30FPS、统计/表格 ~5Hz，
            // 避免高频 StateFlow 触发整个直播页重组，导致按钮卡顿、延迟变大
            val now2 = System.currentTimeMillis()
            if (now2 - lastPreviewEmitMs >= 33) {
                lastPreviewEmitMs = now2
                _preview.value = matToBitmap(rendered)
            }
            rendered.release()

            if (now2 - lastStatsEmitMs >= 200) {
                lastStatsEmitMs = now2
                refreshAnnotationStats(gray)
                _tracks.value = tracks
                _stats.value = Stats(
                    fps = fps,
                    hotspotCount = tracks.size,
                    tempRange = String.format("%.1f~%.1f °C", tLo, tHi),
                )
            }

            // 立即调度下一帧（不空转等待；无新帧时 grabFrame 返回 null 会自动 10ms 后重试）
            handler.post(::processStep)
        } catch (t: Throwable) {
            Log.e(TAG, "处理过程崩溃: ${t.message}", t)
            _error.value = "处理过程崩溃: ${t.message}"
            disconnectInternal()
        }
    }

    // ---------------- 控制 ----------------

    fun setPalette(p: String) {
        _palette.value = p
    }

    // ---------------- 手动调焦（按住调近/调远） ----------------

    fun focusNearStart() = handler.post { sdk?.focusNearStart() }
    fun focusNearStop() = handler.post { sdk?.focusNearStop() }
    fun focusFarStart() = handler.post { sdk?.focusFarStart() }
    fun focusFarStop() = handler.post { sdk?.focusFarStop() }

    // ---------------- 手动测温框 / 温度线（对应 PC 版功能） ----------------

    /** 开关自动追踪绿框显示。 */
    fun toggleShowTracks() = handler.post {
        _showTracks.value = !_showTracks.value
    }

    /** 通过下拉菜单设置标注模式：0=无 1=测温框 2=温度线 3=单点。 */
    fun setAnnotationMode(mode: Int) = handler.post {
        _measureMode.value = mode == 1
        _lineMode.value = mode == 2
        _pointMode.value = mode == 3
    }

    /**
     * 单点模式点击：点已有且点附近则删除该点；否则新增一个点（最多 10 个）。
     */
    fun tapPoint(x: Float, y: Float) = handler.post {
        val pts = _measurePoints.value
        // 命中已有点（图像坐标半径 25px）则删除，并清理它的时间线
        val hit = pts.firstOrNull { p ->
            kotlin.math.hypot((x - p.x).toDouble(), (y - p.y).toDouble()) < 25.0
        }
        if (hit != null) {
            _measurePoints.value = pts.filterNot { it.id == hit.id }
            _pointTimeline.value = _pointTimeline.value.filterNot { it.pointId == hit.id }
            return@post
        }
        if (pts.size >= 10) {
            _captureInfo.value = "单点最多 10 个"
            return@post
        }
        val nextId = (pts.maxOfOrNull { it.id } ?: 0) + 1
        _measurePoints.value = pts + MeasurePoint(nextId, x.toInt().coerceAtLeast(0), y.toInt().coerceAtLeast(0))
        lastPointSampleMs = 0L
    }

    /** 开关测温框模式（与温度线互斥）。 */
    fun toggleMeasureMode() = handler.post {
        _measureMode.value = !_measureMode.value
        if (_measureMode.value) _lineMode.value = false
    }

    /** 开关温度线模式（与测温框互斥）。 */
    fun toggleLineMode() = handler.post {
        _lineMode.value = !_lineMode.value
        if (_lineMode.value) _measureMode.value = false
    }

    /** 清空所有测温框、温度线与单点。 */
    fun clearAnnotations() = handler.post {
        regions.clear()
        tempLineList.clear()
        _measureRegions.value = emptyList()
        _tempLines.value = emptyList()
        _measurePoints.value = emptyList()
        _pointTimeline.value = emptyList()
    }

    /** 拖拽结束：添加测温框（图像坐标，最多 20 个）。 */
    fun addMeasureRegion(x1: Float, y1: Float, x2: Float, y2: Float) = handler.post {
        if (regions.size >= 20) return@post
        val x0 = minOf(x1, x2).toInt().coerceAtLeast(0)
        val y0 = minOf(y1, y2).toInt().coerceAtLeast(0)
        val xb = maxOf(x1, x2).toInt().coerceAtLeast(0)
        val yb = maxOf(y1, y2).toInt().coerceAtLeast(0)
        if (xb - x0 < 3 || yb - y0 < 3) return@post
        regions.add(MeasureRegion(nextRegionId++, x0, y0, xb - x0, yb - y0))
        _measureRegions.value = regions.toList()
    }

    /** 拖拽结束：添加温度线（图像坐标，最多 4 条）。 */
    fun addTempLine(x1: Float, y1: Float, x2: Float, y2: Float) = handler.post {
        if (tempLineList.size >= 4) return@post
        if (kotlin.math.abs(x2 - x1) < 2 && kotlin.math.abs(y2 - y1) < 2) return@post
        tempLineList.add(TempLine(nextLineId++, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt()))
        _tempLines.value = tempLineList.toList()
    }

    /** 点击删除：测温框模式下删除命中的框，温度线模式下删除命中的线。 */
    fun deleteAnnotationAt(x: Float, y: Float) = handler.post {
        if (_measureMode.value) {
            val hit = regions.indexOfFirst { r ->
                x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h
            }
            if (hit >= 0) {
                regions.removeAt(hit)
                _measureRegions.value = regions.toList()
                return@post
            }
        }
        if (_lineMode.value) {
            val hit = tempLineList.indexOfFirst { ln -> distToSegment(x, y, ln) < 12f }
            if (hit >= 0) {
                tempLineList.removeAt(hit)
                _tempLines.value = tempLineList.toList()
            }
        }
    }

    private fun distToSegment(px: Float, py: Float, ln: TempLine): Float {
        val dx = (ln.x2 - ln.x1).toDouble()
        val dy = (ln.y2 - ln.y1).toDouble()
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0
        else (((px - ln.x1) * dx + (py - ln.y1) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ln.x1 + t * dx
        val cy = ln.y1 + t * dy
        return kotlin.math.hypot(px - cx, py - cy).toFloat()
    }

    /**
     * 刷新测温框统计与温度线采样点（在 handler 线程、统计节流时调用）。
     * 真实温度矩阵优先，否则用灰度均值/灰度值回退。
     */
    private fun refreshAnnotationStats(gray: Mat) {
        if (regions.isNotEmpty()) {
            val m = tempProvider?.currentMatrix()
            val mw = tempProvider?.matrixWidth ?: 0
            val mh = tempProvider?.matrixHeight ?: 0
            val gw = gray.cols()
            val gh = gray.rows()
            val updated = regions.map { r ->
                var meanC: Double? = null
                var maxC: Double? = null
                var minC: Double? = null
                if (m != null && mw > 0 && mh > 0 && gw > 0 && gh > 0) {
                    val x0 = (r.x.toLong() * mw / gw).toInt().coerceIn(0, mw - 1)
                    val x1 = (((r.x + r.w).toLong() * mw / gw) - 1).toInt().coerceIn(0, mw - 1)
                    val y0 = (r.y.toLong() * mh / gh).toInt().coerceIn(0, mh - 1)
                    val y1 = (((r.y + r.h).toLong() * mh / gh) - 1).toInt().coerceIn(0, mh - 1)
                    var mx = Double.NEGATIVE_INFINITY
                    var mn = Double.POSITIVE_INFINITY
                    var sum = 0.0
                    var n = 0
                    for (row in y0..y1) {
                        val base = row * mw
                        for (c in x0..x1) {
                            val v = m[base + c].toDouble()
                            if (v > mx) mx = v
                            if (v < mn) mn = v
                            sum += v
                            n++
                        }
                    }
                    if (n > 0) {
                        meanC = sum / n
                        maxC = mx
                        minC = mn
                    }
                }
                var meanG: Double? = null
                if (gw > 0 && gh > 0) {
                    val gx0 = r.x.coerceIn(0, gw - 1)
                    val gx1 = (r.x + r.w).coerceIn(1, gw)
                    val gy0 = r.y.coerceIn(0, gh - 1)
                    val gy1 = (r.y + r.h).coerceIn(1, gh)
                    if (gx1 > gx0 && gy1 > gy0) {
                        val sub = gray.submat(gy0, gy1, gx0, gx1)
                        meanG = Core.mean(sub).`val`[0]
                        sub.release()
                    }
                }
                r.copy(meanC = meanC, maxC = maxC, minC = minC, meanG = meanG)
            }
            regions.clear()
            regions.addAll(updated)
            _measureRegions.value = updated
        }
        if (tempLineList.isNotEmpty()) {
            val m = tempProvider?.currentMatrix()
            val mw = tempProvider?.matrixWidth ?: 0
            val mh = tempProvider?.matrixHeight ?: 0
            val gw = gray.cols()
            val gh = gray.rows()
            val n = lineSamplePoints
            val updatedLines = tempLineList.map { ln ->
                val pts = ArrayList<TempLinePoint>(n)
                for (i in 0 until n) {
                    val t = if (n <= 1) 0.0 else i.toDouble() / (n - 1)
                    val x = (ln.x1 + (ln.x2 - ln.x1) * t).toInt()
                    val y = (ln.y1 + (ln.y2 - ln.y1) * t).toInt()
                    var temp: Double? = null
                    var grayV: Int? = null
                    if (m != null && mw > 0 && mh > 0 && x in 0 until mw && y in 0 until mh) {
                        temp = m[y * mw + x].toDouble()
                    } else if (x in 0 until gw && y in 0 until gh) {
                        val buf = ByteArray(1)
                        gray.get(y, x, buf)
                        grayV = buf[0].toInt() and 0xFF
                    }
                    pts.add(TempLinePoint(x, y, temp, grayV))
                }
                ln.copy(points = pts)
            }
            tempLineList.clear()
            tempLineList.addAll(updatedLines)
            _tempLines.value = updatedLines
            // 分布图数据 2s 刷新一次
            val now = System.currentTimeMillis()
            if (now - lastChartUpdateMs >= 2000) {
                lastChartUpdateMs = now
                _tempLineChart.value = updatedLines
            }
        }
        // 单点测温：采样各点温度 + 追加各点时间线（2s 一次，最长 30 分钟）
        val pts = _measurePoints.value
        if (pts.isNotEmpty()) {
            val m = tempProvider?.currentMatrix()
            val mw = tempProvider?.matrixWidth ?: 0
            val mh = tempProvider?.matrixHeight ?: 0
            val gw = gray.cols()
            val gh = gray.rows()
            val updated = pts.map { pt ->
                var tempC: Double? = null
                if (m != null && mw > 0 && mh > 0 && gw > 0 && gh > 0) {
                    val mx = (pt.x.toLong() * mw / gw).toInt().coerceIn(0, mw - 1)
                    val my = (pt.y.toLong() * mh / gh).toInt().coerceIn(0, mh - 1)
                    tempC = m[my * mw + mx].toDouble()
                } else if (gw > 0 && gh > 0 && pt.x in 0 until gw && pt.y in 0 until gh) {
                    val buf = ByteArray(1)
                    gray.get(pt.y, pt.x, buf)
                    val cal = calib
                    if (cal != null) {
                        tempC = cal.toCelsius((buf[0].toInt() and 0xFF).toDouble())
                    }
                }
                pt.copy(tempC = tempC)
            }
            _measurePoints.value = updated
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPointSampleMs >= 2000) {
                lastPointSampleMs = nowMs
                val tl = _pointTimeline.value.toMutableList()
                for (p in updated) {
                    val t = p.tempC
                    if (t != null) tl.add(PointSample(nowMs, p.id, t))
                }
                // 裁剪到 30 分钟
                while (tl.size > 2 && nowMs - tl.first().timeMs > pointTimelineMaxMs) {
                    tl.removeAt(0)
                }
                _pointTimeline.value = tl
            }
        }
        // 时间曲线导出采样（2s）
        maybeAppendExportSample()
    }

    // ---------------- 时间曲线导出（CSV，用户选择保存位置） ----------------

    /** @param uri 用户通过系统文件选择器选定的保存位置（CreateDocument 授权）。 */
    fun startCsv(uri: Uri) {
        handler.post {
            if (csv != null) return@post
            // 申请持久写权限，保证停止时（或进程重建后）仍能写入该位置
            runCatching {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            csv = CsvRecorder(getApplication<Application>().contentResolver, uri)
            lastExportSampleMs = 0L
            _csvEnabled.value = true
            _csvFile.value = uri.toString()
            _captureInfo.value = "记录中…"
            Log.i(TAG, "时间曲线记录开始: $uri")
        }
    }

    fun stopCsv() {
        handler.post { stopCsvInternal() }
    }

    /** 停止记录并保存 CSV（断开连接时也会调用）。 */
    private fun stopCsvInternal() {
        val r = csv ?: return
        csv = null
        _csvEnabled.value = false
        runCatching { r.save() }
            .onSuccess {
                val name = r.uri().lastPathSegment ?: r.uri().toString()
                _csvFile.value = r.uri().toString()
                _captureInfo.value = "已导出: $name"
                Log.i(TAG, "时间曲线已导出: ${r.uri()}")
            }
            .onFailure { e ->
                _error.value = "导出失败: ${e.message}"
                _captureInfo.value = "导出失败"
            }
    }

    /**
     * 每 2s 把当前标注快照追加进记录器（点温 / 温度线均值 / 温度框 max-min-avg）。
     * 在 refreshAnnotationStats（200ms 节流）末尾调用。
     */
    private fun maybeAppendExportSample() {
        val r = csv ?: return
        val now = System.currentTimeMillis()
        if (now - lastExportSampleMs < 2000) return
        lastExportSampleMs = now

        val pointTemps = _measurePoints.value.map { it.tempC }
        val lineAvgs = tempLineList.map { ln ->
            val ts = ln.points.mapNotNull { it.temp }
            if (ts.isEmpty()) Double.NaN else ts.average()
        }
        val regionStats = regions.map {
            Triple(it.maxC ?: Double.NaN, it.minC ?: Double.NaN, it.meanC ?: Double.NaN)
        }
        r.addSample(now, pointTemps, lineAvgs, regionStats)
    }

    companion object {
        private const val TAG = "LiveViewModel"
    }
}
