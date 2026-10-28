package com.example.security

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class EnvelopeCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripBinaryAndUnicode() {
        val k = key()
        for (bytes in
            listOf(
                byteArrayOf(),
                "♡ RoseVault 🌹".toByteArray(),
                ByteArray(65536) { it.toByte() },
            )) {
            assertArrayEquals(
                bytes,
                EnvelopeCipher.decrypt(k, EnvelopeCipher.encrypt(k, bytes, "chat:1"), "chat:1"),
            )
        }
    }

    @Test
    fun repeatedPlaintextGetsFreshIv() {
        val k = key()
        val a = EnvelopeCipher.encrypt(k, "same".toByteArray())
        val b = EnvelopeCipher.encrypt(k, "same".toByteArray())
        assertNotEquals(a.split(':')[1], b.split(':')[1])
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongConversationFails() {
        val k = key()
        EnvelopeCipher.decrypt(
            k,
            EnvelopeCipher.encrypt(k, "private".toByteArray(), "chat:1"),
            "chat:2",
        )
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongKeyFails() {
        EnvelopeCipher.decrypt(key(), EnvelopeCipher.encrypt(key(), "private".toByteArray()))
    }

    @Test(expected = AEADBadTagException::class)
    fun modifiedCiphertextFails() {
        val k = key()
        val p = EnvelopeCipher.encrypt(k, "private".toByteArray()).split(':').toMutableList()
        val b = Base64.getDecoder().decode(p[2])
        b[0] = (b[0].toInt() xor 1).toByte()
        p[2] = Base64.getEncoder().encodeToString(b)
        EnvelopeCipher.decrypt(k, p.joinToString(":"))
    }

    @Test
    fun malformedEnvelopesFail() {
        for (p in listOf("hello", "ENCv2:AA==:AA==", "ENCv1:AA==:AA==", "ENCv1:@@:bad")) {
            assertThrows(IllegalArgumentException::class.java) { EnvelopeCipher.decrypt(key(), p) }
        }
    }
}
