package com.example

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.*
import com.example.data.repository.RoseVaultRepository
import com.example.nearby.NearbyChatProtocol
import com.example.security.CryptoEngine
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyOfflineTest {
    private val directories = mutableListOf<File>()
    private val aliases = mutableListOf<String>()
    private lateinit var alice: RoseVaultRepository
    private lateinit var bob: RoseVaultRepository
    private lateinit var a: NearbyChatProtocol
    private lateinit var b: NearbyChatProtocol
    private val wire = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
    private var dropAck = false
    private var connected = true

    private fun repository(): RoseVaultRepository {
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "offline-test-${newId()}").apply { mkdirs() }
        directories += dir
        val wrapped =
            object : ContextWrapper(context) {
                override fun getFilesDir() = dir
            }
        val alias = "offline-test-${newId()}"
        aliases += alias
        return RoseVaultRepository(wrapped, CryptoEngine(alias)).also { it.open() }
    }

    @Before
    fun setup() = runBlocking {
        alice = repository()
        bob = repository()
        a =
            NearbyChatProtocol(
                alice,
                { _, data ->
                    wire += data.copyOf()
                    if (connected) b.receive("alice", data)
                },
            )
        b =
            NearbyChatProtocol(
                bob,
                { _, data ->
                    wire += data.copyOf()
                    if (
                        connected &&
                            !(dropAck &&
                                JSONObject(data.toString(Charsets.UTF_8)).optString("type") ==
                                    "ack")
                    )
                        a.receive("bob", data)
                },
            )
        alice.nearbySender = { c, m, media -> a.send(c, m, media) }
        bob.nearbySender = { c, m, media -> b.send(c, m, media) }
        a.trust("bob", "Bob")
        b.trust("alice", "Alice")
        a.hello("bob", "Alice")
        b.hello("alice", "Bob")
    }

    @After
    fun cleanup() {
        if (::alice.isInitialized) alice.close()
        if (::bob.isInitialized) bob.close()
        directories.forEach { it.deleteRecursively() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        aliases.forEach { store.deleteEntry(it) }
    }

    private fun conversation(repo: RoseVaultRepository) =
        repo.state.value.conversations.first { it.transport == "nearby" }

    private suspend fun until(check: () -> Boolean) =
        withTimeout(5000) { while (!check()) delay(20) }

    @Test
    fun twoPhonesExchangeMessagesWithoutFirebaseOrInternet() = runBlocking {
        assertEquals("local", alice.uid)
        assertEquals("local", bob.uid)
        val c = conversation(alice)
        assertEquals(c.id, conversation(bob).id)
        alice.send(c.id, "secret offline hello ♡")
        until { alice.state.value.messages.any { it.delivery == "Delivered nearby" } }
        assertEquals("secret offline hello ♡", bob.state.value.messages.single().text)
        assertFalse(wire.any { it.toString(Charsets.UTF_8).contains("secret offline hello") })
        bob.send(c.id, "reply without internet")
        until { alice.state.value.messages.size == 2 }
        alice.close()
        alice.open()
        assertEquals(2, alice.state.value.messages.size)
    }

    @Test
    fun disconnectQueuesAndReconnectionAcknowledgesOnce() = runBlocking {
        val c = conversation(alice)
        a.forget("bob")
        b.forget("alice")
        connected = false
        alice.send(c.id, "queued safely")
        delay(150)
        assertEquals("Pending", alice.state.value.messages.single().delivery)
        assertTrue(bob.state.value.messages.isEmpty())
        alice.close()
        alice.open()
        assertEquals("Pending", alice.state.value.messages.single().delivery)
        connected = true
        a.trust("bob", "Bob")
        b.trust("alice", "Alice")
        a.hello("bob", "Alice")
        b.hello("alice", "Bob")
        alice.retry()
        until { alice.state.value.messages.single().delivery == "Delivered nearby" }
        assertEquals(1, bob.state.value.messages.size)
    }

    @Test
    fun droppedReceiptDoesNotFalselyReportDeliveryAndRetryDeduplicates() = runBlocking {
        dropAck = true
        val c = conversation(alice)
        alice.send(c.id, "receipt check")
        until { bob.state.value.messages.isNotEmpty() }
        assertEquals("Pending", alice.state.value.messages.single().delivery)
        dropAck = false
        alice.retry()
        until { alice.state.value.messages.single().delivery == "Delivered nearby" }
        assertEquals(1, bob.state.value.messages.size)
    }

    @Test
    fun encryptedAttachmentChunksRoundTripAndOpenOffline() = runBlocking {
        val bytes = ByteArray(65_536) { (it % 251).toByte() }
        val id = newId()
        val file = "media/$id.enc"
        File(alice.context.filesDir, "media").mkdirs()
        File(alice.context.filesDir, file).writeBytes(alice.crypto.seal(bytes))
        val media =
            VaultMedia(id, "test.bin", "application/octet-stream", file, bytes.size.toLong())
        alice.send(conversation(alice).id, "attachment", media)
        until { bob.state.value.messages.isNotEmpty() }
        val received = bob.state.value.messages.single()
        assertTrue(received.attachment.endsWith(".enc"))
        assertArrayEquals(
            bytes,
            bob.crypto.unseal(File(bob.context.filesDir, received.attachment).readBytes()),
        )
        assertTrue(
            wire.any { JSONObject(it.toString(Charsets.UTF_8)).optString("type") == "chunk" }
        )
    }

    @Test
    fun queuedAttachmentsAreProtectedFromAccidentalDeletion() = runBlocking {
        a.forget("bob")
        b.forget("alice")
        connected = false
        val source =
            File(alice.context.cacheDir, "shared/queued-${newId()}.txt").apply {
                parentFile!!.mkdirs()
                writeText("queued attachment")
            }
        try {
            val uri =
                androidx.core.content.FileProvider.getUriForFile(
                    alice.context,
                    "${alice.context.packageName}.files",
                    source,
                )
            val media = alice.importMedia(uri)
            alice.send(conversation(alice).id, "", media)
            alice.deleteMedia(media.id)
            assertTrue(File(alice.context.filesDir, media.file).exists())
            assertTrue(alice.state.value.media.any { it.id == media.id })
            assertTrue(alice.error.value!!.contains("queued"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun unknownEndpointCannotInjectMessages() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { b.receive("stranger", "{\"type\":\"hello\"}".toByteArray()) }
        }
        assertTrue(bob.state.value.messages.isEmpty())
    }

    @Test
    fun tamperedEnvelopeIsRejected() = runBlocking {
        val c = conversation(alice)
        val bad =
            JSONObject()
                .put("type", "message")
                .put("id", newId())
                .put("sender", alice.nearbyId)
                .put("envelope", "ENCv1:AAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAAAAAAAA==")
                .toString()
                .toByteArray()
        assertThrows(Exception::class.java) { runBlocking { b.receive("alice", bad) } }
        assertTrue(bob.state.value.messages.isEmpty())
    }
}
