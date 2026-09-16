package com.kemzy.liveavatar.output

import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarOutputTest {
    @Test
    fun externalCameraIsNotClaimedAsReady() {
        val label = OutputSupport.label(AvatarOutput.ExternalCameraPendingVerification)
        assertTrue(label.contains("pending verification"))
    }
}
