package com.kemzy.liveavatar.models

import android.content.Context
import android.os.Environment
import java.io.File

class ModelDiscovery(private val context: Context) {
    fun discover(): ModelManifest {
        val candidates = linkedSetOf<File>()
        Environment.getExternalStorageDirectory()?.let { candidates += File(it, "KemzyModels") }
        candidates += File(context.filesDir, "KemzyModels")
        val root = candidates.firstOrNull { it.isDirectory }
        if (root == null) return ModelManifest(null, emptyMap(), requiredKeys, listOf("KemzyModels directory not found"))

        val lp = File(root, "liveportrait_onnx")
        val found = linkedMapOf<String, ModelFile>()
        val diagnostics = mutableListOf<String>()
        requiredKeys.forEach { key ->
            val base = when (key) {
                ModelNames.APPEARANCE -> File(lp, "appearance_feature_extractor.onnx")
                ModelNames.MOTION -> File(lp, "motion_extractor.onnx")
                ModelNames.WARPING -> File(lp, "warping_spade-fix.onnx")
                ModelNames.STITCHING -> File(lp, "stitching.onnx")
                ModelNames.STITCHING_EYE -> File(lp, "stitching_eye.onnx")
                ModelNames.STITCHING_LIP -> File(lp, "stitching_lip.onnx")
                ModelNames.LANDMARK -> File(lp, "landmark.onnx")
                ModelNames.FACE_POSE -> File(lp, "face_2dpose_106_static.onnx")
                ModelNames.RETINAFACE -> File(lp, "retinaface_det_static.onnx")
                ModelNames.INSWAPPER -> File(root, "inswapper_128.onnx")
                ModelNames.ARCFACE -> File(root, "w600k_r50.onnx")
                else -> error(key)
            }
            if (base.isFile && base.length() > 0) found[key] = ModelFile(key, base, required = true)
        }
        val missing = requiredKeys.filterNot(found::containsKey)
        if (missing.isNotEmpty()) diagnostics += "Missing: ${missing.joinToString() }"
        diagnostics += "Discovered ${found.size}/${requiredKeys.size} required model files"
        return ModelManifest(root, found, missing, diagnostics)
    }

    private companion object {
        val requiredKeys = listOf(
            ModelNames.APPEARANCE, ModelNames.MOTION, ModelNames.WARPING,
            ModelNames.STITCHING, ModelNames.STITCHING_EYE, ModelNames.STITCHING_LIP,
            ModelNames.LANDMARK, ModelNames.FACE_POSE, ModelNames.RETINAFACE,
            ModelNames.INSWAPPER, ModelNames.ARCFACE
        )
    }
}
