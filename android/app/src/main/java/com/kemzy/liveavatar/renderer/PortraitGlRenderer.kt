package com.kemzy.liveavatar.renderer

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Native-mesh portrait renderer. Native C++ owns mesh storage; direct JNI
 * ByteBuffers expose that storage long enough for OpenGL to copy it into VBO/EBO.
 * Camera frames are never accepted as portrait textures.
 *
 * Use with a GLSurfaceView configured for an OpenGL ES 3 context.
 */
class PortraitGlRenderer(private val portrait: Bitmap) : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "KemzyGL"
        private const val GRID_COLUMNS = 17
        private const val GRID_ROWS = 17
        private const val FLOATS_PER_VERTEX = 5
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
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var samplerHandle = -1
    private val vaoId = IntArray(1)
    private val vboId = IntArray(1)
    private val eboId = IntArray(1)
    private var vertexCount = 0
    private var indexCount = 0
    private var meshReady = false

    external fun nativeInit()
    external fun nativeSystemDiagnostics(): String
    private external fun nativeInitializeMesh(columns: Int, rows: Int): Boolean
    private external fun nativeMeshVertexCount(): Int
    private external fun nativeMeshIndexCount(): Int
    private external fun nativeVertexBuffer(): ByteBuffer?
    private external fun nativeIndexBuffer(): ByteBuffer?

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

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, portrait, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

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

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (!meshReady || program == 0 || textureId == 0 || vaoId[0] == 0) return

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
