package com.kemzy.liveavatar

import kotlin.math.abs

data class MotionPacket(
    val pose: List<Float>,
    val expression: List<Float>,
    val eyeRatio: Float,
    val lipRatio: Float,
)

object MotionPacketMapper {
    fun map(
        yawDegrees: Float,
        pitchDegrees: Float,
        rollDegrees: Float,
        smile: Float,
        leftEyeOpen: Float,
        rightEyeOpen: Float,
        lipOpenRatio: Float = 0f,
    ): MotionPacket {
        val pose = listOf(
            clamp(yawDegrees / 30f),
            clamp(pitchDegrees / 30f),
            clamp(rollDegrees / 30f),
        )
        val eyeRatio = clamp(1f - ((leftEyeOpen + rightEyeOpen) * 0.5f))
        val expression = MutableList(63) { 0f }
        expression[0] = clamp(smile * 2f - 1f)
        expression[1] = clamp(1f - leftEyeOpen * 2f)
        expression[2] = clamp(1f - rightEyeOpen * 2f)
        expression[3] = clamp(abs(yawDegrees) / 30f)
        expression[4] = clamp(abs(pitchDegrees) / 30f)
        expression[5] = clamp(abs(rollDegrees) / 30f)
        return MotionPacket(pose, expression, eyeRatio, clamp(lipOpenRatio))
    }

    private fun clamp(value: Float): Float = value.coerceIn(0f, 1f)
}
