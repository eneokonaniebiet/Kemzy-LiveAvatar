package com.kemzy.liveavatar.output

/**
 * Explicit boundary between Kémzy's internal rendered frames and external consumers.
 * CameraX Preview is intentionally not described as a system-wide virtual camera.
 */
sealed interface AvatarOutput {
    data object InternalPreview : AvatarOutput
    data object SavedVideo : AvatarOutput
    data object ExternalCameraPendingVerification : AvatarOutput
}

object OutputSupport {
    fun label(output: AvatarOutput): String = when (output) {
        AvatarOutput.InternalPreview -> "Kémzy preview"
        AvatarOutput.SavedVideo -> "Saved video"
        AvatarOutput.ExternalCameraPendingVerification -> "External camera integration pending verification"
    }
}
