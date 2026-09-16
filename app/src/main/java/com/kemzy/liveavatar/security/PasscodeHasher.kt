package com.kemzy.liveavatar.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class PasswordRecord(val salt: ByteArray, val digest: ByteArray, val iterations: Int)

internal object PasscodeHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun create(passcode: CharArray, random: SecureRandom = SecureRandom()): PasswordRecord {
        require(passcode.size >= 4) { "Passcode must contain at least 4 characters" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PasswordRecord(salt, derive(passcode, salt, ITERATIONS), ITERATIONS)
    }

    fun verify(passcode: CharArray, record: PasswordRecord): Boolean =
        MessageDigest.isEqual(record.digest, derive(passcode, record.salt, record.iterations))

    private fun derive(passcode: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passcode, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
