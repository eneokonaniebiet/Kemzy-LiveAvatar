package com.kemzy.liveavatar.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.kemzy.liveavatar.camera.DriverMotion
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CloudRenderClient : AutoCloseable {
    companion object { const val API_URL = "https://kemzy-liveavatar.seravellenyravalen.workers.dev" }
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var latest: Bitmap? = null
    @Volatile private var lastError: String? = null

    fun latestFrame(): Bitmap? = latest
    fun error(): String? = lastError

    fun prepareSource(bitmap: Bitmap, onReady: (Boolean, String) -> Unit) {
        stop()
        lastError = null
        val media = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val create = Request.Builder().url("$API_URL/v1/sessions")
            .post("{}".toRequestBody("application/json".toMediaType())).build()

        http.newCall(create).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = fail(onReady, "Cloud session failed: " + (e.message ?: "unknown"))
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return fail(onReady, "Cloud session HTTP " + it.code)
                    val id = runCatching { JSONObject(it.body?.string().orEmpty()).getString("session_id") }.getOrElse { e ->
                        return fail(onReady, "Invalid cloud session response: " + (e.message ?: "unknown"))
                    }
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", "source.jpg", media.toRequestBody("image/jpeg".toMediaType())).build()
                    val upload = Request.Builder().url("$API_URL/v1/sessions/$id/source").post(body).build()
                    http.newCall(upload).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) = fail(onReady, "Cloud source upload failed: " + (e.message ?: "unknown"))
                        override fun onResponse(call: Call, response: Response) {
                            response.use { if (!it.isSuccessful) fail(onReady, "Cloud source HTTP " + it.code) else openSocket(id, onReady) }
                        }
                    })
                }
            }
        })
    }

    private fun openSocket(id: String, onReady: (Boolean, String) -> Unit) {
        socket = http.newWebSocket(Request.Builder().url("$API_URL/v1/stream/$id").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastError = null
                onReady(true, "Cloud neural renderer connected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "frame" -> {
                            val b = Base64.decode(json.optString("frame_base64"), Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: error("invalid image frame")
                            val old = latest
                            latest = bmp
                            old?.recycle()
                        }
                        "error" -> lastError = json.optString("message", "Cloud renderer error")
                    }
                }.onFailure { lastError = it.message }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                lastError = "Cloud renderer disconnected: " + (t.message ?: "unknown")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    }

    // The Render broker's public protocol is JSON. Each motion message carries
    // the compressed camera frame in driving_images for the outbound GPU worker.
    fun sendMotion(motion: DriverMotion, timestampMs: Long, jpeg: ByteArray? = null) {
        val s = socket ?: return
        if (jpeg == null || jpeg.isEmpty()) return
        if (s.queueSize() > 2_000_000L) return

        val json = JSONObject().apply {
            put("type", "driver")
            put("timestamp_ms", timestampMs)
            put("yaw", motion.yaw)
            put("pitch", motion.pitch)
            put("roll", motion.roll)
            put("eye_left", motion.eyeLeft.coerceIn(0f, 1f))
            put("eye_right", motion.eyeRight.coerceIn(0f, 1f))
            put("mouth_open", motion.mouthOpen.coerceIn(0f, 1f))
            put("smile", motion.smile.coerceIn(0f, 1f))
            put("brow_left", motion.browLeft.coerceIn(0f, 1f))
            put("brow_right", motion.browRight.coerceIn(0f, 1f))
            put("driving_images", org.json.JSONArray().put(Base64.encodeToString(jpeg, Base64.NO_WRAP)))
        }
        s.send(json.toString())
    }

    fun stop() {
        socket?.close(1000, "stop")
        socket = null
        latest?.recycle()
        latest = null
    }

    private fun fail(cb: (Boolean, String) -> Unit, msg: String) {
        lastError = msg
        cb(false, msg)
    }

    override fun close() {
        stop()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
