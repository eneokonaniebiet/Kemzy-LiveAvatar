package com.kemzy.liveavatar.camera

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceTracker(private val smoothing: Float = 0.35f) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
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
                onResult(if (next == null) null else previous)
            }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener { image.close() }
    }

    fun close() { detector.close() }

    private fun toMotion(face: Face): DriverMotion {
        val leftEye = face.leftEyeOpenProbability.coerceIn(0f, 1f)
        val rightEye = face.rightEyeOpenProbability.coerceIn(0f, 1f)
        val smile = face.smilingProbability.coerceIn(0f, 1f)
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val mouthMidY = if (mouthLeft != null && mouthRight != null) (mouthLeft.y + mouthRight.y) * 0.5f else 0f
        val faceHeight = face.boundingBox.height().coerceAtLeast(1)
        val mouthOpen = if (mouthBottom != null && mouthLeft != null && mouthRight != null) {
            ((mouthBottom.y - mouthMidY).toFloat() / faceHeight.toFloat() * 8f).coerceIn(0f, 1f)
        } else 0f
        return DriverMotion(
            yaw = (face.headEulerAngleY / 45f).coerceIn(-1f, 1f),
            pitch = (face.headEulerAngleX / 45f).coerceIn(-1f, 1f),
            roll = (face.headEulerAngleZ / 45f).coerceIn(-1f, 1f),
            eyeLeft = 1f - leftEye,
            eyeRight = 1f - rightEye,
            mouthOpen = mouthOpen,
            smile = smile,
            trackingId = face.trackingId
        )
    }
}
