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
        val diagnostics = privateManifest.diagnostics.toMutableList()
        if (externalRoot?.isDirectory == true) {
            diagnostics += if (externalRoot.canRead()) {
                "Shared KemzyModels exists. Import it into app-private storage before ONNX Runtime execution."
            } else {
                "Shared KemzyModels exists but is not directly readable by this app. Use Import Models to grant folder access."
            }
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
                else -> error(key)
            }
            if (file.isFile && file.length() > 0L && file.canRead()) {
                found[key] = ModelFile(key, file, required = true)
            }
        }
        val missing = requiredKeys.filterNot(found::containsKey)
        if (missing.isNotEmpty()) diagnostics += "Missing: ${missing.joinToString()}"
        diagnostics += "Discovered ${found.size}/${requiredKeys.size} required LivePortrait model files"
        diagnostics += "ArcFace/inswapper files are optional for the live-portrait path"
        return ModelManifest(root, found, missing, diagnostics)
    }

    private companion object {
        val requiredKeys = listOf(
            ModelNames.APPEARANCE, ModelNames.MOTION, ModelNames.WARPING,
            ModelNames.STITCHING, ModelNames.STITCHING_EYE, ModelNames.STITCHING_LIP,
            ModelNames.LANDMARK, ModelNames.FACE_POSE, ModelNames.RETINAFACE
        )
    }
}
