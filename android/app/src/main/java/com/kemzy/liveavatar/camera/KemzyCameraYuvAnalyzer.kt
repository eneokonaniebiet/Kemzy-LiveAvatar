package com.kemzy.liveavatar.camera

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kemzy.liveavatar.renderer.PortraitGlRenderer

/**
 * Queues CameraX YUV planes for the GL thread, then hands the same proxy to the
 * existing CPU/MediaPipe pipeline. The callback owns closing the proxy.
 *
 * Plane packing currently copies into bounded direct buffers on the camera
 * executor; it does not retain ImageProxy buffers after the proxy is closed.
 */
class KemzyCameraYuvAnalyzer(
    private val rendererProvider: () -> PortraitGlRenderer?,
    private val shouldUpload: () -> Boolean,
    private val onFrame: (ImageProxy) -> Unit
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        if (image.format != ImageFormat.YUV_420_888) {
            image.close()
            return
        }

        try {
            if (shouldUpload()) {
                val renderer = rendererProvider()
                if (renderer != null) {
                    val planes = image.planes
                    if (planes.size >= 3) {
                        renderer.uploadCameraFrameYUVHardware(
                            image.width,
                            image.height,
                            planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
                            planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
                            planes[2].buffer, planes[2].rowStride, planes[2].pixelStride
                        )
                    }
                }
            }
            // Existing handler performs inference and closes the ImageProxy in finally.
            onFrame(image)
        } catch (t: Throwable) {
            // Avoid leaking the camera frame if dispatch itself fails.
            image.close()
            Log.e(TAG, "Camera YUV frame dispatch failed", t)
        }
    }

    private companion object {
        const val TAG = "KemzyYuvAnalyzer"
    }
}
