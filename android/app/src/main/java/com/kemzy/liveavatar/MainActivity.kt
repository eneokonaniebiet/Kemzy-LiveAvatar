package com.kemzy.liveavatar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.opengl.GLSurfaceView
import android.view.Gravity
import android.widget.FrameLayout
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.kemzy.liveavatar.renderer.PortraitGlRenderer
import com.kemzy.liveavatar.camera.KemzyCameraYuvAnalyzer
import kotlin.math.max

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
    private lateinit var glSurfaceView: GLSurfaceView
    private var portraitRenderer: PortraitGlRenderer? = null
    private var glRendererInitialized = false
    private var displayedLiveBitmap: Bitmap? = null

    private var api: KemzyApi? = null
    private var sessionId: String? = null
    private var sourceBitmap: Bitmap? = null
    private var sourceUri: Uri? = null
    private var sourceMime: String = ""
    private var stream: WebSocket? = null
    private var lastFrameAt = 0L

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
                            avatar.alpha = 1f
                            avatar.visibility = View.VISIBLE
                            attachPortraitRenderer(boundedBitmap)
                            status.text = "Portrait ready · native OpenGL preview initialized"
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
        glSurfaceView = findViewById(R.id.kemzy_gl_surface_view)
        glSurfaceView.setEGLContextClientVersion(3)
        glSurfaceView.visibility = View.GONE

        api = KemzyApi(BuildConfig.KEMZY_API_BASE_URL)
        avatar.setImageDrawable(null)
        avatar.alpha = 0f
        sourceButton.setOnClickListener { pickSource.launch("image/*") }
        moreButton.setOnClickListener { startActivity(Intent(this, KemzyPagesActivity::class.java)) }
        liveButton.setOnClickListener { toggleLive() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    /** Creates a small diagnostic GPU preview without covering the camera or controls. */
    private fun attachPortraitRenderer(bitmap: Bitmap) {
        if (!glRendererInitialized) {
            val renderer = PortraitGlRenderer(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            portraitRenderer = renderer
            glSurfaceView.setRenderer(renderer)
            glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            glRendererInitialized = true
            glSurfaceView.onResume()
        } else {
            portraitRenderer?.updatePortrait(bitmap)
        }
        localAnimator.onHolisticTrackingDispatched = { face, pose, leftHand, rightHand ->
            // The camera executor only queues a copied latest sample; all GL/native work stays on the GL thread.
            portraitRenderer?.enqueueHolisticFrameData(face, pose, leftHand, rightHand)
        }
        localAnimator.onSegmentationMaskDispatched = { width, height, alphaBuffer ->
            portraitRenderer?.uploadSegmentationMaskHardware(width, height, alphaBuffer)
        }
        glSurfaceView.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (glRendererInitialized) glSurfaceView.onResume()
    }

    override fun onPause() {
        if (glRendererInitialized) glSurfaceView.onPause()
        super.onPause()
    }

    private fun toggleLive() {
        if (live.get()) {
            live.set(false)
            liveButton.text = "Go Live"
            stopStream()
            recycleDisplayedLiveBitmap()
            avatar.setImageBitmap(sourceBitmap)
            avatar.alpha = 1f
            showControls()
            status.text = "On-device animation paused"
            return
        }

        if (sourceBitmap == null || !localAnimator.isReady()) {
            status.text = "Choose a clear portrait and wait until the local model is ready"
            return
        }

        live.set(true)
        lastFrameAt = 0L
        liveButton.text = "Stop Live"
        recycleDisplayedLiveBitmap()
        // CameraX is now the full-screen base. The avatar view contains only
        // a transparent face overlay, so body/hands stay at camera speed.
        avatar.setImageDrawable(null)
        avatar.alpha = 1f
        avatar.visibility = View.VISIBLE
        preview.visibility = View.VISIBLE
        hideControls()
        val engine = if (localAnimator.isGpuActive()) "GPU" else "CPU fallback"
        status.text = "ON-DEVICE LIVE · $engine · camera body + local face"
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
            imageAnalysis.setAnalyzer(
                cameraExecutor,
                KemzyCameraYuvAnalyzer(
                    rendererProvider = { portraitRenderer },
                    shouldUpload = { live.get() },
                    onFrame = { imageProxy -> handleCameraFrame(imageProxy) }
                )
            )

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
        // Face tracking is intentionally lower-rate than the camera preview.
        // The preview stays at the camera's native rate while the overlay follows.
        if (now - lastFrameAt < 66L) {
            imageProxy.close()
            return
        }
        lastFrameAt = now

        var driverBitmap: Bitmap? = null
        try {
            driverBitmap = imageProxyToBitmap(imageProxy, 288)
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

    /** Fast YUV_420_888 -> RGB conversion. Avoids JPEG encode/decode on every frame. */
    private fun imageProxyToBitmap(imageProxy: ImageProxy, maxDimension: Int): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height
        val scale = minOf(1f, maxDimension.toFloat() / max(width, height))
        val outWidth = (width * scale).toInt().coerceAtLeast(1)
        val outHeight = (height * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(outWidth * outHeight)
        val planes = imageProxy.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (oy in 0 until outHeight) {
            val sy = (oy / scale).toInt().coerceIn(0, height - 1)
            val uvY = sy / 2
            val yRow = sy * yPlane.rowStride
            val uRow = uvY * uPlane.rowStride
            val vRow = uvY * vPlane.rowStride
            for (ox in 0 until outWidth) {
                val sx = (ox / scale).toInt().coerceIn(0, width - 1)
                val uvX = sx / 2
                val y = yBuffer.get(yRow + sx * yPlane.pixelStride).toInt() and 0xff
                val u = (uBuffer.get(uRow + uvX * uPlane.pixelStride).toInt() and 0xff) - 128
                val v = (vBuffer.get(vRow + uvX * vPlane.pixelStride).toInt() and 0xff) - 128
                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
                pixels[oy * outWidth + ox] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val decoded = Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else decoded
    }

    private fun resizeSourceBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val scale = minOf(1f, maxDimension.toFloat() / max(bitmap.width, bitmap.height))
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
    }

    override fun onDestroy() {
        live.set(false)
        if (glRendererInitialized) glSurfaceView.onPause()
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
