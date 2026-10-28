package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngine(private val identityAlias: String = "RoseVault_Identity_P256_v1") {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val masterAlias = "RoseVault_Hardware_Master_Key_v1"
    private val biometricAlias = "RoseVault_Biometric_CryptoObject_Key_v2"

    private fun key(alias: String, biometric: Boolean = false): SecretKey {
        if (!store.containsAlias(alias)) {
            val spec =
                KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
            if (biometric)
                spec.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(spec.build())
                generateKey()
            }
        }
        return store.getKey(alias, null) as SecretKey
    }

    fun seal(bytes: ByteArray): ByteArray = encrypt(key(masterAlias), bytes).toByteArray()

    fun unseal(bytes: ByteArray): ByteArray =
        decrypt(key(masterAlias), bytes.toString(Charsets.UTF_8))

    fun createBiometricEncryptCryptoObject(): BiometricPrompt.CryptoObject =
        BiometricPrompt.CryptoObject(
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(biometricAlias, true))
            }
        )

    fun encryptWithAuthenticatedCipher(cipher: Cipher, bytes: ByteArray): String =
        "ENCv1:${b64(cipher.iv)}:${b64(cipher.doFinal(bytes))}"

    fun createBiometricDecryptCryptoObject(payload: String): BiometricPrompt.CryptoObject {
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == "ENCv1")
        return BiometricPrompt.CryptoObject(
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(biometricAlias, true),
                    GCMParameterSpec(128, decode(parts[1])),
                )
            }
        )
    }

    fun encrypt(key: SecretKey, bytes: ByteArray, aad: String = ""): String =
        EnvelopeCipher.encrypt(key, bytes, aad)

    fun decrypt(key: SecretKey, payload: String, aad: String = ""): ByteArray =
        EnvelopeCipher.decrypt(key, payload, aad)

    fun publicIdentity(): String {
        if (!store.containsAlias(identityAlias))
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(identityAlias, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build()
                )
                generateKeyPair()
            }
        return b64(store.getCertificate(identityAlias).publicKey.encoded)
    }

    fun sharedKey(peer: String, conversationId: String): SecretKey {
        publicIdentity()
        val remote: PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(peer)))
        val secret =
            KeyAgreement.getInstance("ECDH").run {
                init(store.getKey(identityAlias, null))
                doPhase(remote, true)
                generateSecret()
            }
        val salt = MessageDigest.getInstance("SHA-256").digest("RoseVault/ECDH/v1".toByteArray())
        val prk =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(salt, "HmacSHA256"))
                doFinal(secret)
            }
        secret.fill(0)
        val output =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(prk, "HmacSHA256"))
                doFinal(("conversation:$conversationId".toByteArray() + byteArrayOf(1)))
            }
        prk.fill(0)
        return SecretKeySpec(output, "AES")
    }

    fun safetyNumber(peer: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(listOf(publicIdentity(), peer).sorted().joinToString(":").toByteArray())
            .take(24)
            .joinToString("") { "%02x".format(it) }
            .chunked(4)
            .joinToString(" ")

    companion object {
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
