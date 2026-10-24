package com.kemzy.liveavatar

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceOverlayGeometryTest {
    @Test
    fun clampDisplacement_limitsLargePositiveMotion() {
        val faceWidth = 100f
        val result = FaceOverlayGeometry.clampDisplacement(100f, faceWidth)

        assertEquals(faceWidth * FaceOverlayGeometry.MAX_DISPLACEMENT_RATIO, result, 0.01f)
    }

    @Test
    fun clampDisplacement_limitsLargeNegativeMotion() {
        val faceHeight = 120f
        val result = FaceOverlayGeometry.clampDisplacement(-100f, faceHeight)

        assertEquals(-faceHeight * FaceOverlayGeometry.MAX_DISPLACEMENT_RATIO, result, 0.01f)
    }

    @Test
    fun clampDisplacement_keepsNormalMotion() {
        val result = FaceOverlayGeometry.clampDisplacement(10f, 100f)

        assertEquals(10f, result, 0.01f)
    }
}
