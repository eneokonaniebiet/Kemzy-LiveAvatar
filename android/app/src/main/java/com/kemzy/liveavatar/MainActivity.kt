package com.kemzy.liveavatar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
    private var stream: WebSocket? = null
    private var lastFrameAt = 0L
    private var runner: KaggleNotebookRunner? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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

        liveButton.setOnClickListener { toggleLive() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleLive() {
        if (live.get()) {
            stopLive()
        } else {
            startLive()
        }
    }

    private fun startLive() {
        live.set(true)
        liveButton.text = "Stop Live"
        avatar.visibility = View.VISIBLE
        status.text = "Starting Kémzy AI…"

        runner?.stop()
        runner = KaggleNotebookRunner(
            host = root,
            statusView = status,
            notebookUrl = BuildConfig.KAGGLE_NOTEBOOK_URL,
            onTunnelReady = { tunnelUrl ->
                mainHandler.post {
                    if (!live.get()) return@post
                    status.text = "Connecting to GPU…"
                    connectToTunnel(tunnelUrl)
                }
            },
            onLoginRequired = {
                mainHandler.post {
                    status.text = "Kaggle sign-in required once"
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
        runner?.start()
    }

    private fun connectToTunnel(tunnelUrl: String) {
        stream?.close(1000, "reconnecting")
        stream = KemzyApi("").openDirectTunnel(
            tunnelUrl = tunnelUrl,
            onOpen = {
                mainHandler.post { status.text = "Ready" }
            },
            onFrame = { bitmap ->
                mainHandler.post {
                    avatar.setImageBitmap(bitmap)
                    avatar.visibility = View.VISIBLE
                }
            },
            onError = { error ->
                mainHandler.post {
                    status.text = "GPU connection error: $error"
                }
            }
        )
    }

    private fun stopLive() {
        live.set(false)
        stream?.close(1000, "live stopped")
        stream = null
        runner?.stop()
        runner = null
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
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyze(detector, imageProxy)
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
            status.text = "Camera ready"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(detector: com.google.mlkit.vision.face.FaceDetector, proxy: ImageProxy) {
        val media = proxy.image ?: run {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener(cameraExecutor) { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face != null && live.get()) {
                    sendCameraFrame(proxy, face)
                }
            }
            .addOnCompleteListener(cameraExecutor) {
                proxy.close()
            }
    }

    private fun sendCameraFrame(proxy: ImageProxy, face: Face) {
        val socket = stream ?: return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 33) return
        lastFrameAt = now

        val jpeg = imageProxyToJpeg(proxy) ?: return
        networkExecutor.execute {
            if (live.get()) socket.send(okio.ByteString.of(*jpeg))
        }
    }

    private fun imageProxyToJpeg(proxy: ImageProxy, quality: Int = 70): ByteArray? {
        return try {
            val width = proxy.width
            val height = proxy.height
            val nv21 = yuv420ToNv21(proxy)
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun yuv420ToNv21(proxy: ImageProxy): ByteArray {
        val width = proxy.width
        val height = proxy.height
        val planes = proxy.planes
        val out = ByteArray(width * height + (width * height / 2))
        var outputOffset = 0

        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yRow = ByteArray(yRowStride)
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            val length = if (yPixelStride == 1) width else (width - 1) * yPixelStride + 1
            yBuffer.get(yRow, 0, length)
            var col = 0
            while (col < width) {
                out[outputOffset++] = yRow[col * yPixelStride]
                col++
            }
        }

        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)

        for (row in 0 until chromaHeight) {
            uBuffer.position(row * uRowStride)
            vBuffer.position(row * vRowStride)
            val uLength = if (uPixelStride == 1) chromaWidth else (chromaWidth - 1) * uPixelStride + 1
            val vLength = if (vPixelStride == 1) chromaWidth else (chromaWidth - 1) * vPixelStride + 1
            uBuffer.get(uRow, 0, uLength)
            vBuffer.get(vRow, 0, vLength)
            for (col in 0 until chromaWidth) {
                out[outputOffset++] = vRow[col * vPixelStride]
                out[outputOffset++] = uRow[col * uPixelStride]
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
