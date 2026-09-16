package com.kemzy.liveavatar.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class PasscodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("kemzy_lock", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = true

    fun setPasscode(passcode: CharArray) = Unit

    fun verify(passcode: CharArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(String(passcode).toByteArray(Charsets.UTF_8))
        val expected = FIXED_PASSCODE_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.isEqual(digest, expected)
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val FIXED_PASSCODE_SHA256 = "8f41dfc651a83144725a6708a26af74109d5f88cfbf14543c05f390cf58cbf07"
    }
}
