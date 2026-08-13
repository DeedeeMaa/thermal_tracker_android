package com.thermal.tracker.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 解析海康全屏测温的 P2P 附加数据为温度矩阵。
 *
 * 对应 PC 版 hik_sdk.py 的 capture_with_temperature()：
 * 温度数据为 **little-endian float32**，按行优先排列，共 width*height 个，单位 ℃。
 *
 * 该解析与具体 SDK 版本无关，只需抓拍返回的原始字节。
 */
object TempMatrixParser {

    /**
     * 从 P2P 原始字节解析温度矩阵。
     *
     * @param raw    抓拍返回的附加温度数据字节
     * @param width  探测器宽度（如 384）
     * @param height 探测器高度（如 288）
     * @return 行优先的 FloatArray（长度 width*height），数据不足或非法时返回 null
     */
    fun parse(raw: ByteArray, width: Int, height: Int): FloatArray? {
        if (width <= 0 || height <= 0 || raw.isEmpty()) return null
        // 完整矩阵需要 width*height 个 float(4字节)；不足则取能解析的完整 float 数
        val count = minOf(width * height, raw.size / 4)
        if (count <= 0) return null
        val buf = ByteBuffer.wrap(raw, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        val temps = FloatArray(count)
        buf.asFloatBuffer().get(temps)
        return temps
    }
}
