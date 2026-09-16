package com.kemzy.liveavatar.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer

data class Feature3dTensor(val values: FloatArray, val shape: LongArray) {
    init {
        TensorContract.requireFeature3d(shape)
        require(TensorContract.elementCount(shape) == values.size.toLong()) { "feature_3d element count does not match shape" }
    }

    fun toOrtTensor(env: OrtEnvironment): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)
}
