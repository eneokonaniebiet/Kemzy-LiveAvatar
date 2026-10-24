package com.kemzy.liveavatar.renderer

import android.opengl.GLES30
import android.util.Log

/**
 * Validates a linked GL program while its context is current on the GL thread.
 * Missing uniforms are warnings because the linker may optimize unused uniforms away.
 */
internal object PortraitGlShaderAssert {
    private const val TAG = "KemzyShaderAssert"

    fun assertProgramValid(programId: Int) {
        if (programId <= 0) {
            Log.e(TAG, "CRITICAL: Invalid program ID ($programId)")
            return
        }

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "FATAL: Program link validation failed: ${GLES30.glGetProgramInfoLog(programId)}")
            return
        }

        val requiredUniforms = arrayOf(
            "u_MvpMatrix",
            "u_BoneMatrices",
            "u_TextureY",
            "u_TextureU",
            "u_TextureV",
            "u_SegmentationAlphaMap",
            "u_AvatarTexture"
        )
        Log.i(TAG, "==== BEGIN KÉMZY SHADER ASSERTIONS ====")
        for (name in requiredUniforms) {
            val location = GLES30.glGetUniformLocation(programId, name)
            if (location < 0) {
                Log.w(TAG, "UNIFORM NOTICE: '$name' missing or optimized out (location=$location)")
            } else {
                Log.i(TAG, "UNIFORM OK: '$name' location=$location")
            }
        }
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL error during shader assertions: 0x${error.toString(16)}")
        } else {
            Log.i(TAG, "Shader link and uniform query assertions completed")
        }
        Log.i(TAG, "======================================")
    }
}
