package com.thermal.tracker.hik

/**
 * 海康相机控制抽象接口。
 *
 * 两个实现：
 *  - [MockThermalSdk]：纯软件模拟（无相机时也能跑通整条链路/CSV 导出）
 *  - [HikSdkNative]：真实海康 HCNetSDK for Android 接入占位（需 .so + jar）
 *
 * 对应 Python 版 hik_sdk.py 的功能：登录、调焦、全屏测温抓拍。
 */
interface ThermalSdk {
    /** 实现名（用于 UI 展示）。 */
    val name: String

    fun login(ip: String, port: Int, username: String, password: String): Boolean
    fun logout()

    /** 最近一次 SDK 调用的错误码（海康 NET_DVR_GetLastError），无错误为 0。 */
    fun lastError(): Int

    /** 聚焦模式：0=自动 1=手动 2=半自动（与海康 NET_DVR_FOCUSMODE_CFG 一致）。 */
    fun setFocusMode(mode: Int): Boolean

    fun focusNearStart(): Boolean
    fun focusNearStop(): Boolean
    fun focusFarStart(): Boolean
    fun focusFarStop(): Boolean

    /**
     * 当前焦点位置：返回 dwRelativeFocusPos & 0xFFFF 的原始值（非归一化，便于诊断对比），
     * 不可用时返回 null。
     */
    fun getFocusPosition(): Float?

    /**
     * 读取聚焦信息：返回 (聚焦模式, dwRelativeFocusPos & 0xFFFF 原始值)。
     * 聚焦模式 0=自动 1=手动 2=半自动。读取失败返回 null。
     */
    fun getFocusInfo(): Pair<Int, Float>?

    /**
     * 抓拍并解析全屏温度矩阵（℃），按行优先铺平，长度 = tempMatrixWidth * tempMatrixHeight。
     * 对应 PC 版 NET_DVR_CaptureJPEGPicture_WithAppendData 的 JPEG 附加温度数据解析。
     */
    fun captureTemperatureMatrix(): FloatArray?

    val tempMatrixWidth: Int
    val tempMatrixHeight: Int
}
