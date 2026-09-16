package com.kemzy.liveavatar.camera

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

class FaceTracker(private val smoothing: Float = 0.35f) {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).enableTracking().build())
    private var previous = DriverMotion()

    fun process(image: ImageProxy, onResult: (DriverMotion?) -> Unit) {
        val media = image.image
        if (media == null) { image.close(); onResult(null); return }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val next = face?.let(::toMotion)
                previous = if (next == null) previous else previous.smoothWith(next, smoothing)
                onResult(next?.let { previous })
            }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener { image.close() }
    }

    private fun toMotion(face: Face): DriverMotion {
        fun p(v: Float?) = ((v ?: 0f) + 1f) / 2f
        val leftEye = p(face.leftEyeOpenProbability)
        val rightEye = p(face.rightEyeOpenProbability)
        val smile = p(face.smilingProbability)
        return DriverMotion(
            yaw = face.headEulerAngleY / 45f,
            pitch = face.headEulerAngleX / 45f,
            roll = face.headEulerAngleZ / 45f,
            eyeLeft = 1f - leftEye,
            eyeRight = 1f - rightEye,
            mouthOpen = (1f - p(face.smilingProbability)).coerceIn(0f,1f),
            smile = smile,
            browLeft = 0f,
            browRight = 0f,
            trackingId = face.trackingId
        )
    }
}
