package com.example.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned AEAD format. Context is authenticated, preventing cross-chat substitution. */
object EnvelopeCipher {
    fun encrypt(key: SecretKey, bytes: ByteArray, aad: String = ""): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(aad.toByteArray())
            }
        return "ENCv1:${Base64.getEncoder().encodeToString(cipher.iv)}:${Base64.getEncoder().encodeToString(cipher.doFinal(bytes))}"
    }

    fun decrypt(key: SecretKey, payload: String, aad: String = ""): ByteArray {
        val p = payload.split(':')
        require(p.size == 3 && p[0] == "ENCv1") { "Invalid encrypted envelope" }
        val iv = Base64.getDecoder().decode(p[1])
        require(iv.size == 12) { "Invalid IV" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(aad.toByteArray())
            doFinal(Base64.getDecoder().decode(p[2]))
        }
    }
}
