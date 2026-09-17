package com.kemzy.liveavatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPacketMapperTest {
    @Test
    fun mapsHeadRotationAndFaceSignalsToStablePacket() {
        val packet = MotionPacketMapper.map(
            yawDegrees = 30f,
            pitchDegrees = -10f,
            rollDegrees = 5f,
            smile = 0.8f,
            leftEyeOpen = 0.2f,
            rightEyeOpen = 0.9f,
        )

        assertEquals(3, packet.pose.size)
        assertEquals(63, packet.expression.size)
        assertEquals(1.0f, packet.pose[0], 0.001f)
        assertEquals(-0.3333f, packet.pose[1], 0.01f)
        assertEquals(0.1666f, packet.pose[2], 0.01f)
        assertTrue(packet.expression.all { it in -1f..1f })
        assertTrue(packet.expression.any { it != 0f })
    }

    @Test
    fun clampsExtremeCameraAngles() {
        val packet = MotionPacketMapper.map(999f, -999f, 999f, 0f, 0f, 0f)
        assertEquals(1f, packet.pose[0], 0.0001f)
        assertEquals(-1f, packet.pose[1], 0.0001f)
        assertEquals(1f, packet.pose[2], 0.0001f)
    }
}
