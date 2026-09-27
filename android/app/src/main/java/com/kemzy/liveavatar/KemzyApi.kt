package com.kemzy.liveavatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class MotionPacket(
    val pose: List<Float>,
    val expression: List<Float>
)

class KemzyApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun createSession(): String {
        val body = JSONObject().put("source_type", "image").toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/v1/sessions").post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GPU session failed: HTTP " + response.code }
            return JSONObject(response.body!!.string()).getString("session_id")
        }
    }

    fun uploadSource(sessionId: String, bitmap: Bitmap): Boolean {
        val temp = File.createTempFile("kemzy-source-", ".jpg")
        return try {
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            val part = MultipartBody.Part.createFormData(
                "file", temp.name, temp.asRequestBody("image/jpeg".toMediaType())
            )
            val request = Request.Builder()
                .url("$baseUrl/v1/sessions/$sessionId/source")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM).addPart(part).build())
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } finally {
            temp.delete()
        }
    }

    fun openLiveStream(
        sessionId: String,
        onFrame: (Bitmap) -> Unit,
        onError: (String) -> Unit,
        onOpen: () -> Unit,
    ): WebSocket {
        val wsBase = if (baseUrl.startsWith("https://")) {
            "wss://" + baseUrl.removePrefix("https://")
        } else {
            "ws://" + baseUrl.removePrefix("http://")
        }
        val request = Request.Builder().url("$wsBase/v1/stream/$sessionId").build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { onOpen() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onError(t.message ?: "GPU stream connection failed")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val payload = JSONObject(text)
                    if (payload.optString("type") != "frame") {
                        onError(payload.optString("message", "Renderer stream error"))
                        return
                    }
                    val encoded = payload.optString("frame_base64")
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(onFrame)
                        ?: onError("Invalid rendered frame")
                } catch (t: Throwable) {
                    onError(t.message ?: "Invalid renderer response")
                }
            }
        })
    }

    fun sendMotion(
        webSocket: WebSocket,
        timestampMs: Long,
        packet: MotionPacket,
        jpegBytes: ByteArray,
    ): Boolean {
        val expression = JSONArray()
        packet.expression.forEach { expression.put(it) }
        val pose = JSONArray()
        packet.pose.forEach { pose.put(it) }
        val images = JSONArray()
        images.put(Base64.encodeToString(jpegBytes, Base64.NO_WRAP))

        val json = JSONObject()
            .put("type", "driver")
            .put("timestamp_ms", timestampMs)
            .put("pose", pose)
            .put("expression", expression)
            .put("landmarks", JSONArray())
            .put("driving_images", images)

        return webSocket.send(json.toString())
    }

    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 70): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
