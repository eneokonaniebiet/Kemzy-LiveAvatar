package com.kemzy.liveavatar

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    fun render(sessionId: String, timestampMs: Long, packet: MotionPacket): Bitmap? {
        val json = JSONObject()
            .put("session_id", sessionId)
            .put("timestamp_ms", timestampMs)
            .put("pose", packet.pose)
            .put("expression", packet.expression)
            .put("landmarks", emptyList<Float>())
        val request = Request.Builder()
            .url("$baseUrl/v1/render/frame")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val payload = JSONObject(response.body!!.string())
            val encoded = payload.optString("image_base64", "")
            if (encoded.isBlank()) return null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
