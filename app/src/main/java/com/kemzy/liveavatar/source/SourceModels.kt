package com.kemzy.liveavatar.source

import android.net.Uri

data class FaceCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

enum class SourceKind { IMAGE, CAMERA, VIDEO, LOCAL }

data class SourceAsset(
    val id: String,
    val uri: Uri,
    val kind: SourceKind,
    val crop: FaceCrop = FaceCrop(0f, 0f, 1f, 1f),
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
