package com.kemzy.liveavatar.renderer

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Native-mesh portrait renderer. Native C++ owns mesh storage; direct JNI
 * ByteBuffers expose that storage long enough for OpenGL to copy it into VBO/EBO.
 * Camera frames are never accepted as portrait textures.
 *
 * Use with a GLSurfaceView configured for an OpenGL ES 3 context.
 */
class PortraitGlRenderer(private var portrait: Bitmap) : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "KemzyGL"
        private const val GRID_COLUMNS = 17
        private const val GRID_ROWS = 17
        private const val FLOATS_PER_VERTEX = 7
        private const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        private const val INDEX_BYTES = 2

        private const val VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uPortrait;
            void main() {
                gl_FragColor = texture2D(uPortrait, vTexCoord);
            }
        """
    }

    private var program = 0
    private var textureId = 0
    // GL-thread-owned slots: selected avatar, live camera placeholder, segmentation mask.
    private val compositorTextures = IntArray(3)
    private var compositorTexturesReady = false
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var samplerHandle = -1
    private val vaoId = IntArray(1)
    private val vboId = IntArray(1)
    private val eboId = IntArray(1)
    private var vertexCount = 0
    private var indexCount = 0
    private var meshReady = false
    private var nativeVertices: ByteBuffer? = null
    private data class HolisticFrame(
        val face: FloatArray?, val pose: FloatArray?,
        val leftHand: FloatArray?, val rightHand: FloatArray?
    )
    private val pendingHolisticFrame = AtomicReference<HolisticFrame?>(null)
    private val pendingPortrait = AtomicReference<Bitmap?>(null)

    external fun nativeInit()
    external fun nativeSystemDiagnostics(): String
    private external fun nativeInitializeMesh(columns: Int, rows: Int): Boolean
    private external fun nativeMeshVertexCount(): Int
    private external fun nativeMeshIndexCount(): Int
    private external fun nativeVertexBuffer(): ByteBuffer?
    private external fun nativeIndexBuffer(): ByteBuffer?
    private external fun nativeUpdateHolisticTracking(
        face: FloatArray?, pose: FloatArray?, leftHand: FloatArray?, rightHand: FloatArray?
    ): Boolean

    /**
     * Queue normalized MediaPipe XYZ triplets (each coordinate in [0,1] for X/Y).
     * The GL thread consumes only the newest frame to avoid a growing work queue.
     */
    /** Queue a new selected portrait; the GL thread uploads it and releases the prior texture source. */
    fun updatePortrait(bitmap: Bitmap) {
        val safeCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        pendingPortrait.getAndSet(safeCopy)?.let { if (!it.isRecycled) it.recycle() }
    }

    fun updateTrackingData(landmarks: FloatArray) {
        enqueueHolisticFrameData(face = landmarks)
    }

    /** Keep only the newest tracking sample; null means temporarily unavailable. */
    fun enqueueHolisticFrameData(
        face: FloatArray?, pose: FloatArray? = null,
        leftHand: FloatArray? = null, rightHand: FloatArray? = null
    ) {
        if (!validStream(face, 478) || !validStream(pose, 33) ||
            !validStream(leftHand, 21) || !validStream(rightHand, 21)) return
        pendingHolisticFrame.set(
            HolisticFrame(face?.copyOf(), pose?.copyOf(), leftHand?.copyOf(), rightHand?.copyOf())
        )
    }

    private fun validStream(data: FloatArray?, maxLandmarks: Int): Boolean =
        data == null || (data.size % 3 == 0 && data.size / 3 <= maxLandmarks &&
            data.all { it.isFinite() })

    init {
        System.loadLibrary("kemzyliveavatar")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        nativeInit()
        try {
            Log.i(TAG, nativeSystemDiagnostics())
            Log.i(TAG, "==== KÉMZY HARDWARE GPU DIAGNOSTICS ====")
            Log.i(TAG, "GPU vendor   : ${glString(GLES30.GL_VENDOR)}")
            Log.i(TAG, "GPU renderer : ${glString(GLES30.GL_RENDERER)}")
            Log.i(TAG, "OpenGL ES    : ${glString(GLES30.GL_VERSION)}")
            Log.i(TAG, "GLSL version : ${glString(GLES30.GL_SHADING_LANGUAGE_VERSION)}")

            meshReady = nativeInitializeMesh(GRID_COLUMNS, GRID_ROWS)
            vertexCount = nativeMeshVertexCount()
            indexCount = nativeMeshIndexCount()
            Log.i(TAG, "Native mesh initialized: $meshReady")
            Log.i(TAG, "Native mesh vertices: $vertexCount")
            Log.i(TAG, "Native mesh indices: $indexCount")

            if (!meshReady || vertexCount <= 0 || indexCount <= 0) {
                Log.e(TAG, "Native mesh generation failed; mesh draw is disabled")
                meshReady = false
                return
            }

            val vertexBytes = Math.multiplyExact(vertexCount, VERTEX_STRIDE_BYTES)
            val indexBytes = Math.multiplyExact(indexCount, INDEX_BYTES)
            val vertexBuffer = nativeVertexBuffer()?.order(ByteOrder.nativeOrder())
                ?: error("JNI returned no direct vertex buffer")
            nativeVertices = vertexBuffer
            val indexBuffer = nativeIndexBuffer()?.order(ByteOrder.nativeOrder())
                ?: error("JNI returned no direct index buffer")
            require(vertexBuffer.isDirect && vertexBuffer.capacity() >= vertexBytes) {
                "Invalid native vertex buffer: direct=${vertexBuffer.isDirect}, capacity=${vertexBuffer.capacity()}, required=$vertexBytes"
            }
            require(indexBuffer.isDirect && indexBuffer.capacity() >= indexBytes) {
                "Invalid native index buffer: direct=${indexBuffer.isDirect}, capacity=${indexBuffer.capacity()}, required=$indexBytes"
            }
            vertexBuffer.position(0)
            indexBuffer.position(0)

            // Allocate GL objects and upload the native mesh to GPU-owned storage.
            GLES30.glGenVertexArrays(1, vaoId, 0)
            GLES30.glGenBuffers(1, vboId, 0)
            GLES30.glGenBuffers(1, eboId, 0)
            GLES30.glBindVertexArray(vaoId[0])

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, vertexBytes, vertexBuffer, GLES30.GL_STATIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId[0])
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, indexBuffer, GLES30.GL_STATIC_DRAW
            )

            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
                val status = IntArray(1)
                GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = GLES20.glGetProgramInfoLog(it)
                    GLES20.glDeleteProgram(it)
                    error("Portrait shader link failed: $log")
                }
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            samplerHandle = GLES20.glGetUniformLocation(program, "uPortrait")
            check(positionHandle >= 0 && texCoordHandle >= 0 && samplerHandle >= 0) {
                "Required portrait shader inputs were optimized out or not found"
            }

            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glVertexAttribPointer(
                positionHandle, 3, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0
            )
            GLES30.glEnableVertexAttribArray(texCoordHandle)
            GLES30.glVertexAttribPointer(
                texCoordHandle, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 3 * 4
            )
            // EBO binding is VAO state. Do not unbind it until after the VAO is unbound.
            GLES30.glBindVertexArray(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

            initializeHardwareCompositorTextures()

            val glError = GLES30.glGetError()
            check(glError == GLES30.GL_NO_ERROR) { "OpenGL mesh setup failed with error 0x${glError.toString(16)}" }
            Log.i(TAG, "GPU mesh upload complete: $vertexBytes vertex bytes, $indexBytes index bytes")
            Log.i(TAG, "GL_RENDERER=${glString(GLES20.GL_RENDERER)}; GL_VERSION=${glString(GLES20.GL_VERSION)}")
        } catch (error: Throwable) {
            meshReady = false
            Log.e(TAG, "Native mesh / GPU setup failed", error)
            throw error
        }
    }

    /**
     * Allocate the three ES 3 texture slots on the GL thread. Camera and mask
     * slots are 1x1 placeholders only; frame upload, segmentation inference,
     * and the compositing shader are separate follow-up work.
     */
    private fun initializeHardwareCompositorTextures() {
        GLES30.glGenTextures(compositorTextures.size, compositorTextures, 0)
        check(compositorTextures.all { it != 0 }) { "Failed to allocate compositor texture IDs" }

        // Slot 0: selected avatar texture (keeps the existing portrait render path).
        textureId = compositorTextures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[0])
        configureTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, portrait, 0)

        // Slot 1: live-camera passthrough placeholder; no camera frame is wired yet.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[1])
        configureTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        val cameraPlaceholder = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        cameraPlaceholder.put(byteArrayOf(0, 0, 0, -1)).position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, cameraPlaceholder
        )

        // Slot 2: single-channel segmentation-mask placeholder, not a live mask yet.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[2])
        configureTexture(GLES30.GL_NEAREST)
        val maskPlaceholder = ByteBuffer.allocateDirect(1).put(0).apply { position(0) }
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskPlaceholder
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        compositorTexturesReady = true
        Log.i(TAG, "Compositor texture slots allocated; camera and mask are placeholders")
    }

    private fun configureTexture(target: Int, filter: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (!meshReady || program == 0 || textureId == 0 || vaoId[0] == 0) return

        val nextPortrait = pendingPortrait.getAndSet(null)
        if (nextPortrait != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, nextPortrait, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            if (!portrait.isRecycled) portrait.recycle()
            portrait = nextPortrait
        }

        // Deform native vertices on the GL thread, then update the already allocated VBO.
        // Native storage remains stable for the life of this mesh; no per-frame ByteBuffer
        // wrapper or GPU buffer reallocation is performed.
        val latestFrame = pendingHolisticFrame.getAndSet(null)
        if (latestFrame != null && nativeUpdateHolisticTracking(
                latestFrame.face, latestFrame.pose, latestFrame.leftHand, latestFrame.rightHand
            )) {
            val vertexBuffer = nativeVertices
            if (vertexBuffer != null) {
                vertexBuffer.position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
                GLES30.glBufferSubData(
                    GLES30.GL_ARRAY_BUFFER, 0,
                    vertexCount * VERTEX_STRIDE_BYTES, vertexBuffer
                )
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }
        }

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerHandle, 0)

        GLES30.glBindVertexArray(vaoId[0])
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0
        )
        GLES30.glBindVertexArray(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Portrait shader compile failed: $log")
        }
        return shader
    }

    private fun glString(name: Int): String =
        GLES20.glGetString(name) ?: "unavailable"
}
