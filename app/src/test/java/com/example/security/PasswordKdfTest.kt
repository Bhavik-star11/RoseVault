package com.example.security

import org.junit.Assert.*
import org.junit.Test

class PasswordKdfTest {
    @Test
    fun verifierDependsOnPasswordAndRandomSalt() {
        val a = PasswordKdf.derive("rose vault test password".toCharArray(), ByteArray(32) { 1 })
        val repeat =
            PasswordKdf.derive("rose vault test password".toCharArray(), ByteArray(32) { 1 })
        val otherSalt =
            PasswordKdf.derive("rose vault test password".toCharArray(), ByteArray(32) { 2 })
        val wrong = PasswordKdf.derive("wrong test password".toCharArray(), ByteArray(32) { 1 })
        assertTrue(PasswordKdf.matches(a, repeat))
        assertFalse(PasswordKdf.matches(a, otherSalt))
        assertFalse(PasswordKdf.matches(a, wrong))
    }

    @Test
    fun policyRejectsShortOrExcessivePasswordsAndBadSalt() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordKdf.validate("short".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PasswordKdf.validate(CharArray(129) { 'a' })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PasswordKdf.derive("valid test password".toCharArray(), ByteArray(8))
        }
        PasswordKdf.validate("valid test password".toCharArray())
    }
}
