package com.kemzy.liveavatar.camera

data class DriverMotion(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val eyeLeft: Float = 0f,
    val eyeRight: Float = 0f,
    val mouthOpen: Float = 0f,
    val smile: Float = 0f,
    val browLeft: Float = 0f,
    val browRight: Float = 0f,
    val trackingId: Int? = null
) {
    fun smoothWith(next: DriverMotion, alpha: Float): DriverMotion {
        val a = alpha.coerceIn(0f, 1f)
        fun s(x: Float, y: Float) = x + (y - x) * a
        return copy(yaw=s(yaw,next.yaw), pitch=s(pitch,next.pitch), roll=s(roll,next.roll), eyeLeft=s(eyeLeft,next.eyeLeft), eyeRight=s(eyeRight,next.eyeRight), mouthOpen=s(mouthOpen,next.mouthOpen), smile=s(smile,next.smile), browLeft=s(browLeft,next.browLeft), browRight=s(browRight,next.browRight), trackingId=next.trackingId)
    }
}
