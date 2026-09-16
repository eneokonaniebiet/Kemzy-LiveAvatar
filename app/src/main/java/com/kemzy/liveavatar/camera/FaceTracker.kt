package com.kemzy.liveavatar.camera

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

class FaceTracker(private val smoothing: Float = 0.35f) {
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .enableTracking()
        .build()

    private val detector: FaceDetector = FaceDetection.getClient(detectorOptions)
    private var previous: DriverMotion = DriverMotion()

    fun process(image: ImageProxy, onResult: (DriverMotion?) -> Unit) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            onResult(null)
            return
        }

        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face: Face? = faces.maxByOrNull { candidate ->
                    candidate.boundingBox.width() * candidate.boundingBox.height()
                }
                val next = face?.let { toMotion(it) }
                if (next != null) {
                    previous = previous.smoothWith(next, smoothing)
                }
                onResult(next?.let { previous })
            }
            .addOnFailureListener {
                onResult(null)
            }
            .addOnCompleteListener {
                image.close()
            }
    }

    fun close() {
        detector.close()
    }

    private fun toMotion(face: Face): DriverMotion {
        val leftEyeOpen = face.leftEyeOpenProbability ?: 1f
        val rightEyeOpen = face.rightEyeOpenProbability ?: 1f
        val smile = face.smilingProbability ?: 0f

        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val mouthMidY = if (mouthLeft != null && mouthRight != null) {
            (mouthLeft.y + mouthRight.y) * 0.5f
        } else {
            0f
        }
        val faceHeight = face.boundingBox.height().coerceAtLeast(1)
        val mouthOpen = if (mouthBottom != null && mouthLeft != null && mouthRight != null) {
            ((mouthBottom.y - mouthMidY) / faceHeight.toFloat() * 8f).coerceIn(0f, 1f)
        } else {
            0f
        }

        return DriverMotion(
            yaw = (face.headEulerAngleY / 45f).coerceIn(-1f, 1f),
            pitch = (face.headEulerAngleX / 45f).coerceIn(-1f, 1f),
            roll = (face.headEulerAngleZ / 45f).coerceIn(-1f, 1f),
            eyeLeft = 1f - leftEyeOpen.coerceIn(0f, 1f),
            eyeRight = 1f - rightEyeOpen.coerceIn(0f, 1f),
            mouthOpen = mouthOpen,
            smile = smile.coerceIn(0f, 1f),
            trackingId = face.trackingId
        )
    }
}
