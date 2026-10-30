package com.example

import android.content.Context
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

/** Opt-in localhost fixture; no production test-login or certificate bypass. */
class MatrixDeviceTest {
    @Test
    fun twoAccountsEncryptSyncAndRestoreQueuedMessages(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(target.filesDir, "matrix-fixture.json")
        assumeTrue("Requires an isolated local Synapse fixture", fixture.exists())
        val config = JSONObject(fixture.readText())
        val roots =
            listOf(
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(config.getString("certificate").byteInputStream())
                    .encoded
            )
        val root =
            File(target.cacheDir, "matrix-integration-" + java.util.UUID.randomUUID()).apply {
                mkdirs()
            }
        fun context(name: String): Context =
            object : ContextWrapper(target) {
                private val dir = File(root, name).apply { mkdirs() }

                override fun getFilesDir() = dir
            }
        val aContext = context("a")
        val a = MatrixVault(aContext, CryptoEngine(), roots)
        val b = MatrixVault(context("b"), CryptoEngine(), roots)
        suspend fun waitFor(label: String, test: () -> Boolean) {
            val success = withTimeoutOrNull(90000) { while (!test()) delay(500); true } == true
            assertTrue(label, success)
            println("Matrix check passed: $label")
        }
        try {
            val users = config.getJSONArray("users")
            val alice = users.getJSONObject(0)
            val bob = users.getJSONObject(1)
            a.login(config.getString("origin"), alice.getString("id"), alice.getString("password"))
            b.login(config.getString("origin"), bob.getString("id"), bob.getString("password"))
            val id = a.createDm(bob.getString("id"))
            waitFor("Bob receives invite") { b.state.value.rooms.any { it.id == id && it.invited } }
            b.accept(id)
            waitFor("Bob joins encrypted room") {
                b.state.value.rooms.any { it.id == id && !it.invited && it.encrypted }
            }
            a.openRoom(id)
            b.openRoom(id)
            delay(5000)
            a.send("MATRIX_DEVICE_SECRET_ONE")
            waitFor("Bob decrypts Alice's message") {
                b.state.value.messages.any { it.body == "MATRIX_DEVICE_SECRET_ONE" && !it.own }
            }
            b.pause()
            a.send("MATRIX_DEVICE_SECRET_WHILE_B_OFFLINE")
            waitFor("Server accepts queued message") {
                a.state.value.messages.any {
                    it.body == "MATRIX_DEVICE_SECRET_WHILE_B_OFFLINE" && it.status == "Sent"
                }
            }
            b.restore()
            waitFor("Bob room restored") { b.state.value.rooms.any { it.id == id } }
            b.openRoom(id)
            waitFor("Offline recipient receives missed message") {
                b.state.value.messages.any { it.body == "MATRIX_DEVICE_SECRET_WHILE_B_OFFLINE" }
            }
            a.setNetworkAvailable(false)
            a.send("MATRIX_DEVICE_SECRET_QUEUED_RESTART")
            waitFor("Outgoing message is locally queued") {
                a.state.value.messages.any {
                    it.body == "MATRIX_DEVICE_SECRET_QUEUED_RESTART" && it.status == "Queued"
                }
            }
            a.pause()
            a.restore()
            waitFor("Restored queue reaches Bob") {
                b.state.value.messages.any { it.body == "MATRIX_DEVICE_SECRET_QUEUED_RESTART" }
            }
            assertEquals(
                1,
                b.state.value.messages.count { it.body == "MATRIX_DEVICE_SECRET_QUEUED_RESTART" },
            )
            waitFor("Alice account restored") { a.state.value.user == alice.getString("id") }
            assertFalse(
                File(aContext.filesDir, "matrix-session.enc")
                    .readText()
                    .contains(alice.getString("id"))
            )
            assertFalse(
                File(aContext.filesDir, "matrix-session.enc").readText().contains("accessToken")
            )
            println("MATRIX_TEST_ROOM=$id")
        } finally {
            a.pause()
            b.pause()
        }
    }
}
