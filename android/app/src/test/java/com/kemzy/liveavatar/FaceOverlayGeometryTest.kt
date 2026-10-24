package com.kemzy.liveavatar

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceOverlayGeometryTest {
    @Test
    fun paddedFaceRect_staysInsideFrame() {
        val rect = FaceOverlayGeometry.paddedFaceRect(
            RectF(100f, 80f, 200f, 200f),
            320,
            240
        )

        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.right <= 320f)
        assertTrue(rect.bottom <= 240f)
        assertEquals(112f, rect.width(), 0.01f)
        assertEquals(144f, rect.height(), 0.01f)
    }

    @Test
    fun clampDisplacement_limitsLargeHeadMotion() {
        val faceWidth = 100f
        val faceHeight = 120f

        val x = FaceOverlayGeometry.clampDisplacement(100f, faceWidth)
        val y = FaceOverlayGeometry.clampDisplacement(-100f, faceHeight)

        assertEquals(faceWidth * FaceOverlayGeometry.MAX_DISPLACEMENT_RATIO, x, 0.01f)
        assertEquals(-faceHeight * FaceOverlayGeometry.MAX_DISPLACEMENT_RATIO, y, 0.01f)
    }
}
