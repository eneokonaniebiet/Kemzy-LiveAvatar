package com.kemzy.liveavatar

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class KemzyApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun root(): String = baseUrl.trimEnd('/')

    fun createSession(sourceType: String = "image"): String {
        val body = JSONObject().put("source_type", sourceType).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${root()}/v1/sessions").post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "session failed: ${response.code}" }
            return JSONObject(response.body!!.string()).getString("session_id")
        }
    }

    fun uploadSource(sessionId: String, contentResolver: ContentResolver, uri: Uri, contentType: String, filename: String): Boolean {
        val mediaType = contentType.toMediaType()
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = -1L
            override fun writeTo(sink: okio.BufferedSink) {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open selected source" }
                    input.copyTo(sink.outputStream())
                }
            }
        }
        val part = MultipartBody.Part.createFormData("file", filename, body)
        val request = Request.Builder()
            .url("${root()}/v1/sessions/$sessionId/source")
            .post(MultipartBody.Builder().setType(MultipartBody.FORM).addPart(part).build())
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "source upload failed: ${response.code} ${response.body?.string().orEmpty()}" }
            return true
        }
    }

    @Deprecated("Use uploadSource so image/video bytes reach the cloud renderer unchanged")
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
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "source upload failed: ${response.code} ${response.body?.string().orEmpty()}" }
                true
            }
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

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "frame" -> {
                            val encoded = message.optString("frame_base64")
                            if (encoded.isBlank()) throw IllegalStateException("Renderer returned an empty frame")
                            val data = Base64.decode(encoded, Base64.DEFAULT)
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                            if (bitmap != null) listener.onFrame(bitmap)
                            else throw IllegalStateException("Renderer returned an invalid image frame")
                        }
                        "error" -> listener.onError(
                            IllegalStateException(message.optString("message", "Renderer error"))
                        )
                        else -> listener.onError(
                            IllegalStateException("Renderer control message: $text")
                        )
                    }
                } catch (t: Throwable) {
                    listener.onError(t)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onError(t)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed()
        })
    }

    fun sendDriverFrame(
        webSocket: WebSocket,
        timestampMs: Long,
        yaw: Float,
        pitch: Float,
        roll: Float,
        eyeLeft: Float,
        eyeRight: Float,
        mouthOpen: Float,
        smile: Float,
        browLeft: Float = 0f,
        browRight: Float = 0f,
    ): Boolean {
        val json = JSONObject()
            .put("type", "driver")
            .put("timestamp_ms", timestampMs)
            .put("yaw", yaw)
            .put("pitch", pitch)
            .put("roll", roll)
            .put("eye_left", eyeLeft.coerceIn(0f, 1f))
            .put("eye_right", eyeRight.coerceIn(0f, 1f))
            .put("mouth_open", mouthOpen.coerceIn(0f, 1f))
            .put("smile", smile.coerceIn(0f, 1f))
            .put("brow_left", browLeft.coerceIn(0f, 1f))
            .put("brow_right", browRight.coerceIn(0f, 1f))
        return webSocket.send(json.toString())
    }

    interface StreamListener {
        fun onOpen(webSocket: WebSocket)
        fun onFrame(bitmap: Bitmap)
        fun onError(error: Throwable)
        fun onClosed()
    }
}
