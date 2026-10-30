package com.example

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.matrix.MatrixVault
import com.example.security.CryptoEngine
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Separate opt-in run with freshly generated disposable users. */
class MatrixAdvancedDeviceTest {
    @Test
    fun sasEncryptedMediaAndRecoveryOnFreshSdkDevice(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(target.filesDir, "matrix-fixture.json")
        assumeTrue("Requires fresh disposable local accounts", file.exists())
        val fixture = JSONObject(file.readText())
        val roots =
            listOf(
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(fixture.getString("certificate").byteInputStream())
                    .encoded
            )
        val parent =
            File(target.cacheDir, "matrix-advanced-" + java.util.UUID.randomUUID()).apply {
                mkdirs()
            }
        fun client(name: String): MatrixVault {
            val context =
                object : ContextWrapper(target) {
                    private val dir = File(parent, name).apply { mkdirs() }

                    override fun getFilesDir() = dir
                }
            return MatrixVault(context, CryptoEngine(), roots)
        }
        val a = client("alice")
        val b = client("bob")
        val c = client("alice-new-device")
        suspend fun waitFor(label: String, condition: () -> Boolean) {
            assertTrue(
                label,
                withTimeoutOrNull(60000) {
                    while (!condition()) delay(500)
                    true
                } == true,
            )
            println("Matrix advanced check passed: $label")
        }
        try {
            val alice = fixture.getJSONArray("users").getJSONObject(0)
            val bob = fixture.getJSONArray("users").getJSONObject(1)
            a.login(fixture.getString("origin"), alice.getString("id"), alice.getString("password"))
            b.login(fixture.getString("origin"), bob.getString("id"), bob.getString("password"))
            val id = a.createDm(bob.getString("id"))
            waitFor("Fresh encrypted invitation") {
                b.state.value.rooms.any { it.id == id && it.invited }
            }
            b.accept(id)
            a.openRoom(id)
            b.openRoom(id)
            assertTrue("Alice identity ready for SAS", a.state.value.verificationReady)
            assertTrue("Bob identity ready for SAS", b.state.value.verificationReady)
            a.requestVerification(bob.getString("id"))
            waitFor("Bob receives verification request") {
                b.state.value.verification.startsWith("Verification requested")
            }
            b.acceptVerification()
            waitFor("Both SDKs produce SAS") {
                a.state.value.sas.isNotEmpty() && b.state.value.sas.isNotEmpty()
            }
            assertEquals(a.state.value.sas, b.state.value.sas)
            a.approveVerification()
            b.approveVerification()
            waitFor("Mutual SAS completes") {
                a.state.value.verification == "Verification completed" &&
                    b.state.value.verification == "Verification completed"
            }
            a.send("MATRIX_ADV_SECRET")
            waitFor("Verified encrypted message arrives") {
                b.state.value.messages.any { it.body == "MATRIX_ADV_SECRET" }
            }
            a.sendFile("MATRIX_ADV_FILE_SECRET".toByteArray(), "test.txt", "text/plain")
            waitFor("Encrypted attachment arrives") {
                b.state.value.messages.any { it.media != null }
            }
            val attachment = requireNotNull(b.state.value.messages.last { it.media != null }.media)
            assertEquals("MATRIX_ADV_FILE_SECRET", b.download(attachment).toString(Charsets.UTF_8))
            a.enableRecovery()
            val key = a.state.value.recoveryKey
            assertTrue("Recovery secret returned", key.isNotBlank())
            a.awaitBackupUpload()
            c.login(fixture.getString("origin"), alice.getString("id"), alice.getString("password"))
            assertNotEquals(a.state.value.device, c.state.value.device)
            c.recover(key)
            waitFor("Fresh device sees room") { c.state.value.rooms.any { it.id == id } }
            c.openRoom(id)
            c.paginate()
            waitFor("Historical encrypted message recovered") {
                c.state.value.messages.any { it.body == "MATRIX_ADV_SECRET" }
            }
        } finally {
            a.pause()
            b.pause()
            c.pause()
        }
    }
}
