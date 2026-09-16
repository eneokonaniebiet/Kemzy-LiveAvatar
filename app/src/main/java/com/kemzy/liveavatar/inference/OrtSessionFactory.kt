package com.kemzy.liveavatar.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

class OrtSessionFactory(private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()) {
    fun create(modelPath: File, options: OrtSession.SessionOptions = defaultOptions()): OrtSession {
        require(modelPath.isFile) { "Model does not exist: ${modelPath.path}" }
        return environment.createSession(modelPath.path, options)
    }

    fun defaultOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
}
