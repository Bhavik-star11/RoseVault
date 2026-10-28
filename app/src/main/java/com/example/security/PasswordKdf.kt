package com.example.security

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordKdf {
    const val ITERATIONS = 600_000

    fun validate(password: CharArray) {
        require(password.size in 12..128) { "Use a password with 12 to 128 characters." }
    }

    fun derive(password: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == 32) { "Invalid password salt" }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun matches(expected: ByteArray, actual: ByteArray) = MessageDigest.isEqual(expected, actual)
}
