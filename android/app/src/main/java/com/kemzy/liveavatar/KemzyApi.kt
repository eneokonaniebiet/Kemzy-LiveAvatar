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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class KemzyApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    fun createSession(): String {
        val body = JSONObject().put("source_type", "image").toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/v1/sessions").post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "session failed: ${response.code}" }
            return JSONObject(response.body!!.string()).getString("session_id")
        }
    }

    fun uploadSource(sessionId: String, bitmap: Bitmap): Boolean {
        val temp = File.createTempFile("kemzy-source-", ".jpg")
        FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val part = MultipartBody.Part.createFormData("file", temp.name, temp.asRequestBody("image/jpeg".toMediaType()))
        val request = Request.Builder()
            .url("$baseUrl/v1/sessions/$sessionId/source")
            .post(MultipartBody.Builder().setType(MultipartBody.FORM).addPart(part).build())
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } finally {
            temp.delete()
        }
    }

    fun openLiveStream(
        sessionId: String,
        onFrame: (Bitmap) -> Unit,
        onError: (String) -> Unit,
    ): WebSocket {
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://${baseUrl.removePrefix("https://")}" 
            baseUrl.startsWith("http://") -> "ws://${baseUrl.removePrefix("http://")}" 
            else -> baseUrl
        }.trimEnd('/')
        val request = Request.Builder().url("$wsBase/v1/stream/$sessionId").build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t.message ?: "WebSocket connection failed")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val payload = JSONObject(text)
                    if (payload.optString("type") != "frame") {
                        onError(payload.optString("detail", "Renderer stream error"))
                        return
                    }
                    val encoded = payload.optString("frame_base64")
                    if (encoded.isBlank()) {
                        onError("Renderer returned an empty frame")
                        return
                    }
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) onFrame(bitmap) else onError("Invalid rendered frame")
                } catch (t: Throwable) {
                    onError(t.message ?: "Invalid renderer response")
                }
            }
        })
    }

    fun sendMotion(webSocket: WebSocket, timestampMs: Long, packet: MotionPacket): Boolean {
        val json = JSONObject()
            .put("timestamp_ms", timestampMs)
            .put("pose", packet.pose)
            .put("expression", packet.expression)
            .put("landmarks", emptyList<Float>())
        return webSocket.send(json.toString())
    }
}
