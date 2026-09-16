package com.kemzy.liveavatar.security

import org.junit.Assert.*
import org.junit.Test

class PasscodeHasherTest {
    @Test fun correctPasscodeVerifies() {
        val record = PasscodeHasher.create("1234".toCharArray())
        assertTrue(PasscodeHasher.verify("1234".toCharArray(), record))
    }

    @Test fun wrongPasscodeFails() {
        val record = PasscodeHasher.create("1234".toCharArray())
        assertFalse(PasscodeHasher.verify("4321".toCharArray(), record))
    }

    @Test fun recordsUseDifferentSalts() {
        val a = PasscodeHasher.create("1234".toCharArray())
        val b = PasscodeHasher.create("1234".toCharArray())
        assertFalse(a.salt.contentEquals(b.salt))
    }
}
