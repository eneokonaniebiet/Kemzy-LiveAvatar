package com.kemzy.liveavatar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.net.Uri
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
import java.io.ByteArrayOutputStream
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
    private var sourceUri: Uri? = null
    private var sourceMime: String = ""
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
                sourceUri = uri
                sourceMime = mime.ifBlank { "image/jpeg" }
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
        val uri = sourceUri
        if (source == null || uri == null) {
            status.text = "Select an image or video source first"
            live.set(false)
            liveButton.text = "Go Live"
            return
        }
        if (sessionId != null && stream != null) return

        networkExecutor.execute {
            try {
                val sourceType = if (sourceMime.startsWith("video/")) "video" else "image"
                val filename = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                } ?: if (sourceType == "video") "source.mp4" else "source.jpg"
                val id = api!!.createSession(sourceType)
                check(api!!.uploadSource(id, contentResolver, uri, sourceMime, filename)) {
                    "Cloud renderer source upload failed"
                }
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

        val drivingFrame = try {
            encodeDrivingFrame(imageProxy)
        } catch (t: Throwable) {
            mainHandler.post { if (live.get()) status.text = "Camera frame error: ${t.message}" }
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
                    sendFaceMotion(face, drivingFrame)
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

    private fun encodeDrivingFrame(imageProxy: ImageProxy): String {
        val width = imageProxy.width
        val height = imageProxy.height
        val planes = imageProxy.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val nv21 = ByteArray(width * height + width * height / 2)

        var offset = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[offset++] = yPlane.buffer.get(rowStart + col * yPlane.pixelStride)
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val vStart = row * vPlane.rowStride
            val uStart = row * uPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[offset++] = vPlane.buffer.get(vStart + col * vPlane.pixelStride)
                nv21[offset++] = uPlane.buffer.get(uStart + col * uPlane.pixelStride)
            }
        }

        val jpeg = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        check(yuv.compressToJpeg(Rect(0, 0, width, height), 55, jpeg)) {
            "Could not encode camera frame"
        }

        val original = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size())
            ?: error("Could not decode camera frame")
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotated = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                .also { if (it !== original) original.recycle() }
        } else original

        val scale = minOf(1f, 320f / maxOf(rotated.width, rotated.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1),
                (rotated.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== rotated) rotated.recycle() }
        } else rotated

        val compressed = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 55, compressed)
        if (!small.isRecycled) small.recycle()
        return Base64.encodeToString(compressed.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendFaceMotion(face: Face, drivingFrame: String) {
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
            smile,
            drivingFrame
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
