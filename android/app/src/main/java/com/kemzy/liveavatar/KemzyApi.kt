package com.kemzy.liveavatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class KemzyApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun root(): String = baseUrl.trimEnd('/')

    fun createSession(): String {
        val body = JSONObject().put("source_type", "image").toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${root()}/v1/sessions").post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "session failed: ${response.code}" }
            return JSONObject(response.body!!.string()).getString("session_id")
        }
    }

    fun uploadSource(sessionId: String, bitmap: Bitmap): Boolean {
        val temp = File.createTempFile("kemzy-source-", ".jpg")
        FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return try {
            val part = MultipartBody.Part.createFormData(
                "file", temp.name, temp.asRequestBody("image/jpeg".toMediaType())
            )
            val request = Request.Builder()
                .url("${root()}/v1/sessions/$sessionId/source")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM).addPart(part).build())
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } finally { temp.delete() }
    }

    fun openStream(sessionId: String, listener: StreamListener): WebSocket {
        val wsRoot = root().replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val request = Request.Builder().url("$wsRoot/v1/stream/$sessionId").build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket)
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "frame" -> {
                            val encoded = json.optString("frame_base64")
                            if (encoded.isBlank()) return
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: throw IllegalArgumentException("Renderer returned invalid image bytes")
                            listener.onFrame(bitmap)
                        }
                        "error" -> listener.onError(
                            IllegalStateException("${json.optString("code", "renderer_error")}: ${json.optString("message", "unknown renderer error")}")
                        )
                    }
                } catch (t: Throwable) { listener.onError(t) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onError(t)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
        })
    }

    fun send(webSocket: WebSocket, timestampMs: Long, packet: MotionPacket): Boolean {
        fun array(values: List<Float>) = JSONArray().apply { values.forEach { put(it.toDouble()) } }
        val payload = JSONObject()
            .put("timestamp_ms", timestampMs)
            .put("pose", array(packet.pose))
            .put("expression", array(packet.expression))
            .put("eye_ratio", packet.eyeRatio.toDouble())
            .put("lip_ratio", packet.lipRatio.toDouble())
            .put("landmarks", JSONArray())
        return webSocket.send(payload.toString())
    }

    interface StreamListener {
        fun onOpen(webSocket: WebSocket)
        fun onFrame(bitmap: Bitmap)
        fun onError(error: Throwable)
        fun onClosed()
    }
}
