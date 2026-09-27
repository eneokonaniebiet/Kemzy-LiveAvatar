package com.kemzy.liveavatar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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
import okhttp3.WebSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var avatar: ImageView
    private lateinit var status: TextView
    private lateinit var liveButton: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val live = AtomicBoolean(false)
    private val sessionStarting = AtomicBoolean(false)

    private var api: KemzyApi? = null
    private var sessionId: String? = null
    private var stream: WebSocket? = null
    private var lastFrameAt = 0L

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else status.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        preview = findViewById(R.id.cameraPreview)
        avatar = findViewById(R.id.avatarPreview)
        status = findViewById(R.id.status)
        liveButton = findViewById(R.id.liveButton)

        api = KemzyApi(BuildConfig.KEMZY_API_BASE_URL.trimEnd('/'))
        liveButton.setOnClickListener { if (live.get()) stopLive() else startLive() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startLive() {
        live.set(true)
        liveButton.text = "Stop Live"
        avatar.visibility = View.VISIBLE
        sessionId = null
        stream?.close(1000, "new session")
        stream = null
        sessionStarting.set(false)

        val runner = KaggleNotebookRunner(
            host = root,
            statusView = status,
            notebookUrl = BuildConfig.KAGGLE_NOTEBOOK_URL,
            apiBaseUrl = BuildConfig.KEMZY_API_BASE_URL.trimEnd('/'),
            onReady = {
                mainHandler.post {
                    if (live.get()) status.text = "Loading LivePortrait…"
                }
            },
            onLoginRequired = {
                mainHandler.post {
                    status.text = "Sign in to Kaggle once, then start again"
                }
            },
            onError = { error ->
                mainHandler.post {
                    status.text = "GPU start error: $error"
                    live.set(false)
                    liveButton.text = "Go Live"
                }
            }
        )
        runner.start()
    }

    private fun stopLive() {
        live.set(false)
        stream?.close(1000, "live stopped")
        stream = null
        sessionId = null
        sessionStarting.set(false)
        liveButton.text = "Go Live"
        status.text = "Live paused"
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
            )

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(detector, proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                previewUseCase,
                analysis
            )
            status.text = "Camera ready"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(
        detector: com.google.mlkit.vision.face.FaceDetector,
        proxy: ImageProxy
    ) {
        val media = proxy.image ?: run {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener(cameraExecutor) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null && live.get()) sendCameraFrame(proxy, face)
            }
            .addOnCompleteListener(cameraExecutor) { proxy.close() }
    }

    private fun sendCameraFrame(proxy: ImageProxy, face: Face) {
        val socket = stream
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 100) return
        lastFrameAt = now

        val jpeg = api?.bitmapToJpeg(proxyToBitmap(proxy), 65) ?: return
        if (socket == null) {
            if (sessionStarting.compareAndSet(false, true)) {
                networkExecutor.execute {
                    try {
                        val localApi = api ?: return@execute
                        val newSession = localApi.createSession()
                        if (!localApi.uploadSource(newSession, proxyToBitmap(proxy))) {
                            throw IllegalStateException("Source image upload failed")
                        }
                        sessionId = newSession
                        if (!live.get()) return@execute

                        stream = localApi.openLiveStream(
                            sessionId = newSession,
                            onFrame = { bitmap ->
                                mainHandler.post {
                                    avatar.setImageBitmap(bitmap)
                                    avatar.visibility = View.VISIBLE
                                    status.text = "Ready"
                                }
                            },
                            onError = { error ->
                                mainHandler.post { status.text = "GPU stream error: $error" }
                            },
                            onOpen = {
                                mainHandler.post { status.text = "Ready" }
                            }
                        )
                    } catch (t: Throwable) {
                        sessionStarting.set(false)
                        mainHandler.post {
                            status.text = "GPU session error: " + (t.message ?: "unknown error")
                        }
                    }
                }
            }
            return
        }

        val expression = MutableList(63) { 0f }
        expression[0] = (1f - (face.leftEyeOpenProbability ?: 1f)).coerceIn(0f, 1f)
        expression[3] = (1f - (face.rightEyeOpenProbability ?: 1f)).coerceIn(0f, 1f)
        expression[18] = (face.smilingProbability ?: 0f).coerceIn(0f, 1f)
        expression[21] = (face.smilingProbability ?: 0f).coerceIn(0f, 1f)

        val packet = MotionPacket(
            pose = listOf(
                face.headEulerAngleX / 25f,
                face.headEulerAngleY / 25f,
                face.headEulerAngleZ / 25f
            ),
            expression = expression
        )
        api?.let { localApi ->
            networkExecutor.execute {
                localApi.sendMotion(socket, now, packet, jpeg)
            }
        }
    }

    private fun proxyToBitmap(proxy: ImageProxy): android.graphics.Bitmap {
        val yuv = imageProxyToJpeg(proxy, 80)
        return android.graphics.BitmapFactory.decodeByteArray(yuv, 0, yuv.size)
            ?: throw IllegalStateException("Could not encode camera frame")
    }

    private fun imageProxyToJpeg(proxy: ImageProxy, quality: Int): ByteArray {
        val width = proxy.width
        val height = proxy.height
        val nv21 = yuv420ToNv21(proxy)
        val yuv = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            width,
            height,
            null
        )
        val out = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(proxy: ImageProxy): ByteArray {
        val width = proxy.width
        val height = proxy.height
        val planes = proxy.planes
        val out = ByteArray(width * height + width * height / 2)
        var offset = 0

        val y = planes[0]
        val yBuffer = y.buffer
        val yRow = ByteArray(y.rowStride)
        for (row in 0 until height) {
            yBuffer.position(row * y.rowStride)
            val length = if (y.pixelStride == 1) width else (width - 1) * y.pixelStride + 1
            yBuffer.get(yRow, 0, length)
            for (col in 0 until width) out[offset++] = yRow[col * y.pixelStride]
        }

        val u = planes[1]
        val v = planes[2]
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val uRow = ByteArray(u.rowStride)
        val vRow = ByteArray(v.rowStride)
        val cw = width / 2
        val ch = height / 2

        for (row in 0 until ch) {
            uBuffer.position(row * u.rowStride)
            vBuffer.position(row * v.rowStride)
            val ul = if (u.pixelStride == 1) cw else (cw - 1) * u.pixelStride + 1
            val vl = if (v.pixelStride == 1) cw else (cw - 1) * v.pixelStride + 1
            uBuffer.get(uRow, 0, ul)
            vBuffer.get(vRow, 0, vl)
            for (col in 0 until cw) {
                out[offset++] = vRow[col * v.pixelStride]
                out[offset++] = uRow[col * u.pixelStride]
            }
        }
        return out
    }

    override fun onDestroy() {
        stopLive()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
