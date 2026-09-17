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
            clampSigned(yawDegrees / 30f),
            clampSigned(pitchDegrees / 30f),
            clampSigned(rollDegrees / 30f),
        )
        val eyeRatio = clampUnit(1f - ((leftEyeOpen + rightEyeOpen) * 0.5f))
        val expression = MutableList(63) { 0f }
        expression[0] = clampSigned(smile * 2f - 1f)
        expression[1] = clampSigned(1f - leftEyeOpen * 2f)
        expression[2] = clampSigned(1f - rightEyeOpen * 2f)
        expression[3] = clampUnit(abs(yawDegrees) / 30f)
        expression[4] = clampUnit(abs(pitchDegrees) / 30f)
        expression[5] = clampUnit(abs(rollDegrees) / 30f)
        return MotionPacket(pose, expression, eyeRatio, clampUnit(lipOpenRatio))
    }

    private fun clampSigned(value: Float): Float = value.coerceIn(-1f, 1f)
    private fun clampUnit(value: Float): Float = value.coerceIn(0f, 1f)
}
