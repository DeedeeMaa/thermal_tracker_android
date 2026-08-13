package com.thermal.tracker.model

/** 相机连接配置（对应 Python 版 config.py 的 CameraConfig）。 */
data class CameraConfig(
    var ip: String = "192.168.2.104",
    var rtspPort: Int = 554,
    var username: String = "admin",
    var password: String = "asd37210",
    var channel: Int = 101,
    var rtspTransport: String = "tcp", // tcp | udp
    var frameWidth: Int = 384,          // 热成像主码流分辨率
    var frameHeight: Int = 288,
) {
    fun getRtspUrls(): List<String> {
        val urls = mutableListOf<String>()
        val chIndex = if (channel >= 100) channel / 100 else channel
        // 尝试主码流 101 和子码流 102
        val channels = listOf(channel, (chIndex * 100) + 2)
        
        val auth = if (password.isNotEmpty()) "$username:$password@" else ""
        val host = ip
        val portSuffix = if (rtspPort == 554) "" else ":$rtspPort"

        channels.forEach { ch ->
            val base = "$auth$host$portSuffix"
            urls.add("rtsp://$base/ISAPI/streaming/channels/$ch")
            urls.add("rtsp://$base/Streaming/Channels/$ch")
            urls.add("rtsp://$base/ch$chIndex/main/av_stream")
        }
        
        return urls.distinct()
    }
}

/** 检测参数（对应 DetectionConfig）。 */
data class DetectionConfig(
    var threshMode: String = "percentile",  // percentile | fixed
    var fixedThresh: Int = 200,
    var percentile: Double = 97.0,
    var minIntensity: Int = 40,
    var blurKsize: Int = 5,
    var morphKsize: Int = 3,
    var minArea: Int = 25,
    var mergeIou: Double = 0.5,
)

/** 追踪参数（对应 TrackerConfig）。 */
data class TrackerConfig(
    var matchDist: Double = 80.0,
    var maxMiss: Int = 20,
    var minHits: Int = 2,
    var historyLen: Int = 40,
    var velAlpha: Double = 0.6,
)

/** 温度标定参数（对应 CalibrationConfig）。 */
data class CalibrationConfig(
    var enabled: Boolean = true,
    var useTwoPoint: Boolean = false,
    var tmin: Double = -20.0,
    var tmax: Double = 150.0,
    var ref1Intensity: Int = 0,
    var ref1Celsius: Double = -20.0,
    var ref2Intensity: Int = 255,
    var ref2Celsius: Double = 150.0,
)

/** CSV 输出模式。 */
enum class CsvMode { HOTSPOT, FULL_MATRIX }

/** CSV 热力表格导出配置（采样周期 0.05~5s）。 */
data class CsvConfig(
    var enabled: Boolean = false,
    var samplePeriodMs: Long = 500,   // 50..5000 (0.05s~5s)
    var mode: CsvMode = CsvMode.HOTSPOT,
    var matrixDownsample: Int = 2,    // FULL_MATRIX 模式下每 n 像素采样一个
)

/** 全局配置。 */
data class AppConfig(
    var camera: CameraConfig = CameraConfig(),
    var detection: DetectionConfig = DetectionConfig(),
    var tracker: TrackerConfig = TrackerConfig(),
    var calibration: CalibrationConfig = CalibrationConfig(),
    var csv: CsvConfig = CsvConfig(),
    var palette: String = "ironbow",  // ironbow | whitehot | inferno | jet
)
