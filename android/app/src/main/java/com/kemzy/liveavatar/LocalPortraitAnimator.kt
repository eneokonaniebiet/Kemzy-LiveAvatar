package com.kemzy.liveavatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight, local-only portrait deformation prototype.
 *
 * This is a geometric warp driven by local facial landmarks, not a generative
 * model. It can move visible facial features, but it cannot invent hidden side
 * views or guarantee photorealistic animation.
 */
class LocalPortraitAnimator(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var landmarker: FaceLandmarker? = null
    private var sourceBitmap: Bitmap? = null
    private var outputBitmap: Bitmap? = null
    private var sourcePoints: List<PointF> = emptyList()
    private var sourceBounds: RectF? = null
    private var neutralDriverPoints: List<PointF>? = null
    private var neutralDriverBounds: RectF? = null
    private var meshBase = FloatArray(0)
    private var meshWarped = FloatArray(0)
    private var influences: Array<VertexInfluence> = emptyArray()
    private var lastTimestampMs = 0L

    @Synchronized
    fun isReady(): Boolean =
        sourceBitmap != null && sourcePoints.isNotEmpty() &&
            landmarker != null && meshBase.isNotEmpty()

    /**
     * Detect the chosen portrait locally, then switch the one model instance
     * to VIDEO mode for camera frames. The temporary IMAGE instance is closed
     * before the streaming instance is created to reduce peak memory.
     */
    @Synchronized
    fun setSource(bitmap: Bitmap) {
        closeLandmarker()
        neutralDriverPoints = null
        neutralDriverBounds = null
        sourceBitmap = bitmap
        outputBitmap?.recycle()
        outputBitmap = Bitmap.createBitmap(
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
        sourceBounds = boundsOf(sourcePoints)
        prepareMesh()
        landmarker = createLandmarker(RunningMode.VIDEO)
        lastTimestampMs = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun processFrame(frame: Bitmap): Bitmap? {
        val activeLandmarker = landmarker ?: return null
        val source = sourceBitmap ?: return null
        val bounds = sourceBounds ?: return null
        if (sourcePoints.isEmpty() || frame.width <= 0 || frame.height <= 0) return null

        val timestamp = max(SystemClock.uptimeMillis(), lastTimestampMs + 1L)
        lastTimestampMs = timestamp
        val result = activeLandmarker.detectForVideo(
            BitmapImageBuilder(frame).build(),
            timestamp
        )
        val detected = result.faceLandmarks().firstOrNull() ?: return source
        val current = detected.map { PointF(it.x() * frame.width, it.y() * frame.height) }
        val currentBounds = boundsOf(current)

        if (neutralDriverPoints == null || neutralDriverBounds == null) {
            neutralDriverPoints = current.map { PointF(it.x, it.y) }
            neutralDriverBounds = currentBounds
            return source
        }

        val neutral = neutralDriverPoints ?: return source
        val neutralBounds = neutralDriverBounds ?: return source
        val count = min(min(sourcePoints.size, neutral.size), current.size)
        if (count < 100) return source

        val scaleX = bounds.width().coerceAtLeast(1f) / neutralBounds.width().coerceAtLeast(1f)
        val scaleY = bounds.height().coerceAtLeast(1f) / neutralBounds.height().coerceAtLeast(1f)
        val maxDx = bounds.width() * 0.18f
        val maxDy = bounds.height() * 0.18f
        val deltasX = FloatArray(sourcePoints.size)
        val deltasY = FloatArray(sourcePoints.size)

        for (i in 0 until count) {
            deltasX[i] = ((current[i].x - neutral[i].x) * scaleX).coerceIn(-maxDx, maxDx)
            deltasY[i] = ((current[i].y - neutral[i].y) * scaleY).coerceIn(-maxDy, maxDy)
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

        val output = outputBitmap ?: return source
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawBitmapMesh(
            source,
            MESH_WIDTH,
            MESH_HEIGHT,
            meshWarped,
            0,
            null,
            0,
            paint
        )
        return output
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

            val nearest = sourcePoints.indices
                .map { pointIndex ->
                    val point = sourcePoints[pointIndex]
                    val dx = point.x - x
                    val dy = point.y - y
                    Triple(pointIndex, dx * dx + dy * dy, 0f)
                }
                .sortedBy { it.second }
                .take(NEIGHBOR_COUNT)
            val total = nearest.sumOf { 1.0 / (sqrt(it.second.toDouble()) + DISTANCE_EPSILON) }
                .coerceAtLeast(1e-9)
            val ids = IntArray(nearest.size)
            val weights = FloatArray(nearest.size)
            for (j in nearest.indices) {
                ids[j] = nearest[j].first
                weights[j] = (
                    (1.0 / (sqrt(nearest[j].second.toDouble()) + DISTANCE_EPSILON)) / total
                ).toFloat()
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

    private fun boundsOf(points: List<PointF>): RectF {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (point in points) {
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
        }
        return RectF(left, top, right, bottom)
    }

    private fun createLandmarker(mode: RunningMode): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(mode)
            .setNumFaces(1)
            .build()
        return FaceLandmarker.createFromOptions(appContext, options)
    }

    private fun closeLandmarker() {
        landmarker?.close()
        landmarker = null
    }

    @Synchronized
    override fun close() {
        closeLandmarker()
        outputBitmap?.recycle()
        outputBitmap = null
        neutralDriverPoints = null
        neutralDriverBounds = null
    }

    private data class VertexInfluence(
        val ids: IntArray,
        val weights: FloatArray,
        val fade: Float
    )

    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val MESH_WIDTH = 32
        private const val MESH_HEIGHT = 32
        private const val NEIGHBOR_COUNT = 6
        private const val DISTANCE_EPSILON = 4.0
        private const val EDGE_FADE_RATIO = 0.08f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}
