package com.kemzy.liveavatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight local face-motion compositor.
 *
 * CameraPreview remains the full-screen, full-rate body/background. This class
 * produces only a transparent face overlay, so the phone does not repeatedly
 * redraw the entire camera frame and the user's live body/hands stay natural.
 */
class LocalPortraitAnimator(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var landmarker: FaceLandmarker? = null
    private var sourceBitmap: Bitmap? = null
    private var warpedSource: Bitmap? = null
    private var sourcePoints: List<PointF> = emptyList()
    private var sourceBounds: RectF? = null
    private var neutralDriverPoints: List<PointF>? = null
    private var neutralDriverBounds: RectF? = null
    private var smoothedDriverPoints: MutableList<PointF>? = null
    private var meshBase = FloatArray(0)
    private var meshWarped = FloatArray(0)
    private var influences: Array<VertexInfluence> = emptyArray()
    private var lastTimestampMs = 0L
    private var lastFaceSeenMs = 0L
    private var gpuActive = false

    /**
     * Optional normalized XYZ stream for the native OpenGL renderer.
     * Consumer should enqueue it (not perform GL calls on this camera thread).
     */
    var onNormalizedLandmarks: ((FloatArray) -> Unit)? = null

    @Synchronized
    fun isReady(): Boolean =
        sourceBitmap != null && sourcePoints.isNotEmpty() &&
            landmarker != null && meshBase.isNotEmpty()

    @Synchronized
    fun isGpuActive(): Boolean = gpuActive

    @Synchronized
    fun setSource(bitmap: Bitmap) {
        closeLandmarker()
        neutralDriverPoints = null
        neutralDriverBounds = null
        smoothedDriverPoints = null
        lastFaceSeenMs = 0L
        sourceBitmap = bitmap
        warpedSource?.recycle()
        warpedSource = Bitmap.createBitmap(
            bitmap.width.coerceAtLeast(1),
            bitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )

        val sourceDetector = createLandmarker(RunningMode.IMAGE)
        val sourceResult = try {
            sourceDetector.detect(BitmapImageBuilder(bitmap).build())
        } finally {
            sourceDetector.close()
        }

        val detected = sourceResult.faceLandmarks().firstOrNull()
            ?: throw IllegalArgumentException("No face was found in that photo. Choose a clear, front-facing portrait.")
        sourcePoints = detected.map { PointF(it.x() * bitmap.width, it.y() * bitmap.height) }
        sourceBounds = contourBounds(sourcePoints)
        prepareMesh()
        landmarker = createLandmarker(RunningMode.VIDEO)
        lastTimestampMs = SystemClock.uptimeMillis()
    }

    /** Returns a transparent full-frame overlay; the camera itself is never redrawn here. */
    @Synchronized
    fun processFrame(frame: Bitmap): Bitmap? {
        val activeLandmarker = landmarker ?: return null
        val source = sourceBitmap ?: return null
        val sourceFaceBounds = sourceBounds ?: return null
        if (sourcePoints.isEmpty() || frame.width <= 0 || frame.height <= 0) return null

        val timestamp = max(SystemClock.uptimeMillis(), lastTimestampMs + 1L)
        lastTimestampMs = timestamp
        val result = activeLandmarker.detectForVideo(BitmapImageBuilder(frame).build(), timestamp)
        val detected = result.faceLandmarks().firstOrNull()

        if (detected == null) {
            if (lastFaceSeenMs != 0L && SystemClock.uptimeMillis() - lastFaceSeenMs > FACE_RESET_MS) {
                neutralDriverPoints = null
                neutralDriverBounds = null
                smoothedDriverPoints = null
            }
            return transparentFrame(frame)
        }
        lastFaceSeenMs = SystemClock.uptimeMillis()

        onNormalizedLandmarks?.let { callback ->
            val normalizedXyz = FloatArray(detected.size * 3)
            for (i in detected.indices) {
                val offset = i * 3
                normalizedXyz[offset] = detected[i].x()
                normalizedXyz[offset + 1] = detected[i].y()
                normalizedXyz[offset + 2] = detected[i].z()
            }
            callback(normalizedXyz)
        }

        val rawCurrent = detected.map { PointF(it.x() * frame.width, it.y() * frame.height) }
        if (neutralDriverPoints == null || neutralDriverBounds == null) {
            neutralDriverPoints = rawCurrent.map { PointF(it.x, it.y) }
            neutralDriverBounds = contourBounds(rawCurrent)
            smoothedDriverPoints = rawCurrent.map { PointF(it.x, it.y) }.toMutableList()
            return transparentFrame(frame)
        }

        val current = smoothPoints(rawCurrent)
        val neutral = neutralDriverPoints ?: return transparentFrame(frame)
        val neutralBounds = neutralDriverBounds ?: return transparentFrame(frame)
        val count = min(min(sourcePoints.size, neutral.size), current.size)
        if (count < 100) return transparentFrame(frame)

        val scaleX = sourceFaceBounds.width() / neutralBounds.width().coerceAtLeast(1f)
        val scaleY = sourceFaceBounds.height() / neutralBounds.height().coerceAtLeast(1f)
        val maxDx = sourceFaceBounds.width() * MAX_DISPLACEMENT_RATIO
        val maxDy = sourceFaceBounds.height() * MAX_DISPLACEMENT_RATIO
        val deltasX = FloatArray(sourcePoints.size)
        val deltasY = FloatArray(sourcePoints.size)

        for (i in 0 until count) {
            deltasX[i] = FaceOverlayGeometry.clampDisplacement(
                (current[i].x - neutral[i].x) * scaleX,
                sourceFaceBounds.width()
            ).coerceIn(-maxDx, maxDx)
            deltasY[i] = FaceOverlayGeometry.clampDisplacement(
                (current[i].y - neutral[i].y) * scaleY,
                sourceFaceBounds.height()
            ).coerceIn(-maxDy, maxDy)
        }

        for (vertexIndex in influences.indices) {
            val influence = influences[vertexIndex]
            var dx = 0f
            var dy = 0f
            for (j in influence.ids.indices) {
                val id = influence.ids[j]
                if (id < count) {
                    dx += deltasX[id] * influence.weights[j]
                    dy += deltasY[id] * influence.weights[j]
                }
            }
            val offset = vertexIndex * 2
            meshWarped[offset] = meshBase[offset] + dx * influence.fade
            meshWarped[offset + 1] = meshBase[offset + 1] + dy * influence.fade
        }

        val warped = warpedSource ?: return transparentFrame(frame)
        val canvasWarp = Canvas(warped)
        canvasWarp.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvasWarp.drawBitmapMesh(source, MESH_WIDTH, MESH_HEIGHT, meshWarped, 0, null, 0, paint)

        val output = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        // Use the real MediaPipe face contour, not an oval/circle. This removes
        // the obvious circular cut-out and keeps the edge around the jaw/forehead.
        val currentFacePath = contourPath(current)
        val currentFaceRect = FaceOverlayGeometry.paddedFaceRect(
            contourBounds(current), frame.width, frame.height
        )
        val sourceFaceRect = FaceOverlayGeometry.paddedFaceRect(
            sourceFaceBounds, source.width, source.height
        )
        val sourceRect = Rect().also { sourceFaceRect.roundOut(it) }

        canvas.save()
        canvas.clipPath(currentFacePath)
        canvas.drawBitmap(warped, sourceRect, currentFaceRect, paint)
        canvas.restore()
        return output
    }

    private fun transparentFrame(frame: Bitmap): Bitmap {
        return Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        }
    }

    private fun smoothPoints(raw: List<PointF>): List<PointF> {
        val state = smoothedDriverPoints
        if (state == null || state.size != raw.size) {
            smoothedDriverPoints = raw.map { PointF(it.x, it.y) }.toMutableList()
            return raw
        }
        // Higher alpha keeps mouth/eye/head movement responsive without the old shake.
        val alpha = 0.40f
        for (i in raw.indices) {
            state[i].x += (raw[i].x - state[i].x) * alpha
            state[i].y += (raw[i].y - state[i].y) * alpha
        }
        return state
    }

    private fun prepareMesh() {
        val bitmap = sourceBitmap ?: return
        val bounds = sourceBounds ?: return
        val vertexColumns = MESH_WIDTH + 1
        val vertexRows = MESH_HEIGHT + 1
        val vertexCount = vertexColumns * vertexRows
        meshBase = FloatArray(vertexCount * 2)
        meshWarped = FloatArray(vertexCount * 2)
        influences = Array(vertexCount) { index ->
            val row = index / vertexColumns
            val column = index % vertexColumns
            val x = bitmap.width.toFloat() * column / MESH_WIDTH
            val y = bitmap.height.toFloat() * row / MESH_HEIGHT
            val offset = index * 2
            meshBase[offset] = x
            meshBase[offset + 1] = y
            meshWarped[offset] = x
            meshWarped[offset + 1] = y

            val nearest = sourcePoints.indices.map { pointIndex ->
                val point = sourcePoints[pointIndex]
                val dx = point.x - x
                val dy = point.y - y
                Triple(pointIndex, dx * dx + dy * dy, 0f)
            }.sortedBy { it.second }.take(NEIGHBOR_COUNT)
            val total = nearest.sumOf { 1.0 / (sqrt(it.second.toDouble()) + DISTANCE_EPSILON) }
                .coerceAtLeast(1e-9)
            val ids = IntArray(nearest.size)
            val weights = FloatArray(nearest.size)
            for (j in nearest.indices) {
                ids[j] = nearest[j].first
                weights[j] = ((1.0 / (sqrt(nearest[j].second.toDouble()) + DISTANCE_EPSILON)) / total).toFloat()
            }
            VertexInfluence(ids, weights, edgeFade(x, y, bounds))
        }
    }

    private fun edgeFade(x: Float, y: Float, bounds: RectF): Float {
        val marginX = (bounds.width() * EDGE_FADE_RATIO).coerceAtLeast(1f)
        val marginY = (bounds.height() * EDGE_FADE_RATIO).coerceAtLeast(1f)
        val fx = min((x - bounds.left) / marginX, (bounds.right - x) / marginX).coerceIn(0f, 1f)
        val fy = min((y - bounds.top) / marginY, (bounds.bottom - y) / marginY).coerceIn(0f, 1f)
        return min(fx, fy)
    }

    private fun contourBounds(points: List<PointF>): RectF {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (index in FACE_CONTOUR) {
            if (index >= points.size) continue
            val point = points[index]
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
        }
        return RectF(left, top, right, bottom)
    }

    private fun contourPath(points: List<PointF>): Path {
        val path = Path()
        var first = true
        for (index in FACE_CONTOUR) {
            if (index >= points.size) continue
            val p = points[index]
            if (first) {
                path.moveTo(p.x, p.y)
                first = false
            } else {
                path.lineTo(p.x, p.y)
            }
        }
        path.close()
        return path
    }

    private fun createLandmarker(mode: RunningMode): FaceLandmarker {
        fun create(delegate: Delegate): FaceLandmarker {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(mode)
                .setNumFaces(1)
                .build()
            return FaceLandmarker.createFromOptions(appContext, options)
        }
        return try {
            val result = create(Delegate.GPU)
            gpuActive = true
            result
        } catch (_: Throwable) {
            gpuActive = false
            create(Delegate.CPU)
        }
    }

    private fun closeLandmarker() {
        landmarker?.close()
        landmarker = null
    }

    @Synchronized
    override fun close() {
        closeLandmarker()
        warpedSource?.recycle()
        warpedSource = null
        neutralDriverPoints = null
        neutralDriverBounds = null
        smoothedDriverPoints = null
        onNormalizedLandmarks = null
        sourcePoints = emptyList()
        meshBase = FloatArray(0)
        meshWarped = FloatArray(0)
        influences = emptyArray()
    }

    private data class VertexInfluence(val ids: IntArray, val weights: FloatArray, val fade: Float)

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val MESH_WIDTH = 16
        private const val MESH_HEIGHT = 16
        private const val NEIGHBOR_COUNT = 4
        private const val DISTANCE_EPSILON = 4.0
        private const val EDGE_FADE_RATIO = 0.08f
        private const val FACE_RESET_MS = 600L
        private const val MAX_DISPLACEMENT_RATIO = 0.18f
        private val FACE_CONTOUR = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}
