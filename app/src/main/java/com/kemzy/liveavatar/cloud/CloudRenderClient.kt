package com.kemzy.liveavatar.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    @Volatile private var connected = false

    fun latestFrame(): Bitmap? = latest
    fun error(): String? = lastError

    fun prepareSource(bitmap: Bitmap, onReady: (Boolean, String) -> Unit) {
        stop()
        lastError = null
        val media = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val create = Request.Builder().url("$API_URL/v1/sessions").post("{}".toRequestBody("application/json".toMediaType())).build()
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
                connected = true
                lastError = null
                onReady(true, "Cloud neural renderer connected")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching {
                    val data = bytes.toByteArray()
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("invalid image frame")
                    val old = latest
                    latest = bmp
                    old?.recycle()
                }.onFailure { lastError = "Invalid cloud frame: " + (it.message ?: "unknown") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    if (json.optString("status") == "error" || json.optString("type") == "error") {
                        lastError = json.optString("message", "Cloud renderer error")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                socket = null
                lastError = "Cloud renderer disconnected: " + (t.message ?: "unknown")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                webSocket.close(1000, null)
            }
        })
    }

    fun sendDrivingFrame(jpeg: ByteArray) {
        val s = socket ?: return
        if (!connected || jpeg.isEmpty() || s.queueSize() > 1_500_000L) return
        s.send(ByteString.of(*jpeg))
    }

    fun stop() {
        connected = false
        socket?.close(1000, "stop")
        socket = null
        latest?.recycle()
        latest = null
    }

    private fun fail(cb: (Boolean, String) -> Unit, msg: String) {
        lastError = msg
        connected = false
        cb(false, msg)
    }

    override fun close() {
        stop()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
