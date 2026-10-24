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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import okhttp3.WebSocket
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var avatar: ImageView
    private lateinit var status: TextView
    private lateinit var liveButton: Button
    private lateinit var sourceButton: Button
    private lateinit var moreButton: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val live = AtomicBoolean(false)
    private lateinit var localAnimator: LocalPortraitAnimator
    private var lastStatusUpdateAt = 0L
    private var displayedLiveBitmap: Bitmap? = null

    // Kept only for compatibility with the existing API layer. The on-device
    // Studio path does not create a cloud session or upload camera frames.
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
                    contentResolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input) }
                }
                checkNotNull(bitmap) { "Could not read the selected media" }
                val boundedBitmap = resizeSourceBitmap(bitmap, 512)
                if (boundedBitmap !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                val oldSource = sourceBitmap
                sourceBitmap = boundedBitmap
                sourceUri = uri
                sourceMime = mime.ifBlank { "image/jpeg" }
                cameraExecutor.execute {
                    try {
                        localAnimator.setSource(boundedBitmap)
                        mainHandler.post {
                            oldSource?.let { if (it !== boundedBitmap && !it.isRecycled) it.recycle() }
                            avatar.setImageBitmap(boundedBitmap)
                            avatar.visibility = View.VISIBLE
                            status.text = "Portrait ready · local hybrid model loaded"
                        }
                    } catch (t: Throwable) {
                        mainHandler.post { status.text = "Local portrait setup failed: ${t.message}" }
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
        localAnimator = LocalPortraitAnimator(applicationContext)
        preview = findViewById(R.id.cameraPreview)
        avatar = findViewById(R.id.avatarPreview)
        status = findViewById(R.id.status)
        liveButton = findViewById(R.id.liveButton)
        sourceButton = findViewById(R.id.sourceButton)
        moreButton = findViewById(R.id.moreButton)

        api = KemzyApi(BuildConfig.KEMZY_API_BASE_URL)
        sourceButton.setOnClickListener { pickSource.launch("image/*") }
        moreButton.setOnClickListener { startActivity(Intent(this, KemzyPagesActivity::class.java)) }
        liveButton.setOnClickListener { toggleLive() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleLive() {
        if (live.get()) {
            live.set(false)
            liveButton.text = "Go Live"
            stopStream()
            recycleDisplayedLiveBitmap()
            showControls()
            status.text = "On-device animation paused"
            return
        }

        if (sourceBitmap == null || !localAnimator.isReady()) {
            status.text = "Choose a clear portrait and wait until the local model is ready"
            return
        }

        live.set(true)
        lastSentAt = 0L
        liveButton.text = "Stop Live"
        recycleDisplayedLiveBitmap()
        avatar.setImageBitmap(sourceBitmap)
        avatar.visibility = View.VISIBLE
        hideControls()
        val engine = if (localAnimator.isGpuActive()) "GPU" else "CPU fallback"
        status.text = "ON-DEVICE LIVE · hybrid avatar · $engine · no cloud"
    }

    private fun hideControls() {
        sourceButton.visibility = View.GONE
        moreButton.visibility = View.GONE
        liveButton.visibility = View.GONE
        status.visibility = View.GONE
        hideSystemBars()
    }

    private fun showControls() {
        sourceButton.visibility = View.VISIBLE
        moreButton.visibility = View.VISIBLE
        liveButton.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        showSystemBars()
    }

    private fun hideSystemBars() {
        val controller = window.insetsController
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun showSystemBars() {
        window.insetsController?.show(WindowInsets.Type.systemBars())
    }

    private fun stopStream() {
        stream?.close(1000, "on-device mode")
        stream = null
        sessionId = null
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> handleCameraFrame(imageProxy) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, imageAnalysis)
            status.text = "Camera ready · select a source"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleCameraFrame(imageProxy: ImageProxy) {
        if (!live.get() || !::localAnimator.isInitialized || !localAnimator.isReady()) {
            imageProxy.close()
            return
        }

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSentAt < 100L) {
            imageProxy.close()
            return
        }
        lastSentAt = now

        var driverBitmap: Bitmap? = null
        try {
            driverBitmap = imageProxyToBitmap(imageProxy)
            val animated = localAnimator.processFrame(driverBitmap)
            if (animated != null) {
                mainHandler.post {
                    if (live.get()) {
                        val previous = displayedLiveBitmap
                        displayedLiveBitmap = animated
                        avatar.setImageBitmap(animated)
                        avatar.visibility = View.VISIBLE
                        previous?.let { if (it !== animated && !it.isRecycled) it.recycle() }
                    } else if (!animated.isRecycled) {
                        animated.recycle()
                    }
                }
            }
        } catch (t: Throwable) {
            mainHandler.post {
                if (live.get()) status.text = "On-device animation error: ${t.message}"
            }
        } finally {
            driverBitmap?.let { if (!it.isRecycled) it.recycle() }
            imageProxy.close()
        }
    }

    private fun recycleDisplayedLiveBitmap() {
        displayedLiveBitmap?.let { if (!it.isRecycled) it.recycle() }
        displayedLiveBitmap = null
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
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
            for (col in 0 until width) nv21[offset++] = yPlane.buffer.get(rowStart + col * yPlane.pixelStride)
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
        check(yuv.compressToJpeg(Rect(0, 0, width, height), 65, jpeg)) { "Could not convert camera frame" }
        val decoded = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size()) ?: error("Could not decode camera frame")
        val rotation = imageProxy.imageInfo.rotationDegrees
        val rotated = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val scale = minOf(1f, 320f / maxOf(rotated.width, rotated.height))
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== rotated) rotated.recycle() }
        } else rotated
    }

    private fun resizeSourceBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
    }

    override fun onDestroy() {
        live.set(false)
        stopStream()
        recycleDisplayedLiveBitmap()
        sourceBitmap?.let { if (!it.isRecycled) it.recycle() }
        sourceBitmap = null
        if (::localAnimator.isInitialized) localAnimator.close()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
