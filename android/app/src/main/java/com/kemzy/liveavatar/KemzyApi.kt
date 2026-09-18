package com.kemzy.liveavatar

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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

    fun createSession(sourceType: String): String {
        val body = """{"source_type":"$sourceType"}"""
            .toRequestBodyCompat("application/json")
        val request = Request.Builder().url("${root()}/v1/sessions").post(body).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "session failed: ${response.code}" }
            return JSONObjectCompat(response.body!!.string()).getString("session_id")
        }
    }

    fun uploadImageSource(sessionId: String, bitmap: Bitmap): Boolean {
        val temp = File.createTempFile("kemzy-source-", ".jpg")
        FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return try { uploadFile(sessionId, temp, "image/jpeg") } finally { temp.delete() }
    }

    private fun uploadFile(sessionId: String, file: File, mime: String): Boolean {
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBodyCompat(mime)
        )
        val request = Request.Builder()
            .url("${root()}/v1/sessions/$sessionId/source")
            .post(MultipartBody.Builder().setType(MultipartBody.FORM).addPart(part).build())
            .build()
        client.newCall(request).execute().use { return it.isSuccessful }
    }

    fun openStream(sessionId: String, listener: StreamListener): WebSocket {
        val wsRoot = root().replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val request = Request.Builder().url("$wsRoot/v1/stream/$sessionId").build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(webSocket)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
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
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
        return webSocket.send(output.toByteArray().toByteString())
    }

    interface StreamListener {
        fun onOpen(webSocket: WebSocket)
        fun onFrame(bitmap: Bitmap)
        fun onError(error: Throwable)
        fun onClosed()
    }
}

// Small compatibility helpers keep this file independent of Android JSON implementation details.
private fun String.toRequestBodyCompat(mime: String) =
    okhttp3.RequestBody.create(mime.toMediaType(), toByteArray(Charsets.UTF_8))

private fun File.asRequestBodyCompat(mime: String) =
    okhttp3.RequestBody.create(mime.toMediaType(), this)

private fun ByteArray.toByteString(): ByteString = ByteString.of(*this)

private class JSONObjectCompat(private val raw: String) {
    fun getString(key: String): String {
        val marker = """"$key":""""
        val start = raw.indexOf(marker)
        require(start >= 0) { "Missing $key in response" }
        val valueStart = raw.indexOf('"', start + marker.length)
        val valueEnd = raw.indexOf('"', valueStart + 1)
        require(valueStart >= 0 && valueEnd > valueStart) { "Invalid response" }
        return raw.substring(valueStart + 1, valueEnd)
    }
}
