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
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import okhttp3.WebSocket
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
    private val renderInFlight = AtomicBoolean(false)
    private var api: KemzyApi? = null
    private var sessionId: String? = null
    private var sourceBitmap: Bitmap? = null
    private var stream: WebSocket? = null
    private var lastSentAt = 0L

    private val pickSource = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
            sourceBitmap = bitmap
            avatar.setImageBitmap(bitmap)
            avatar.visibility = ImageView.VISIBLE
            status.text = "Source loaded · tap Go Live"
        } catch (t: Throwable) { status.text = "Source error: ${t.message}" }
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
        api = KemzyApi(BuildConfig.KEMZY_API_BASE_URL)
        sourceButton.setOnClickListener { pickSource.launch("image/*") }
        liveButton.setOnClickListener { toggleLive() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun toggleLive() {
        val enabled = !live.get()
        live.set(enabled)
        renderInFlight.set(false)
        liveButton.text = if (enabled) "Stop Live" else "Go Live"
        if (enabled) ensureSession() else stopStream()
    }

    private fun ensureSession() {
        val source = sourceBitmap ?: run { status.text = "Select a source portrait first"; live.set(false); return }
        if (sessionId != null && stream != null) return
        networkExecutor.execute {
            try {
                val id = api!!.createSession()
                check(api!!.uploadSource(id, source)) { "GPU source upload failed" }
                sessionId = id
                stream = api!!.openStream(id, object : KemzyApi.StreamListener {
                    override fun onOpen(webSocket: WebSocket) {
                        mainHandler.post { status.text = "LIVE · neural renderer connected" }
                    }
                    override fun onFrame(bitmap: Bitmap) {
                        renderInFlight.set(false)
                        mainHandler.post { avatar.setImageBitmap(bitmap); avatar.visibility = ImageView.VISIBLE }
                    }
                    override fun onError(error: Throwable) {
                        renderInFlight.set(false)
                        mainHandler.post { status.text = "Renderer error: ${error.message}" }
                    }
                    override fun onClosed() {
                        renderInFlight.set(false)
                        mainHandler.post { status.text = "Renderer disconnected" }
                    }
                })
            } catch (t: Throwable) {
                live.set(false)
                renderInFlight.set(false)
                mainHandler.post { status.text = "GPU connection failed: ${t.message}" }
            }
        }
    }

    private fun stopStream() {
        stream?.close(1000, "user stopped")
        stream = null
        sessionId = null
        renderInFlight.set(false)
        status.text = "Live paused"
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .enableTracking()
                    .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(detector, proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
            status.text = "Camera ready · select a source"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(detector: FaceDetector, proxy: ImageProxy) {
        val media = proxy.image ?: run { proxy.close(); return }
        detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener(cameraExecutor) { faces ->
                faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.let { if (live.get()) handleFace(it) }
            }
            .addOnCompleteListener(cameraExecutor) { proxy.close() }
    }

    private fun handleFace(face: Face) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < 33 || renderInFlight.get()) return
        val socket = stream ?: return
        val packet = MotionPacketMapper.map(
            yawDegrees = face.headEulerAngleY,
            pitchDegrees = face.headEulerAngleX,
            rollDegrees = face.headEulerAngleZ,
            smile = face.smilingProbability ?: 0f,
            leftEyeOpen = face.leftEyeOpenProbability ?: 1f,
            rightEyeOpen = face.rightEyeOpenProbability ?: 1f,
            lipOpenRatio = calculateLipOpenRatio(face),
        )
        renderInFlight.set(true)
        lastSentAt = now
        if (!api!!.send(socket, now, packet)) {
            renderInFlight.set(false)
            mainHandler.post { status.text = "Live stream send failed" }
        }
    }

    private fun calculateLipOpenRatio(face: Face): Float {
        val top = face.getContour(FaceContour.MOUTH_TOP)?.points.orEmpty()
        val bottom = face.getContour(FaceContour.MOUTH_BOTTOM)?.points.orEmpty()
        if (top.isEmpty() || bottom.isEmpty()) return 0f
        val all = top + bottom
        val minX = all.minOf { it.x }
        val maxX = all.maxOf { it.x }
        val topY = top.map { it.y }.average().toFloat()
        val bottomY = bottom.map { it.y }.average().toFloat()
        val width = max(1f, maxX - minX)
        return ((bottomY - topY) / width * 3f).coerceIn(0f, 1f)
    }

    override fun onDestroy() {
        live.set(false)
        stopStream()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
