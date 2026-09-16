package com.kemzy.liveavatar.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DriverMotionRetargetTest {
    @Test
    fun eyeRatioUsesBothEyesAndMean() {
        val motion = DriverMotion(eyeLeft = 0.2f, eyeRight = 0.8f)
        assertArrayEquals(floatArrayOf(0.2f, 0.8f, 0.5f), motion.eyeRetargetRatios(), 0.0001f)
    }

    @Test
    fun lipRatioUsesMouthOpenAndSmile() {
        val motion = DriverMotion(mouthOpen = 0.7f, smile = 0.4f)
        assertArrayEquals(floatArrayOf(0.7f, 0.4f), motion.lipRetargetRatios(), 0.0001f)
    }
}
