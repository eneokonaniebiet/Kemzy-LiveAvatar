package com.kemzy.liveavatar.source

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SourceFeatureCache(context: Context) {
    private val root = File(context.cacheDir, "source-features").apply { mkdirs() }

    fun get(sourceId: String, modelVersion: String): FloatArray? {
        val file = fileFor(sourceId, modelVersion)
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also(b::get) }
        }.getOrNull()
    }

    fun put(sourceId: String, modelVersion: String, features: FloatArray) {
        val bytes = ByteBuffer.allocate(features.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            features.forEach(::putFloat)
        }.array()
        fileFor(sourceId, modelVersion).writeBytes(bytes)
    }

    private fun fileFor(sourceId: String, modelVersion: String): File = File(root, "${sourceId.hashCode()}-${modelVersion.hashCode()}.f32")
}
