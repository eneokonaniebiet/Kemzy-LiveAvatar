package com.kemzy.liveavatar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import okhttp3.WebSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var avatar: ImageView
    private lateinit var status: TextView
    private lateinit var liveButton: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val live = AtomicBoolean(false)
    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.12f)
                .enableTracking()
                .build()
        )
    }

    private var api: KemzyApi? = null
    private var sessionId: String? = null
    private var sourceBitmap: Bitmap? = null
    private var stream: WebSocket? = null
    private var lastSentAt = 0L

    private val pickSource = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        networkExecutor.execute {
            try {
                val mime = contentResolver.getType(uri).orEmpty()
                val bitmap = if (mime.startsWith("video/")) {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(this, uri)
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } else {
                    contentResolver.openInputStream(uri).use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                }
                checkNotNull(bitmap) { "Could not read the selected media" }
                sourceBitmap = bitmap
                mainHandler.post {
                    avatar.setImageBitmap(bitmap)
                    avatar.visibility = ImageView.VISIBLE
                    status.text = if (mime.startsWith("video/")) {
                        "Video source loaded · reference frame ready"
                    } else {
                        "Image source loaded · tap Go Live"
                    }
                }
            } catch (t: Throwable) {
                mainHandler.post { status.text = "Source error: ${t.message}" }
            }
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.cameraPreview)
        avatar = findViewById(R.id.avatarPreview)
        status = findViewById(R.id.status)
        liveButton = findViewById(R.id.liveButton)

        api = KemzyApi(BuildConfig.KEMZY_API_BASE_URL)
        findViewById<Button>(R.id.sourceButton).setOnClickListener { pickSource.launch("*/*") }
        findViewById<Button>(R.id.moreButton).setOnClickListener {
            startActivity(Intent(this, KemzyPagesActivity::class.java))
        }
        liveButton.setOnClickListener { toggleLive() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleLive() {
        val enabled = !live.get()
        live.set(enabled)
        liveButton.text = if (enabled) "Stop Live" else "Go Live"
        if (enabled) ensureSession() else stopStream()
    }

    private fun ensureSession() {
        val source = sourceBitmap
        if (source == null) {
            status.text = "Select an image or video source first"
            live.set(false)
            liveButton.text = "Go Live"
            return
        }
        if (sessionId != null && stream != null) return

        networkExecutor.execute {
            try {
                val id = api!!.createSession("image")
                check(api!!.uploadImageSource(id, source)) { "Cloud renderer source upload failed" }
                sessionId = id
                stream = api!!.openStream(id, object : KemzyApi.StreamListener {
                    override fun onOpen(webSocket: WebSocket) {
                        mainHandler.post {
                            status.text = "LIVE · cloud neural renderer connected"
                        }
                    }

                    override fun onFrame(bitmap: Bitmap) {
                        mainHandler.post {
                            avatar.setImageBitmap(bitmap)
                            avatar.visibility = ImageView.VISIBLE
                        }
                    }

                    override fun onError(error: Throwable) {
                        mainHandler.post {
                            status.text = "Renderer error: ${error.message}"
                        }
                    }

                    override fun onClosed() {
                        mainHandler.post { status.text = "Renderer disconnected" }
                    }
                })
            } catch (t: Throwable) {
                live.set(false)
                mainHandler.post {
                    liveButton.text = "Go Live"
                    status.text = "Cloud renderer connection failed: ${t.message}"
                }
            }
        }
    }

    private fun stopStream() {
        stream?.close(1000, "user stopped")
        stream = null
        sessionId = null
        status.text = "Live paused"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also {
                it.surfaceProvider = preview.surfaceProvider
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> handleCameraFrame(imageProxy) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                previewUseCase,
                imageAnalysis
            )
            status.text = "Camera ready · select a source"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleCameraFrame(imageProxy: ImageProxy) {
        if (!live.get() || stream == null) {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSentAt < 66L) {
            imageProxy.close()
            return
        }
        lastSentAt = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(input)
            .addOnSuccessListener(cameraExecutor) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null) {
                    sendFaceMotion(face)
                }
            }
            .addOnFailureListener(cameraExecutor) { error ->
                mainHandler.post {
                    if (live.get()) status.text = "Face tracking error: ${error.message}"
                }
            }
            .addOnCompleteListener(cameraExecutor) {
                imageProxy.close()
            }
    }

    private fun sendFaceMotion(face: Face) {
        val socket = stream ?: return
        val timestamp = System.currentTimeMillis()

        val yaw = normalizeAngle(face.headEulerAngleY)
        val pitch = normalizeAngle(face.headEulerAngleX)
        val roll = normalizeAngle(face.headEulerAngleZ)

        val leftEye = (face.leftEyeOpenProbability ?: 1f).coerceIn(0f, 1f)
        val rightEye = (face.rightEyeOpenProbability ?: 1f).coerceIn(0f, 1f)
        val smile = (face.smilingProbability ?: 0f).coerceIn(0f, 1f)
        val mouthOpen = estimateMouthOpen(face)

        api?.sendDriverFrame(
            socket,
            timestamp,
            yaw,
            pitch,
            roll,
            leftEye,
            rightEye,
            mouthOpen,
            smile
        )
    }

    private fun estimateMouthOpen(face: Face): Float {
        val left = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val right = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val bottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        if (left == null || right == null || bottom == null) return 0f

        val mouthWidth = kotlin.math.hypot(
            (right.x - left.x).toDouble(),
            (right.y - left.y).toDouble()
        ).toFloat().coerceAtLeast(1f)
        val centerY = (left.y + right.y) * 0.5f
        return ((kotlin.math.abs(bottom.y - centerY) / mouthWidth) * 2.5f)
            .coerceIn(0f, 1f)
    }

    private fun normalizeAngle(degrees: Float): Float =
        (degrees / 30f).coerceIn(-1f, 1f)

    override fun onDestroy() {
        live.set(false)
        stopStream()
        detector.close()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
