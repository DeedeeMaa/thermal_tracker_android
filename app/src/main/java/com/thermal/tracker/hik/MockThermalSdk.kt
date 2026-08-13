package com.thermal.tracker.hik

import org.opencv.core.Mat

/**
 * 软件模拟的测温 SDK：
 * 用最近一帧灰度图按量程线性映射生成温度矩阵，
 * 让整条处理链路（含 CSV 全矩阵导出）在没有相机时也能开发调试。
 */
class MockThermalSdk(
    private val grayProvider: () -> Mat?,
    private val tMin: Float = -20f,
    private val tMax: Float = 150f,
) : ThermalSdk {

    override val name = "Mock(模拟)"
    override val tempMatrixWidth = 384
    override val tempMatrixHeight = 288

    private var loggedIn = false
    private var focusMode = 1

    override fun login(ip: String, port: Int, username: String, password: String): Boolean {
        loggedIn = true
        return true
    }

    override fun logout() {
        loggedIn = false
    }

    override fun lastError(): Int = 0

    override fun setFocusMode(mode: Int): Boolean {
        focusMode = mode
        return true
    }

    override fun focusNearStart(): Boolean = true
    override fun focusNearStop(): Boolean = true
    override fun focusFarStart(): Boolean = true
    override fun focusFarStop(): Boolean = true

    override fun getFocusPosition(): Float? = 500f

    override fun getFocusInfo(): Pair<Int, Float>? = focusMode to 500f

    override fun captureTemperatureMatrix(): FloatArray? {
        val gray = grayProvider() ?: return null
        val n = gray.total().toInt()
        if (n <= 0) return null
        val buf = ByteArray(n)
        gray.get(0, 0, buf)
        val arr = FloatArray(n)
        val span = tMax - tMin
        for (i in 0 until n) {
            arr[i] = tMin + (buf[i].toInt() and 0xFF) * span / 255f
        }
        return arr
    }
}
