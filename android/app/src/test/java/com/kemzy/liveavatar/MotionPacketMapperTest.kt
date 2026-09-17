package com.kemzy.liveavatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPacketMapperTest {
    @Test
    fun neutralFaceProducesValidLivePortraitShape() {
        val packet = MotionPacketMapper.map(0f, 0f, 0f, 0f, 1f, 1f)
        assertEquals(3, packet.pose.size)
        assertEquals(63, packet.expression.size)
        assertTrue(packet.pose.all { it in -1f..1f })
        assertTrue(packet.expression.all { it in -1f..1f })
    }

    @Test
    fun headMotionIsClamped() {
        val packet = MotionPacketMapper.map(180f, -180f, 90f, 1f, 0f, 0f)
        assertTrue(packet.pose.all { it in -1f..1f })
        assertTrue(packet.expression.all { it in -1f..1f })
    }
}
