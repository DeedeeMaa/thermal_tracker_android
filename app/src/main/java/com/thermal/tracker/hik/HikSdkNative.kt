package com.thermal.tracker.hik

import android.util.Log
import com.hcnetsdk.jna.HCNetSDKByJNA
import com.hcnetsdk.jna.HCNetSDKJNAInstance
import com.sun.jna.Memory
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.thermal.tracker.data.TempMatrixParser

/**
 * 海康 HCNetSDK for Android 真实实现（JNA 封装，对应 PC 版 hik_sdk.py）。
 *
 * 依赖：
 *  - app/libs/HCNetSDK.jar、jna.jar（SDK 的 JNA 封装）
 *  - app/src/main/jniLibs/<abi>/ 下的 libhcnetsdk.so 及配套 so
 *
 * 功能：
 *  - 登录/登出（端口 8000，byLoginMode=0 走 SDK 登录）
 *  - 全屏测温：NET_DVR_CaptureJPEGPicture_WithAppendData -> P2P 温度矩阵（℃）
 *  - 调焦：模式（3305/3306 配置）+ 电机（PTZ FOCUS_NEAR/FAR）
 */
class HikSdkNative(
    private val ip: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val sdkChannel: Int = 1,
) : ThermalSdk {

    override val name = "HikSDK"
    override val tempMatrixWidth = 384
    override val tempMatrixHeight = 288

    // 与 PC 版 hik_sdk.py 一致的常量
    private val netDvrGetFocusModeCfg = 3305
    private val netDvrSetFocusModeCfg = 3306
    private val focusNear = 13
    private val focusFar = 14

    private val hik: HCNetSDKByJNA = HCNetSDKJNAInstance.getInstance()
    private var userId = -1

    override fun login(ip: String, port: Int, username: String, password: String): Boolean {
        return try {
            if (!hik.NET_DVR_Init()) {
                Log.e(TAG, "NET_DVR_Init 失败 err=${hik.NET_DVR_GetLastError()}")
                return false
            }

            val loginInfo = HCNetSDKByJNA.NET_DVR_USER_LOGIN_INFO()
            val ipB = ip.toByteArray()
            val userB = username.toByteArray()
            val pwdB = password.toByteArray()
            System.arraycopy(ipB, 0, loginInfo.sDeviceAddress, 0, ipB.size.coerceAtMost(loginInfo.sDeviceAddress.size))
            System.arraycopy(userB, 0, loginInfo.sUserName, 0, userB.size.coerceAtMost(loginInfo.sUserName.size))
            System.arraycopy(pwdB, 0, loginInfo.sPassword, 0, pwdB.size.coerceAtMost(loginInfo.sPassword.size))
            loginInfo.wPort = port.toShort()
            loginInfo.byLoginMode = 0 // 0 = SDK 登录（8000），1 = ISAPI 登录（80）
            loginInfo.write()

            val deviceInfo = HCNetSDKByJNA.NET_DVR_DEVICEINFO_V40()
            var uid = hik.NET_DVR_Login_V40(loginInfo.getPointer(), deviceInfo.getPointer())
            if (uid < 0) {
                val err40 = hik.NET_DVR_GetLastError()
                Log.w(TAG, "NET_DVR_Login_V40 失败 err=$err40，尝试 V30（同 PC 版 hik_sdk.py）")
                // 回退 V30：PC 版用 NET_DVR_Login_V30 登录成功，个别相机 V40 不支持
                val dev30 = HCNetSDKByJNA.NET_DVR_DEVICEINFO_V30()
                uid = hik.NET_DVR_Login_V30(
                    ip.toByteArray(), port.toShort(),
                    username.toByteArray(), password.toByteArray(),
                    dev30.getPointer()
                )
                if (uid < 0) {
                    Log.e(TAG, "NET_DVR_Login_V30 也失败 err=${hik.NET_DVR_GetLastError()}")
                    return false
                }
            }
            deviceInfo.read()
            userId = uid
            Log.i(TAG, "海康 SDK 登录成功 userId=$uid")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "海康 SDK 登录异常", t)
            false
        }
    }

    override fun logout() {
        try {
            if (userId >= 0) {
                hik.NET_DVR_Logout(userId)
                userId = -1
            }
            hik.NET_DVR_Cleanup()
        } catch (t: Throwable) {
            Log.w(TAG, "登出异常", t)
        }
    }

    override fun lastError(): Int = try { hik.NET_DVR_GetLastError() } catch (_: Throwable) { -1 }

    // ---------------- 调焦 ----------------

    override fun setFocusMode(mode: Int): Boolean {
        return try {
            if (userId < 0) return false
            val cfg = FocusModeCfg()
            cfg.dwSize = cfg.size()
            val bytesReturned = IntByReference()
            if (!hik.NET_DVR_GetDVRConfig(
                    userId, netDvrGetFocusModeCfg, sdkChannel,
                    cfg.pointer, cfg.size(), bytesReturned
                )
            ) {
                Log.w(TAG, "读取聚焦配置失败 err=${hik.NET_DVR_GetLastError()}")
                return false
            }
            cfg.read()
            cfg.byFocusMode = mode.toByte() // 0-自动 1-手动 2-半自动
            cfg.dwSize = cfg.size()
            cfg.write()
            val ok = hik.NET_DVR_SetDVRConfig(
                userId, netDvrSetFocusModeCfg, sdkChannel, cfg.pointer, cfg.size()
            )
            Log.i(TAG, "setFocusMode($mode) -> $ok")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "setFocusMode 异常", t)
            false
        }
    }

    override fun focusNearStart(): Boolean {
        ensureManualMode()
        return ptzFocus(focusNear, 0)
    }

    override fun focusNearStop(): Boolean = stopFocusMotor()

    override fun focusFarStart(): Boolean {
        ensureManualMode()
        return ptzFocus(focusFar, 0)
    }

    override fun focusFarStop(): Boolean = stopFocusMotor()

    /**
     * 电机调焦前先确保手动聚焦模式：
     * 相机在自动对焦模式下会忽略 PTZ 调焦命令（PC 版也是手动模式下才按住调近/调远）。
     */
    private fun ensureManualMode() {
        try {
            if (userId >= 0) {
                val ok = setFocusMode(1) // 1 = 手动
                Log.i(TAG, "ensureManualMode setFocusMode(1) -> $ok")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ensureManualMode 异常", t)
        }
    }

    /**
     * 停止电机调焦：同时停近/远。
     * 与 PC 版 focus_motor_stop 完全一致：用 NET_DVR_PTZControl_Other 发停止（不带速度），
     * 之前用 PTZControlWithSpeed_Other 停止对这台相机无效。
     */
    private fun stopFocusMotor(): Boolean {
        return try {
            if (userId < 0) return false
            val ok = hik.NET_DVR_PTZControl_Other(userId, sdkChannel, focusNear, 1)
            val ok2 = hik.NET_DVR_PTZControl_Other(userId, sdkChannel, focusFar, 1)
            Log.i(TAG, "stopFocusMotor -> $ok / $ok2 err=${hik.NET_DVR_GetLastError()}")
            ok || ok2
        } catch (t: Throwable) {
            Log.e(TAG, "stopFocusMotor 异常", t)
            false
        }
    }

    /**
     * 电机调焦：stop=0 启动，1 停止。
     * 启动与 PC 版 focus_motor_start 一致：PTZControlWithSpeed_Other(cmd, 0, speed=2)。
     */
    private fun ptzFocus(command: Int, stop: Int): Boolean {
        return try {
            if (userId < 0) return false
            val ok = hik.NET_DVR_PTZControlWithSpeed_Other(userId, sdkChannel, command, stop, 2)
            Log.i(TAG, "ptzFocus(cmd=$command, stop=$stop) -> $ok err=${hik.NET_DVR_GetLastError()}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "ptzFocus 异常", t)
            false
        }
    }

    override fun getFocusPosition(): Float? {
        return try {
            if (userId < 0) return null
            val cfg = FocusModeCfg()
            cfg.dwSize = cfg.size()
            val bytesReturned = IntByReference()
            if (!hik.NET_DVR_GetDVRConfig(
                    userId, netDvrGetFocusModeCfg, sdkChannel,
                    cfg.pointer, cfg.size(), bytesReturned
                )
            ) return null
            cfg.read()
            // dwRelativeFocusPos & 0xFFFF 原始值（PC 实测可达 36213，非 0~4000）
            (cfg.dwRelativeFocusPos.toLong() and 0xFFFFL).toFloat()
        } catch (t: Throwable) {
            Log.e(TAG, "getFocusPosition 异常", t)
            null
        }
    }

    override fun getFocusInfo(): Pair<Int, Float>? {
        return try {
            if (userId < 0) return null
            val cfg = FocusModeCfg()
            cfg.dwSize = cfg.size()
            val bytesReturned = IntByReference()
            if (!hik.NET_DVR_GetDVRConfig(
                    userId, netDvrGetFocusModeCfg, sdkChannel,
                    cfg.pointer, cfg.size(), bytesReturned
                )
            ) {
                Log.w(TAG, "getFocusInfo 读取失败 err=${hik.NET_DVR_GetLastError()}")
                return null
            }
            cfg.read()
            val mode = cfg.byFocusMode.toInt() and 0xFF
            val pos = (cfg.dwRelativeFocusPos.toLong() and 0xFFFFL).toFloat()
            mode to pos
        } catch (t: Throwable) {
            Log.e(TAG, "getFocusInfo 异常", t)
            null
        }
    }

    // ---------------- 全屏测温 ----------------

    override fun captureTemperatureMatrix(): FloatArray? {
        return try {
            if (userId < 0) return null
            val info = HCNetSDKByJNA.NET_DVR_JPEGPICTURE_WITH_APPENDDATA()
            info.dwSize = info.size()
            info.dwChannel = sdkChannel
            val bufSize = 4 * 1024 * 1024
            val jpegBuf = Memory(bufSize.toLong())
            val p2pBuf = Memory(bufSize.toLong())
            info.pJpegPicBuff = jpegBuf
            info.pP2PDataBuff = p2pBuf
            info.write()

            if (!hik.NET_DVR_CaptureJPEGPicture_WithAppendData(userId, sdkChannel, info.getPointer())) {
                Log.w(TAG, "NET_DVR_CaptureJPEGPicture_WithAppendData 失败 err=${hik.NET_DVR_GetLastError()}")
                return null
            }
            info.read()

            val w = info.dwJpegPicWidth
            val h = info.dwJpegPicHeight
            val n = info.dwP2PDataLen
            if (n <= 0 || w <= 0 || h <= 0) return null

            val raw = ByteArray(n)
            info.pP2PDataBuff.read(0, raw, 0, n)
            // 优先用返回的宽高，失败再用默认 384x288
            TempMatrixParser.parse(raw, w, h) ?: TempMatrixParser.parse(raw, tempMatrixWidth, tempMatrixHeight)
        } catch (t: Throwable) {
            Log.e(TAG, "captureTemperatureMatrix 异常", t)
            null
        }
    }

    companion object {
        private const val TAG = "HikSdkNative"

        /**
         * NET_DVR_FOCUSMODE_CFG（JNA 未内置此结构，按 PC 版 hik_sdk.py 字段自定义）。
         * 字段顺序与布局必须与 PC 一致，否则 3305/3306 配置读写失败。
         */
        class FocusModeCfg : Structure() {
            @JvmField var dwSize: Int = 0
            @JvmField var byFocusMode: Byte = 0
            @JvmField var byAutoFocusMode: Byte = 0
            @JvmField var wMinFocusDistance: Short = 0
            @JvmField var byZoomSpeedLevel: Byte = 0
            @JvmField var byFocusSpeedLevel: Byte = 0
            @JvmField var byOpticalZoom: Byte = 0
            @JvmField var byDigtitalZoom: Byte = 0
            @JvmField var fOpticalZoomLevel: Float = 0f
            @JvmField var dwFocusPos: Int = 0
            @JvmField var byFocusDefinitionDisplay: Byte = 0
            @JvmField var byFocusSensitivity: Byte = 0
            @JvmField var byRes1: ByteArray = ByteArray(2)
            @JvmField var dwRelativeFocusPos: Int = 0
            @JvmField var byRes: ByteArray = ByteArray(48)

            override fun getFieldOrder(): List<String> {
                return listOf(
                    "dwSize", "byFocusMode", "byAutoFocusMode", "wMinFocusDistance",
                    "byZoomSpeedLevel", "byFocusSpeedLevel", "byOpticalZoom", "byDigtitalZoom",
                    "fOpticalZoomLevel", "dwFocusPos", "byFocusDefinitionDisplay", "byFocusSensitivity",
                    "byRes1", "dwRelativeFocusPos", "byRes"
                )
            }
        }
    }
}
