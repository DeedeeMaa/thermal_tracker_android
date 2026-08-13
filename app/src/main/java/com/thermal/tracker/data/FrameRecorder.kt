package com.thermal.tracker.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * 录像器：把处理后的 BGR Mat 编码为 H.264 MP4。
 *
 * 原理：
 *  BGR -> YV12（OpenCV）-> NV12（手动交错）-> MediaCodec 编码 -> MediaMuxer 封装。
 * 仅支持偶数宽高（384x288 满足）。
 *
 * 说明：这是骨架级实现，帧率固定、码率固定；如需更高质量可改用
 * MediaCodec 输入 Surface + GL 渲染路径。
 */
class FrameRecorder(
    private val path: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 15,
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var ptsUs = 0L
    private val frameIntervalUs = 1_000_000L / fps
    private val yuvBuf = ByteArray(width * height * 3 / 2)
    private val nv12Buf = ByteArray(width * height * 3 / 2)

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** 输入一帧 BGR Mat，编码为 H.264。 */
    fun encodeFrame(bgr: Mat) {
        val yuv = Mat()
        Imgproc.cvtColor(bgr, yuv, Imgproc.COLOR_BGR2YUV_YV12)
        yuv.get(0, 0, yuvBuf)
        yuv.release()

        // YV12(Y,V,U) -> NV12(Y, UV 交错)
        val ySize = width * height
        val uvSize = ySize / 4
        val uvRow = width / 2
        val uvH = height / 2
        val vOff = ySize
        val uOff = ySize + uvSize
        System.arraycopy(yuvBuf, 0, nv12Buf, 0, ySize)
        var dst = ySize
        for (row in 0 until uvH) {
            var u = uOff + row * uvRow
            var v = vOff + row * uvRow
            for (col in 0 until uvRow) {
                nv12Buf[dst++] = yuvBuf[u++]
                nv12Buf[dst++] = yuvBuf[v++]
            }
        }

        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val buf: ByteBuffer = codec.getInputBuffer(inIdx)!!
            buf.clear()
            buf.put(nv12Buf, 0, ySize + uvSize * 2)
            codec.queueInputBuffer(inIdx, 0, ySize + uvSize * 2, ptsUs, 0)
            ptsUs += frameIntervalUs
        }
        drain(false)
    }

    fun stop() {
        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIdx >= 0 -> {
                    if (muxerStarted) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, buf, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
