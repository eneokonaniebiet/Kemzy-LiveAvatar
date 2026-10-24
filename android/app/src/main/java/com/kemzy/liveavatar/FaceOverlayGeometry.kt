package com.kemzy.liveavatar

import android.graphics.RectF
import kotlin.math.min

internal object FaceOverlayGeometry {
    const val MAX_DISPLACEMENT_RATIO = 0.22f

    fun paddedFaceRect(bounds: RectF, width: Int, height: Int): RectF {
        val padX = bounds.width() * 0.06f
        val padY = bounds.height() * 0.10f
        return RectF(
            (bounds.left - padX).coerceAtLeast(0f),
            (bounds.top - padY).coerceAtLeast(0f),
            (bounds.right + padX).coerceAtMost(width.toFloat()),
            (bounds.bottom + padY).coerceAtMost(height.toFloat())
        )
    }

    fun clampDisplacement(value: Float, faceSize: Float): Float {
        val limit = faceSize * MAX_DISPLACEMENT_RATIO
        return value.coerceIn(-limit, limit)
    }
}
