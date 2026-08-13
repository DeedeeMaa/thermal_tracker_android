package com.thermal.tracker.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通过 SurfaceTexture + EGL 离屏渲染抓取 ExoPlayer 解码后的视频帧。
 *
 * 原理：
 *   ExoPlayer 把 H.264 帧渲染到 [surfaceTexture] 对应的 Surface；
 *   本类在 EGL 离屏（pbuffer）上下文中用 OES 纹理做 YUV->RGB 转换，
 *   再 glReadPixels 读出 RGBA，封装为 OpenCV Mat。
 *
 * 注意：必须在创建它的同一线程（HandlerThread）上调用 [grabFrame]。
 * 宽高需与码流分辨率一致（默认 384x288）。
 */
class FrameGrabber(private val width: Int, private val height: Int) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0

    private val frameAvailable = AtomicBoolean(false)
    private val texMatrix = FloatArray(16)

    private val stMatrixLoc: Int
    private val positionLoc: Int
    private val texCoordLoc: Int
    private val samplerLoc: Int

    val surfaceTexture: SurfaceTexture

    private val pixelData = ByteArray(width * height * 4)
    private val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    )

    private val quadBuffer: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(quadVertices); position(0) }
    }

    init {
        if (!setupEgl()) throw IllegalStateException("EGL 初始化失败")
        textureId = createExternalTexture()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) throw IllegalStateException("GL 着色器编译失败")

        stMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        samplerLoc = GLES20.glGetUniformLocation(program, "sTexture")

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener { 
            if (!frameAvailable.get()) {
                android.util.Log.d("FrameGrabber", "First frame available!")
            }
            frameAvailable.set(true) 
        }
    }

    fun isReady(): Boolean = textureId != 0

    fun hasNewFrame(): Boolean = frameAvailable.get()

    /** 抓取一帧（RGBA Mat），无新帧返回 null。 */
    fun grabFrame(): Mat? {
        if (textureId == 0) return null
        
        // 既然 Looper 已经通过 postDelayed 保持空闲，
        // 我们可以恢复依赖 frameAvailable 标志位，避免无效渲染。
        if (!frameAvailable.getAndSet(false)) return null

        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
        } catch (e: Exception) {
            android.util.Log.w("FrameGrabber", "updateTexImage failed: ${e.message}")
            return null
        }

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(stMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLoc, 0)

        GLES20.glViewport(0, 0, width, height)
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(positionLoc)
        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(texCoordLoc)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        pixelBuffer.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
        pixelBuffer.rewind()
        pixelBuffer.get(pixelData)

        val mat = Mat(height, width, CvType.CV_8UC4)
        mat.put(0, 0, pixelData)
        Core.flip(mat, mat, 0) // glReadPixels 是自下而上的，翻转回来

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return mat
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        try {
            surfaceTexture.release()
        } catch (_: Exception) {
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    // ---------------- GL 工具 ----------------

    private fun setupEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfig, 0) || numConfig[0] == 0) {
            return false
        }
        val config = configs[0]!!

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun createExternalTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return if (status[0] == 0) {
            GLES20.glDeleteProgram(p)
            0
        } else {
            p
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
