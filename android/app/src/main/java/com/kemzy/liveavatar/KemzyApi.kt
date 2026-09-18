package com.kemzy.liveavatar

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class KemzyApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
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

    fun uploadImageSource(sessionId: String, bitmap: Bitmap): Boolean {
        val temp = File.createTempFile("kemzy-source-", ".jpg")
        FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return try {
            val part = MultipartBody.Part.createFormData(
                "file", temp.name, temp.asRequestBody("image/jpeg".toMediaType())
            )
            val request = Request.Builder()
                .url("${root()}/v1/sessions/$sessionId/source")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM).addPart(part).build())
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } finally {
            temp.delete()
        }
    }

    fun openStream(sessionId: String, listener: StreamListener): WebSocket {
        val wsRoot = root().replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val request = Request.Builder().url("$wsRoot/v1/stream/$sessionId").build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) listener.onFrame(bitmap)
                else listener.onError(IllegalStateException("PersonaLive returned invalid JPEG frame"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onError(IllegalStateException("PersonaLive control message: $text"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onError(t)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
        })
    }

    fun sendCameraFrame(webSocket: WebSocket, bitmap: Bitmap): Boolean {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
        return webSocket.send(ByteString.of(*output.toByteArray()))
    }

    interface StreamListener {
        fun onOpen(webSocket: WebSocket)
        fun onFrame(bitmap: Bitmap)
        fun onError(error: Throwable)
        fun onClosed()
    }
}
