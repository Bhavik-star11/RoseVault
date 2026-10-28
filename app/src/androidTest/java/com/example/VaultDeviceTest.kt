package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.repository.RoseVaultRepository
import com.example.security.CryptoEngine
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val target =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        directory =
            File(target.cacheDir, "vault-test-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        context =
            object : ContextWrapper(target) {
                override fun getFilesDir() = directory
            }
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun encryptedVaultPersistsWithoutPlaintext() = runBlocking {
        val repo = RoseVaultRepository(context)
        repo.open()
        repo.send("journal", "Device test secret ♡")
        val bytes = File(directory, "rosevault.enc").readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Device test secret"))
        repo.close()
        assertTrue(repo.state.value.messages.isEmpty())
        repo.open()
        assertEquals("Device test secret ♡", repo.state.value.messages.single().text)
        repo.close()
    }

    @Test
    fun memoryFreeMessagesDoNotSurviveRelock() = runBlocking {
        val repo = RoseVaultRepository(context)
        repo.open()
        repo.send("journal", "previous history")
        repo.setMode("journal", memoryFree = true)
        assertTrue(repo.state.value.messages.isEmpty())
        repo.send("journal", "temporary secret")
        assertEquals(1, repo.state.value.messages.size)
        repo.close()
        repo.open()
        assertTrue(repo.state.value.messages.isEmpty())
        repo.close()
    }

    @Test
    fun importedMediaCanBeOpenedAndDeletedOffline(): Unit = runBlocking {
        val repo = RoseVaultRepository(context)
        repo.open()
        val source =
            File(context.cacheDir, "shared/media-fixture-${java.util.UUID.randomUUID()}.txt")
                .apply {
                    parentFile!!.mkdirs()
                    writeText("offline media secret")
                }
        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                source,
            )
        val media = repo.importMedia(uri)
        assertFalse(File(directory, media.file).readText().contains("offline media secret"))
        val opened = repo.openMedia(media.file, media.mime)
        assertEquals(
            "offline media secret",
            context.contentResolver.openInputStream(opened)!!.bufferedReader().use { it.readText() },
        )
        repo.close()
        repo.open()
        assertEquals(media.id, repo.state.value.media.single().id)
        repo.deleteMedia(media.id)
        assertTrue(repo.state.value.media.isEmpty())
        assertFalse(File(directory, media.file).exists())
        repo.close()
        source.delete()
    }

    @Test
    fun legacyVaultOpensWithoutLosingHistory() = runBlocking {
        val repo = RoseVaultRepository(context)
        repo.open()
        repo.send("journal", "preserve this history")
        repo.close()
        val file = File(directory, "rosevault.enc")
        val json =
            org.json.JSONObject(repo.crypto.unseal(file.readBytes()).toString(Charsets.UTF_8))
        val conversations = json.getJSONArray("conversations")
        for (i in 0 until conversations.length()) conversations.getJSONObject(i).remove("transport")
        file.writeBytes(repo.crypto.seal(json.toString().toByteArray()))
        val upgraded = RoseVaultRepository(context)
        upgraded.open()
        assertEquals("preserve this history", upgraded.state.value.messages.single().text)
        upgraded.close()
    }

    @Test
    fun notificationInboxReadsEncryptedRecordsAndClearsThem() {
        val repo = RoseVaultRepository(context)
        repo.open()
        val folder = File(directory, "notification-inbox").apply { mkdirs() }
        File(folder, "test-notice.enc")
            .writeBytes(repo.crypto.seal("journal:notice-id".toByteArray()))
        File(folder, "corrupt.enc").writeText("not an encrypted record")
        assertEquals("journal", repo.notifications().single().conversationId)
        repo.clearNotifications()
        assertTrue(repo.notifications().isEmpty())
        repo.close()
    }

    @Test
    fun corruptionDoesNotSilentlyResetVault() {
        val repo = RoseVaultRepository(context)
        repo.open()
        repo.close()
        val file = File(directory, "rosevault.enc")
        val original = file.readBytes()
        file.writeText("corrupted")
        assertThrows(Exception::class.java) { RoseVaultRepository(context).open() }
        assertEquals("corrupted", file.readText())
        file.writeBytes(original)
    }

    @Test
    fun hardwareEcdhPeersDeriveSameKeyAndContextBindsSession() {
        val a = "test-alice-" + java.util.UUID.randomUUID()
        val b = "test-bob-" + java.util.UUID.randomUUID()
        try {
            val alice = CryptoEngine(a)
            val bob = CryptoEngine(b)
            val aKey = alice.sharedKey(bob.publicIdentity(), "conversation-one")
            val bKey = bob.sharedKey(alice.publicIdentity(), "conversation-one")
            assertArrayEquals(aKey.encoded, bKey.encoded)
            assertFalse(
                aKey.encoded.contentEquals(
                    bob.sharedKey(alice.publicIdentity(), "conversation-two").encoded
                )
            )
            assertEquals(
                alice.safetyNumber(bob.publicIdentity()),
                bob.safetyNumber(alice.publicIdentity()),
            )
        } finally {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(a)
            store.deleteEntry(b)
        }
    }
}
