package com.example.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom
import org.json.JSONObject

/** Local application gate, separate from cloud authentication. Phone credentials remain the recovery path. */
class AppPasswordStore(
    context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val file = AtomicFile(File(context.filesDir, "app-password.enc"))
    private val crypto = CryptoEngine()
    val configured: Boolean
        get() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    @Synchronized
    private fun read(): JSONObject =
        JSONObject(crypto.unseal(file.readFully()).toString(Charsets.UTF_8))

    @Synchronized
    private fun write(value: JSONObject) {
        val bytes = crypto.seal(value.toString().toByteArray())
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        } finally {
            bytes.fill(0)
        }
    }

    fun prepare(password: CharArray): JSONObject {
        PasswordKdf.validate(password)
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = PasswordKdf.derive(password, salt)
        return try {
            JSONObject()
                .put("version", 1)
                .put("salt", CryptoEngine.b64(salt))
                .put("hash", CryptoEngine.b64(hash))
                .put("failures", 0)
                .put("blockedUntil", 0L)
        } finally {
            hash.fill(0)
            salt.fill(0)
        }
    }

    @Synchronized
    fun commit(value: JSONObject) {
        write(value)
    }

    fun set(password: CharArray) {
        commit(prepare(password))
    }

    @Synchronized
    fun verify(password: CharArray): Boolean {
        require(configured) { "No RoseVault password is set. Use your phone screen lock." }
        val record = read()
        require(record.getInt("version") == 1) {
            "Use your phone screen lock to restore password settings."
        }
        val wait = record.optLong("blockedUntil") - now()
        require(wait <= 0) {
            "Too many attempts. Try again in ${(wait + 999) / 1000} seconds, or use your phone screen lock."
        }
        val salt = CryptoEngine.decode(record.getString("salt"))
        val expected = CryptoEngine.decode(record.getString("hash"))
        require(expected.size == 32) { "Invalid password record. Use your phone screen lock." }
        val actual = PasswordKdf.derive(password, salt)
        val valid =
            try {
                PasswordKdf.matches(expected, actual)
            } finally {
                expected.fill(0)
                actual.fill(0)
                salt.fill(0)
            }
        val failures = if (valid) 0 else (record.optInt("failures") + 1).coerceAtMost(100)
        val delay =
            if (failures >= 5) (30_000L shl (failures - 5).coerceAtMost(4)).coerceAtMost(300_000L)
            else 0L
        write(
            record
                .put("failures", failures)
                .put("blockedUntil", if (delay > 0) now() + delay else 0L)
        )
        return valid
    }

    /** Call only after a successful system-credential confirmation. */
    @Synchronized
    fun remove() {
        file.delete()
    }
}
