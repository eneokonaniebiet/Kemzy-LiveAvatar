package com.kemzy.liveavatar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var avatar: ImageView
    private lateinit var status: TextView
    private lateinit var liveButton: Button
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val live = AtomicBoolean(false)
    private var api: KemzyApi? = null
    private var sessionId: String? = null
    private var sourceBitmap: Bitmap? = null
    private var lastRenderAt = 0L

    private val pickSource = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            sourceBitmap = bitmap
            avatar.setImageBitmap(bitmap)
            avatar.visibility = ImageView.VISIBLE
            status.text = "Source loaded · tap Go Live"
        } catch (t: Throwable) {
            status.text = "Source error: ${t.message}"
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
        val sourceButton: Button = findViewById(R.id.sourceButton)

        api = KemzyApi(BuildConfig.KEMZY_API_BASE_URL.trimEnd('/'))
        sourceButton.setOnClickListener { pickSource.launch("image/*") }
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
        status.text = if (enabled) "Live tracking · turn your head" else "Live paused"
        if (enabled) ensureSession()
    }

    private fun ensureSession() {
        if (sessionId != null || sourceBitmap == null) return
        networkExecutor.execute {
            try {
                val id = api!!.createSession()
                val uploaded = api!!.uploadSource(id, sourceBitmap!!)
                if (uploaded) {
                    sessionId = id
                    mainHandler.post { status.text = "Live tracking · turn your head" }
                } else {
                    mainHandler.post { status.text = "GPU source upload failed" }
                }
            } catch (t: Throwable) {
                mainHandler.post { status.text = "API error: ${t.message}" }
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also {
                it.surfaceProvider = preview.surfaceProvider
            }
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .enableTracking()
                    .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyze(detector, imageProxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
            status.text = "Camera ready · select a source"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(detector: com.google.mlkit.vision.face.FaceDetector, proxy: ImageProxy) {
        val media = proxy.image ?: run { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener(cameraExecutor) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null && live.get()) handleFace(face)
            }
            .addOnCompleteListener(cameraExecutor) { proxy.close() }
    }

    private fun handleFace(face: Face) {
        val now = System.currentTimeMillis()
        if (now - lastRenderAt < 100) return
        lastRenderAt = now
        val packet = MotionPacketMapper.map(
            yawDegrees = face.headEulerAngleY,
            pitchDegrees = face.headEulerAngleX,
            rollDegrees = face.headEulerAngleZ,
            smile = face.smilingProbability ?: 0f,
            leftEyeOpen = face.leftEyeOpenProbability ?: 1f,
            rightEyeOpen = face.rightEyeOpenProbability ?: 1f,
        )
        if (sessionId == null) {
            ensureSession()
            return
        }
        val id = sessionId ?: return
        networkExecutor.execute {
            try {
                val rendered = api!!.render(id, now, packet)
                if (rendered != null) mainHandler.post {
                    avatar.setImageBitmap(rendered)
                    avatar.visibility = ImageView.VISIBLE
                }
            } catch (t: Throwable) {
                mainHandler.post { status.text = "Render error: ${t.message}" }
            }
        }
    }

    override fun onDestroy() {
        live.set(false)
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
