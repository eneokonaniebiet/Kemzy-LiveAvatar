package com.kemzy.liveavatar.models

import android.content.Context
import android.os.Environment
import java.io.File

class ModelDiscovery(private val context: Context) {
    fun discover(): ModelManifest {
        val privateRoot = File(context.filesDir, "models")
        val privateManifest = scan(privateRoot, "app-private")
        if (privateManifest.complete) return privateManifest

        val externalRoot = Environment.getExternalStorageDirectory()?.let { File(it, "KemzyModels") }
        if (externalRoot?.isDirectory == true && externalRoot.canRead()) {
            val external = scan(externalRoot, "shared-storage")
            if (external.complete) {
                return external.copy(
                    diagnostics = external.diagnostics + "Shared models are readable; import them into app-private storage before ONNX Runtime execution."
                )
            }
        }

        val diagnostics = privateManifest.diagnostics.toMutableList()
        if (externalRoot?.isDirectory == true) {
            diagnostics += "Shared KemzyModels exists but is not directly readable by this app. Use Import Models to grant folder access."
        } else {
            diagnostics += "Shared KemzyModels directory not found."
        }
        return ModelManifest(privateRoot, privateManifest.files, privateManifest.missingRequired, diagnostics)
    }

    private fun scan(root: File, source: String): ModelManifest {
        val lp = File(root, "liveportrait_onnx")
        val found = linkedMapOf<String, ModelFile>()
        val diagnostics = mutableListOf("Model source: $source")
        requiredKeys.forEach { key ->
            val file = when (key) {
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
            if (file.isFile && file.length() > 0L && file.canRead()) {
                found[key] = ModelFile(key, file, required = true)
            }
        }
        val missing = requiredKeys.filterNot(found::containsKey)
        if (missing.isNotEmpty()) diagnostics += "Missing: ${missing.joinToString()}"
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
