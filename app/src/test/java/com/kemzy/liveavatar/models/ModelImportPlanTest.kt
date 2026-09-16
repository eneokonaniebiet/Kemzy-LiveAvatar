package com.kemzy.liveavatar.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImportPlanTest {
    @Test
    fun requiredModelNames_areMappedToPrivateRelativePaths() {
        val plan = ModelImportPlan.required()

        assertEquals("liveportrait_onnx/appearance_feature_extractor.onnx", plan.pathFor(ModelNames.APPEARANCE))
        assertEquals("liveportrait_onnx/warping_spade-fix.onnx", plan.pathFor(ModelNames.WARPING))
        assertEquals("inswapper_128.onnx", plan.pathFor(ModelNames.INSWAPPER))
        assertEquals("w600k_r50.onnx", plan.pathFor(ModelNames.ARCFACE))
        assertTrue(plan.entries.size >= 11)
    }

    @Test
    fun importIsComplete_onlyWhenEveryRequiredFileExists() {
        val plan = ModelImportPlan.required()
        val all = plan.entries.associate { it.key to true }
        val missing = all.toMutableMap().also { it[ModelNames.WARPING] = false }

        assertTrue(plan.isComplete(all))
        assertTrue(!plan.isComplete(missing))
    }
}
