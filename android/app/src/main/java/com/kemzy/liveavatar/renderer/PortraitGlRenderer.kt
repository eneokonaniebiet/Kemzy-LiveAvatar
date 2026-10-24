package com.kemzy.liveavatar.renderer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
class PortraitGlRenderer(private val appContext: Context, private var portrait: Bitmap) : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "KemzyGL"
        private const val GRID_COLUMNS = 17
        private const val GRID_ROWS = 17
        // Native MeshVertex now carries the stable 40-byte rig-compatible layout.
        private const val FLOATS_PER_VERTEX = 10
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
    private var humanoidSuitProgram = 0
    private var activeProgramId = 0
    private var uMvpMatrixLocation = -1
    private val mvpMatrix = FloatArray(16)
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var uTextureYLocation = -1
    private var uTextureULocation = -1
    private var uTextureVLocation = -1
    private var uSegmentationAlphaLocation = -1
    private var uAvatarTextureLocation = -1
    private var textureId = 0
    private val compositorTextures = IntArray(3)
    private var compositorTexturesReady = false
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var samplerHandle = -1
    private var boneMatricesUniformLocation = -1
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
    private data class PendingPixelFrame(val width: Int, val height: Int, val pixels: ByteBuffer)
    private data class PendingYuvFrame(
        val width: Int, val height: Int,
        val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer
    )
    private val pendingYuvFrame = AtomicReference<PendingYuvFrame?>(null)
    private val yuvTextures = IntArray(3)
    private val yuvTextureWidths = intArrayOf(1, 1, 1)
    private val yuvTextureHeights = intArrayOf(1, 1, 1)
    private val pendingHolisticFrame = AtomicReference<HolisticFrame?>(null)
    private val pendingPortrait = AtomicReference<Bitmap?>(null)
    private val pendingCameraRgb = AtomicReference<PendingPixelFrame?>(null)
    private val pendingSegmentationMask = AtomicReference<PendingPixelFrame?>(null)
    private var cameraTextureWidth = 1
    private var cameraTextureHeight = 1
    private var maskTextureWidth = 1
    private var maskTextureHeight = 1

    private val riggedVertexStrideBytes = 10 * 4
    private val riggedBoneIdOffsetBytes = 8 * 4
    private val riggedSkinWeightOffsetBytes = 9 * 4

    private fun configureRiggedHardwareStateVAO(vao: Int, vbo: Int, ebo: Int) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, riggedVertexStrideBytes, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, riggedVertexStrideBytes, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribIPointer(2, 1, GLES30.GL_INT, riggedVertexStrideBytes, riggedBoneIdOffsetBytes)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, riggedVertexStrideBytes, riggedSkinWeightOffsetBytes)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    external fun nativeInit()
    external fun nativeSystemDiagnostics(): String
    private external fun nativeInitializeMesh(columns: Int, rows: Int): Boolean
    private external fun nativeMeshVertexCount(): Int
    private external fun nativeMeshIndexCount(): Int
    private external fun nativeVertexBuffer(): ByteBuffer?
    private external fun nativeIndexBuffer(): ByteBuffer?
    private external fun nativeGetBoneMatricesBuffer(): ByteBuffer?
    private external fun nativeUpdateHolisticTracking(
        face: FloatArray?, pose: FloatArray?, leftHand: FloatArray?, rightHand: FloatArray?
    ): Boolean

    fun updatePortrait(bitmap: Bitmap) {
        val safeCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        pendingPortrait.getAndSet(safeCopy)?.let { if (!it.isRecycled) it.recycle() }
    }

    fun uploadCameraFrameYUVHardware(
        width: Int,
        height: Int,
        planeY: ByteBuffer, rowStrideY: Int, pixelStrideY: Int,
        planeU: ByteBuffer, rowStrideU: Int, pixelStrideU: Int,
        planeV: ByteBuffer, rowStrideV: Int, pixelStrideV: Int
    ) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val y = packYuvPlane(planeY, width, height, rowStrideY, pixelStrideY) ?: return
        val u = packYuvPlane(planeU, chromaWidth, chromaHeight, rowStrideU, pixelStrideU) ?: return
        val v = packYuvPlane(planeV, chromaWidth, chromaHeight, rowStrideV, pixelStrideV) ?: return
        pendingYuvFrame.set(PendingYuvFrame(width, height, y, u, v))
    }

    private fun packYuvPlane(
        source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int
    ): ByteBuffer? {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
        val bytes = width.toLong() * height.toLong()
        if (bytes > 16L * 1024L * 1024L) return null
        val input = source.duplicate()
        val base = input.position().toLong()
        val lastByte = base + (height - 1L) * rowStride.toLong() + (width - 1L) * pixelStride.toLong()
        if (lastByte >= input.limit().toLong()) return null
        val packed = ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())
        for (row in 0 until height) {
            val rowStart = base.toInt() + row * rowStride
            for (column in 0 until width) packed.put(input.get(rowStart + column * pixelStride))
        }
        packed.flip()
        return packed
    }

    fun uploadLiveCameraFrameHardware(width: Int, height: Int, frameBuffer: ByteBuffer) {
        val frame = copyPixelFrame(width, height, 3, frameBuffer) ?: return
        pendingCameraRgb.getAndSet(frame)?.pixels?.let { }
    }

    fun uploadSegmentationMaskHardware(width: Int, height: Int, alphaBuffer: ByteBuffer) {
        val frame = copyPixelFrame(width, height, 1, alphaBuffer) ?: return
        pendingSegmentationMask.getAndSet(frame)?.pixels?.let { }
    }

    private fun copyPixelFrame(width: Int, height: Int, bytesPerPixel: Int, source: ByteBuffer): PendingPixelFrame? {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null
        val expected = width.toLong() * height.toLong() * bytesPerPixel.toLong()
        if (expected > 64L * 1024L * 1024L || expected != source.remaining().toLong()) return null
        val copy = ByteBuffer.allocateDirect(expected.toInt()).order(ByteOrder.nativeOrder())
        copy.put(source.duplicate()).flip()
        return PendingPixelFrame(width, height, copy)
    }

    private fun bindCompositorTexturesToShader(programId: Int) {
        GLES30.glUseProgram(programId)
        val samplerNames = arrayOf("u_TextureY", "u_TextureU", "u_TextureV")
        for (i in 0..2) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yuvTextures[i])
            val location = GLES30.glGetUniformLocation(programId, samplerNames[i])
            if (location >= 0) GLES30.glUniform1i(location, 3 + i)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun uploadPendingPixelTextures() {
        if (!compositorTexturesReady) return
        pendingCameraRgb.getAndSet(null)?.let { frame ->
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[1])
            if (frame.width != cameraTextureWidth || frame.height != cameraTextureHeight) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB8, frame.width, frame.height, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, null)
                cameraTextureWidth = frame.width
                cameraTextureHeight = frame.height
            }
            frame.pixels.position(0)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, frame.pixels)
        }
        pendingSegmentationMask.getAndSet(null)?.let { frame ->
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[2])
            if (frame.width != maskTextureWidth || frame.height != maskTextureHeight) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, frame.width, frame.height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
                maskTextureWidth = frame.width
                maskTextureHeight = frame.height
            }
            frame.pixels.position(0)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, frame.pixels)
        }
        pendingYuvFrame.getAndSet(null)?.let { frame ->
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            val chromaWidth = (frame.width + 1) / 2
            val chromaHeight = (frame.height + 1) / 2
            val planes = arrayOf(frame.y, frame.u, frame.v)
            val widths = intArrayOf(frame.width, chromaWidth, chromaWidth)
            val heights = intArrayOf(frame.height, chromaHeight, chromaHeight)
            for (i in 0..2) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE3 + i)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yuvTextures[i])
                val planeWidth = widths[i]
                val planeHeight = heights[i]
                if (planeWidth != yuvTextureWidths[i] || planeHeight != yuvTextureHeights[i]) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, planeWidth, planeHeight, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
                    yuvTextureWidths[i] = planeWidth
                    yuvTextureHeights[i] = planeHeight
                }
                planes[i].position(0)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, widths[i], heights[i], GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, planes[i])
            }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun updateTrackingData(landmarks: FloatArray) = enqueueHolisticFrameData(face = landmarks)

    fun enqueueHolisticFrameData(
        face: FloatArray?, pose: FloatArray? = null,
        leftHand: FloatArray? = null, rightHand: FloatArray? = null
    ) {
        if (!validStream(face, 478) || !validStream(pose, 33) || !validStream(leftHand, 21) || !validStream(rightHand, 21)) return
        pendingHolisticFrame.set(HolisticFrame(face?.copyOf(), pose?.copyOf(), leftHand?.copyOf(), rightHand?.copyOf()))
    }

    private fun validStream(data: FloatArray?, maxLandmarks: Int): Boolean =
        data == null || (data.size % 3 == 0 && data.size / 3 <= maxLandmarks && data.all { it.isFinite() })

    init { System.loadLibrary("kemzyliveavatar") }

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
            val vertexBuffer = nativeVertexBuffer()?.order(ByteOrder.nativeOrder()) ?: error("JNI returned no direct vertex buffer")
            nativeVertices = vertexBuffer
            val indexBuffer = nativeIndexBuffer()?.order(ByteOrder.nativeOrder()) ?: error("JNI returned no direct index buffer")
            require(vertexBuffer.isDirect && vertexBuffer.capacity() >= vertexBytes) {
                "Invalid native vertex buffer: direct=${vertexBuffer.isDirect}, capacity=${vertexBuffer.capacity()}, required=$vertexBytes"
            }
            require(indexBuffer.isDirect && indexBuffer.capacity() >= indexBytes) {
                "Invalid native index buffer: direct=${indexBuffer.isDirect}, capacity=${indexBuffer.capacity()}, required=$indexBytes"
            }
            vertexBuffer.position(0)
            indexBuffer.position(0)
            GLES30.glGenVertexArrays(1, vaoId, 0)
            GLES30.glGenBuffers(1, vboId, 0)
            GLES30.glGenBuffers(1, eboId, 0)
            GLES30.glBindVertexArray(vaoId[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBytes, vertexBuffer, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboId[0])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, indexBuffer, GLES30.GL_STATIC_DRAW)

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
            humanoidSuitProgram = compileProgramFromAssets("shaders/humanoid_suit.vert", "shaders/humanoid_suit.frag")
            activateHumanoidSuitProgram(humanoidSuitProgram)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            samplerHandle = GLES20.glGetUniformLocation(program, "uPortrait")
            boneMatricesUniformLocation = GLES30.glGetUniformLocation(program, "u_BoneMatrices")
            check(positionHandle >= 0 && texCoordHandle >= 0 && samplerHandle >= 0) { "Required portrait shader inputs were optimized out or not found" }
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0)
            GLES30.glEnableVertexAttribArray(texCoordHandle)
            GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 3 * 4)
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

    private fun initializeHardwareCompositorTextures() {
        GLES30.glGenTextures(compositorTextures.size, compositorTextures, 0)
        check(compositorTextures.all { it != 0 }) { "Failed to allocate compositor texture IDs" }
        textureId = compositorTextures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[0])
        configureTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, portrait, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[1])
        configureTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        val cameraPlaceholder = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        cameraPlaceholder.put(byteArrayOf(0, 0, 0, -1)).position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, cameraPlaceholder)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, compositorTextures[2])
        configureTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        val maskPlaceholder = ByteBuffer.allocateDirect(1).put(0).apply { position(0) }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskPlaceholder)
        GLES30.glGenTextures(yuvTextures.size, yuvTextures, 0)
        check(yuvTextures.all { it != 0 }) { "Failed to allocate YUV plane texture IDs" }
        for (texture in yuvTextures) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            configureTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
            val initialPlane = ByteBuffer.allocateDirect(1).put(0).apply { position(0) }
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, initialPlane)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        compositorTexturesReady = true
        Log.i(TAG, "Compositor slots and YUV plane textures allocated; live rendering remains unwired")
    }

    private fun configureTexture(target: Int, filter: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        configureCompositorMatrices(viewportWidth, viewportHeight)
    }

    private fun configureCompositorMatrices(width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val aspect = safeWidth.toFloat() / safeHeight.toFloat()
        android.opengl.Matrix.orthoM(mvpMatrix, 0, -aspect, aspect, -1.0f, 1.0f, -1.0f, 1.0f)
        if (humanoidSuitProgram > 0) {
            uMvpMatrixLocation = GLES30.glGetUniformLocation(humanoidSuitProgram, "u_MvpMatrix")
            if (uMvpMatrixLocation < 0) Log.e(TAG, "Humanoid shader missing required u_MvpMatrix uniform")
            else Log.i(TAG, "MVP uniform verified at location $uMvpMatrixLocation; viewport=${safeWidth}x${safeHeight}")
        }
    }

    private fun executeRiggedRenderPass(riggedVaoId: Int, riggedIndexCount: Int) {
        if (activeProgramId <= 0 || riggedVaoId <= 0 || riggedIndexCount <= 0) return
        GLES30.glUseProgram(activeProgramId)
        val matrixLocation = GLES30.glGetUniformLocation(activeProgramId, "u_MvpMatrix")
        if (matrixLocation < 0) {
            Log.e(TAG, "Rigged render pass skipped: u_MvpMatrix uniform is unavailable")
            GLES30.glUseProgram(program)
            return
        }
        GLES30.glUniformMatrix4fv(matrixLocation, 1, false, mvpMatrix, 0)
        val glErrorBeforeDraw = GLES30.glGetError()
        if (glErrorBeforeDraw != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "MVP upload GL error 0x${glErrorBeforeDraw.toString(16)}")
            GLES30.glUseProgram(program)
            return
        }
        GLES30.glBindVertexArray(riggedVaoId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, riggedIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
        val glErrorAfterDraw = GLES30.glGetError()
        if (glErrorAfterDraw != GLES30.GL_NO_ERROR) Log.e(TAG, "Rigged draw GL error 0x${glErrorAfterDraw.toString(16)}")
        else Log.d(TAG, "Rigged render pass submitted; indices=$riggedIndexCount")
        GLES30.glUseProgram(program)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        uploadPendingPixelTextures()
        if (!meshReady || program == 0 || textureId == 0 || vaoId[0] == 0) return
        val nextPortrait = pendingPortrait.getAndSet(null)
        if (nextPortrait != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, nextPortrait, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            if (!portrait.isRecycled) portrait.recycle()
            portrait = nextPortrait
        }
        // Native deformation, including the synchronized voice overlay, completes before this VBO upload.
        val latestFrame = pendingHolisticFrame.getAndSet(null)
        if (latestFrame != null && nativeUpdateHolisticTracking(latestFrame.face, latestFrame.pose, latestFrame.leftHand, latestFrame.rightHand)) {
            val vertexBuffer = nativeVertices
            if (vertexBuffer != null) {
                vertexBuffer.position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId[0])
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertexCount * VERTEX_STRIDE_BYTES, vertexBuffer)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }
        }
        GLES20.glUseProgram(program)
        uploadBoneMatricesToGPUIfSupported()
        bindCompositorTexturesToShader(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES30.glBindVertexArray(vaoId[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uploadBoneMatricesToGPUIfSupported() {
        if (boneMatricesUniformLocation < 0) return
        val nativeBuffer = nativeGetBoneMatricesBuffer() ?: return
        if (!nativeBuffer.isDirect || nativeBuffer.capacity() < 4 * 16 * Float.SIZE_BYTES) return
        nativeBuffer.order(ByteOrder.nativeOrder())
        val matrices: FloatBuffer = nativeBuffer.asFloatBuffer()
        matrices.position(0)
        GLES30.glUniformMatrix4fv(boneMatricesUniformLocation, 4, false, matrices)
    }

    private fun activateHumanoidSuitProgram(compiledProgramId: Int) {
        if (compiledProgramId <= 0) return
        GLES30.glUseProgram(compiledProgramId)
        activeProgramId = compiledProgramId
        uMvpMatrixLocation = GLES30.glGetUniformLocation(compiledProgramId, "u_MvpMatrix")
        uAvatarTextureLocation = GLES30.glGetUniformLocation(compiledProgramId, "u_AvatarTexture")
        uSegmentationAlphaLocation = GLES30.glGetUniformLocation(compiledProgramId, "u_SegmentationAlphaMap")
        uTextureYLocation = GLES30.glGetUniformLocation(compiledProgramId, "u_TextureY")
        uTextureULocation = GLES30.glGetUniformLocation(compiledProgramId, "u_TextureU")
        uTextureVLocation = GLES30.glGetUniformLocation(compiledProgramId, "u_TextureV")
        if (uAvatarTextureLocation >= 0) GLES30.glUniform1i(uAvatarTextureLocation, 0)
        if (uSegmentationAlphaLocation >= 0) GLES30.glUniform1i(uSegmentationAlphaLocation, 2)
        if (uTextureYLocation >= 0) GLES30.glUniform1i(uTextureYLocation, 3)
        if (uTextureULocation >= 0) GLES30.glUniform1i(uTextureULocation, 4)
        if (uTextureVLocation >= 0) GLES30.glUniform1i(uTextureVLocation, 5)
        if (uMvpMatrixLocation < 0) Log.e(TAG, "Humanoid suit shader linked without u_MvpMatrix; rigged draw will remain disabled")
        else Log.i(TAG, "Humanoid suit shader linked; MVP uniform verified at location $uMvpMatrixLocation")
        Log.i(TAG, "Humanoid suit samplers mapped (0,2,3,4,5); active draw remains gated on a matching rigged VAO.")
        GLES30.glUseProgram(program)
    }

    private fun compileProgramFromAssets(vertexAsset: String, fragmentAsset: String): Int {
        val vertexSource = appContext.assets.open(vertexAsset).bufferedReader().use { it.readText() }
        val fragmentSource = appContext.assets.open(fragmentAsset).bufferedReader().use { it.readText() }
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = try { compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource) } catch (t: Throwable) {
            GLES30.glDeleteShader(vertexShader)
            throw t
        }
        val candidate = GLES30.glCreateProgram()
        GLES30.glAttachShader(candidate, vertexShader)
        GLES30.glAttachShader(candidate, fragmentShader)
        GLES30.glLinkProgram(candidate)
        val status = IntArray(1)
        GLES30.glGetProgramiv(candidate, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(candidate)
            GLES30.glDeleteProgram(candidate)
            error("Humanoid suit shader link failed: $log")
        }
        Log.i(TAG, "Humanoid suit shader assets compiled and linked successfully")
        PortraitGlShaderAssert.assertProgramValid(candidate)
        return candidate
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

    private fun glString(name: Int): String = GLES20.glGetString(name) ?: "unavailable"
}
