package com.kemzy.liveavatar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.content.Intent
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
                checkNotNull(bitmap) { "Could not read a face source from the selected media" }
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
        val sourceButton: Button = findViewById(R.id.sourceButton)
        val moreButton: Button = findViewById(R.id.moreButton)
        api = KemzyApi(BuildConfig.KEMZY_API_BASE_URL)
        sourceButton.setOnClickListener { pickSource.launch("*/*") }
        moreButton.setOnClickListener { startActivity(Intent(this, KemzyPagesActivity::class.java)) }
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
                val id = api!!.createSession()
                check(api!!.uploadImageSource(id, source)) { "GPU source upload failed" }
                sessionId = id
                stream = api!!.openStream(id, object : KemzyApi.StreamListener {
                    override fun onOpen(webSocket: WebSocket) {
                        mainHandler.post { status.text = "LIVE · PersonaLive neural renderer connected" }
                    }
                    override fun onFrame(bitmap: Bitmap) {
                        mainHandler.post {
                            avatar.setImageBitmap(bitmap)
                            avatar.visibility = ImageView.VISIBLE
                        }
                    }
                    override fun onError(error: Throwable) {
                        mainHandler.post { status.text = "PersonaLive error: ${error.message}" }
                    }
                    override fun onClosed() {
                        mainHandler.post { status.text = "PersonaLive disconnected" }
                    }
                })
            } catch (t: Throwable) {
                live.set(false)
                mainHandler.post {
                    liveButton.text = "Go Live"
                    status.text = "GPU connection failed: ${t.message}"
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> handleCameraFrame(imageProxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, imageAnalysis)
            status.text = "Camera ready · select a source"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes.firstOrNull()
            ?: throw IllegalArgumentException("Camera frame has no image plane")
        val width = imageProxy.width
        val height = imageProxy.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val rowBytes = pixelStride * width
        val buffer = plane.buffer.duplicate()
        val pixels = IntArray(width * height)
        val row = ByteArray(rowBytes)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, rowBytes)
            for (x in 0 until width) {
                val i = x * pixelStride
                val r = row[i].toInt() and 0xff
                val g = row[i + 1].toInt() and 0xff
                val b = row[i + 2].toInt() and 0xff
                val a = if (pixelStride >= 4) row[i + 3].toInt() and 0xff else 0xff
                pixels[y * width + x] =
                    (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }


    private fun handleCameraFrame(imageProxy: ImageProxy) {
        try {
            if (!live.get()) return
            val socket = stream ?: return
            val now = System.currentTimeMillis()
            if (now - lastSentAt < 33L) return
            val bitmap = imageProxyToBitmap(imageProxy)
            lastSentAt = now
            if (!api!!.sendCameraFrame(socket, bitmap)) {
                mainHandler.post { status.text = "Live frame send failed" }
            }
        } catch (t: Throwable) {
            mainHandler.post { status.text = "Camera frame error: ${t.message}" }
        } finally {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        live.set(false)
        stopStream()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
