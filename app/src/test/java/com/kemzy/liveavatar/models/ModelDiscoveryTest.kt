package com.kemzy.liveavatar.models

import org.junit.Assert.*
import org.junit.Test

class ModelDiscoveryTest {
    @Test fun requiredSetContainsLivePortraitStages() {
        assertTrue(ModelNames.WARPING.isNotBlank())
        assertTrue(ModelNames.APPEARANCE.isNotBlank())
        assertTrue(ModelNames.MOTION.isNotBlank())
        assertTrue(ModelNames.STITCHING_EYE.isNotBlank())
        assertTrue(ModelNames.STITCHING_LIP.isNotBlank())
    }
}
