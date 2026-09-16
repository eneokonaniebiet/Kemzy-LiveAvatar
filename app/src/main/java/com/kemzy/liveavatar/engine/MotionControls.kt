package com.kemzy.liveavatar.engine

data class MotionControls(
    val eyeBlink: Float = 1f,
    val winkLeft: Float = 0f,
    val winkRight: Float = 0f,
    val mouth: Float = 1f,
    val smile: Float = 1f,
    val frown: Float = 0f,
    val brows: Float = 1f,
    val yaw: Float = 1f,
    val pitch: Float = 1f,
    val roll: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val zoom: Float = 1f,
    val intensity: Float = 1f,
    val smoothing: Float = 0.35f,
    val relativeMotion: Boolean = true,
    val stitching: Boolean = true,
    val background: BackgroundMode = BackgroundMode.ORIGINAL,
    val effect: EffectMode = EffectMode.NONE
)

enum class BackgroundMode {
    ORIGINAL,
    BLUR,
    DARK,
    LIGHT
}

enum class EffectMode {
    NONE,
    SOFT,
    MONO,
    DREAM
}
