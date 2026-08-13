package com.thermal.tracker.camera

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import org.opencv.core.Mat

/**
 * RTSP 取流：ExoPlayer 解码（TCP 传输、自动重连），
 * 通过 [FrameGrabber] 抓取原始帧给 OpenCV 处理。
 *
 * 对应 Python 版 camera.py 的功能。
 */
class RtspStream(
    private val context: Context,
    private val url: String,
    private val frameW: Int,
    private val frameH: Int,
    private val useTcp: Boolean = true,
    private val onError: (String) -> Unit = {},
) {
    private var player: ExoPlayer? = null
    private var grabber: FrameGrabber? = null

    /** 开始播放，失败返回 false。 */
    fun start(): Boolean {
        android.util.Log.d("RtspStream", "Starting stream with URL: $url (TCP=$useTcp)")
        return try {
            val g = FrameGrabber(frameW, frameH)
            grabber = g

            // 使用 ExoPlayer 默认缓冲配置（已知对海康相机最稳，勿随意改动）
            val p = ExoPlayer.Builder(context).build()
            player = p
            p.setVideoSurface(Surface(g.surfaceTexture))
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val detail = when (error.errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "网络连接异常"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "认证失败或路径错误"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "无法连接到服务器"
                        else -> error.message
                    }
                    android.util.Log.e("RtspStream", "ExoPlayer Error [${error.errorCode}]: $detail", error)
                    onError("码流错误: $detail")
                }
                override fun onPlaybackStateChanged(state: Int) {
                    val s = when(state) {
                        ExoPlayer.STATE_BUFFERING -> "BUFFERING"
                        ExoPlayer.STATE_READY -> "READY"
                        ExoPlayer.STATE_ENDED -> "ENDED"
                        ExoPlayer.STATE_IDLE -> "IDLE"
                        else -> "UNKNOWN"
                    }
                    android.util.Log.d("RtspStream", "Player state: $s")
                }
            })

            val factory = RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
            p.setMediaSource(factory.createMediaSource(MediaItem.fromUri(url)))
            p.prepare()
            p.playWhenReady = true
            true
        } catch (e: Exception) {
            android.util.Log.e("RtspStream", "Failed to start player", e)
            release()
            false
        }
    }

    fun hasNewFrame(): Boolean = grabber?.hasNewFrame() ?: false

    /** 抓取一帧 RGBA Mat，无新帧返回 null。 */
    fun grabFrame(): Mat? = grabber?.grabFrame()

    fun release() {
        player?.let {
            it.playWhenReady = false
            it.release()
        }
        player = null
        grabber?.release()
        grabber = null
    }
}
