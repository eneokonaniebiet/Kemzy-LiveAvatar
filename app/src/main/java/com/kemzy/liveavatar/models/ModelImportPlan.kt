package com.kemzy.liveavatar.models

/** Relative paths inside the user-selected KemzyModels directory. */
data class ModelImportEntry(val key: String, val relativePath: String)

data class ModelImportPlan(val entries: List<ModelImportEntry>) {
    fun pathFor(key: String): String = entries.first { it.key == key }.relativePath

    fun isComplete(results: Map<String, Boolean>): Boolean =
        entries.all { results[it.key] == true }

    companion object {
        fun required(): ModelImportPlan = ModelImportPlan(
            listOf(
                ModelImportEntry(ModelNames.APPEARANCE, "liveportrait_onnx/appearance_feature_extractor.onnx"),
                ModelImportEntry(ModelNames.MOTION, "liveportrait_onnx/motion_extractor.onnx"),
                ModelImportEntry(ModelNames.WARPING, "liveportrait_onnx/warping_spade-fix.onnx"),
                ModelImportEntry(ModelNames.STITCHING, "liveportrait_onnx/stitching.onnx"),
                ModelImportEntry(ModelNames.STITCHING_EYE, "liveportrait_onnx/stitching_eye.onnx"),
                ModelImportEntry(ModelNames.STITCHING_LIP, "liveportrait_onnx/stitching_lip.onnx"),
                ModelImportEntry(ModelNames.LANDMARK, "liveportrait_onnx/landmark.onnx"),
                ModelImportEntry(ModelNames.FACE_POSE, "liveportrait_onnx/face_2dpose_106_static.onnx"),
                ModelImportEntry(ModelNames.RETINAFACE, "liveportrait_onnx/retinaface_det_static.onnx")
            )
        )
    }
}
