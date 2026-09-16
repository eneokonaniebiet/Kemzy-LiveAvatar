package com.kemzy.liveavatar.models

import java.io.File

data class ModelFile(val key: String, val file: File, val required: Boolean)

data class ModelManifest(
    val root: File?,
    val files: Map<String, ModelFile>,
    val missingRequired: List<String>,
    val diagnostics: List<String>
) {
    val complete: Boolean get() = missingRequired.isEmpty()
}

object ModelNames {
    const val APPEARANCE = "appearance_feature_extractor"
    const val MOTION = "motion_extractor"
    const val WARPING = "warping_spade-fix"
    const val STITCHING = "stitching"
    const val STITCHING_EYE = "stitching_eye"
    const val STITCHING_LIP = "stitching_lip"
    const val LANDMARK = "landmark"
    const val FACE_POSE = "face_2dpose_106_static"
    const val RETINAFACE = "retinaface_det_static"
    const val INSWAPPER = "inswapper_128"
    const val ARCFACE = "w600k_r50"
}
