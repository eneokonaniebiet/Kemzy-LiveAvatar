package com.kemzy.liveavatar.cloud

import android.graphics.Bitmap
import android.util.Base64
import com.kemzy.liveavatar.camera.DriverMotion
import okhttp3.*
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
        lastError = null
        val media = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val create = Request.Builder().url("$API_URL/v1/sessions").post("""{"source_type":"image"}""".toRequestBody("application/json".toMediaType())).build()
        http.newCall(create).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { fail(onReady, "Cloud session failed: " + (e.message ?: "unknown")) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return fail(onReady, "Cloud session HTTP " + it.code)
                    val id = JSONObject(it.body?.string().orEmpty()).getString("session_id")
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "source.jpg", media.toRequestBody("image/jpeg".toMediaType())).build()
                    val upload = Request.Builder().url("$API_URL/v1/sessions/$id/source").post(body).build()
                    http.newCall(upload).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) { fail(onReady, "Cloud source upload failed: " + (e.message ?: "unknown")) }
                        override fun onResponse(call: Call, response: Response) { response.use { if (!it.isSuccessful) fail(onReady, "Cloud source HTTP " + it.code) else openSocket(id, onReady) } }
                    })
                }
            }
        })
    }
    private fun openSocket(id: String, onReady: (Boolean, String) -> Unit) {
        socket = http.newWebSocket(Request.Builder().url("$API_URL/v1/stream/$id").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { lastError = null; onReady(true, "Cloud neural renderer connected") }
            override fun onMessage(webSocket: WebSocket, text: String) { runCatching { val json=JSONObject(text); if(json.optString("type")=="frame"){ val b=Base64.decode(json.optString("frame_base64"),Base64.DEFAULT); val bmp=android.graphics.BitmapFactory.decodeByteArray(b,0,b.size); if(bmp!=null){ val old=latest; latest=bmp; old?.recycle() } } else if(json.optString("type")=="error") lastError=json.optString("message","Cloud renderer error") }.onFailure { lastError=it.message } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lastError = "Cloud renderer disconnected: " + (t.message ?: "unknown"); socket=null }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000,null) }
        })
    }
    fun sendMotion(motion: DriverMotion, timestampMs: Long) {
        val s = socket ?: return
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
        }
        s.send(json.toString())
    }

    fun stop(){ socket?.close(1000,"stop"); socket=null; latest?.recycle(); latest=null }
    private fun fail(cb:(Boolean,String)->Unit,msg:String){lastError=msg;cb(false,msg)}
    override fun close(){stop();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
}