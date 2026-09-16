package com.kemzy.liveavatar.engine

sealed interface EngineState {
    data object Ready : EngineState
    data object SourceReady : EngineState
    data object Preparing : EngineState
    data object Running : EngineState
    data class Degraded(val message: String) : EngineState
    data class Error(val message: String) : EngineState
    data object Stopped : EngineState
}

data class AiFrame(val bitmap: android.graphics.Bitmap, val timestampNanos: Long)
