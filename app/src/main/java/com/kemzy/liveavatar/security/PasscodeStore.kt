package com.kemzy.liveavatar.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

class PasscodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("kemzy_lock", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = prefs.contains(KEY_SALT) && prefs.contains(KEY_DIGEST)

    fun setPasscode(passcode: CharArray) {
        val record = PasscodeHasher.create(passcode, SecureRandom())
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(record.salt, Base64.NO_WRAP))
            .putString(KEY_DIGEST, Base64.encodeToString(record.digest, Base64.NO_WRAP))
            .putInt(KEY_ITERATIONS, record.iterations)
            .apply()
    }

    fun verify(passcode: CharArray): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val digest = prefs.getString(KEY_DIGEST, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val iterations = prefs.getInt(KEY_ITERATIONS, 120_000)
        return PasscodeHasher.verify(passcode, PasswordRecord(salt, digest, iterations))
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_SALT = "salt"
        const val KEY_DIGEST = "digest"
        const val KEY_ITERATIONS = "iterations"
    }
}
