package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai.RoseAi
import com.example.data.repository.RoseVaultRepository
import com.example.security.CryptoEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionFeaturesDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context

    @Before
    fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory =
            File(target.cacheDir, "connection-test-" + java.util.UUID.randomUUID()).apply {
                mkdirs()
            }
        context =
            object : ContextWrapper(target) {
                override fun getFilesDir() = directory
            }
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun privateIntentionIsEncryptedAndNeverBecomesAChatMessage(): Unit = runBlocking {
        val repo = RoseVaultRepository(context)
        try {
            repo.open()
            repo.privateIntention("journal", "Dating")
            assertTrue(repo.state.value.messages.isEmpty())
            repo.send("journal", "A shared moment", kind = "moment")
            assertFalse(File(directory, "rosevault.enc").readText().contains("Dating"))
            repo.close()
            repo.open()
            assertEquals("Dating", repo.state.value.privateIntentions["journal"])
            assertEquals("moment", repo.state.value.messages.single().kind)
            assertEquals("A shared moment", repo.state.value.messages.single().text)
        } finally {
            repo.close()
        }
    }

    @Test
    fun serverCredentialsAreEncryptedAndForgettable() {
        val ai = RoseAi(context, CryptoEngine())
        val config = RoseAi.Config("https://ai.example.org", "a".repeat(64), "qwen3:4b")
        ai.save(config)
        assertEquals(config, ai.config())
        val bytes = File(directory, "rose-ai-server.enc").readText()
        assertFalse(bytes.contains(config.token))
        assertFalse(bytes.contains(config.origin))
        ai.forget()
        assertEquals("", ai.config().origin)
    }

    @Test
    fun serverConfigurationRejectsInsecureOrAmbiguousDestinations() {
        val ai = RoseAi(context, CryptoEngine())
        for (origin in
            listOf(
                "http://ai.example.org",
                "https://password@ai.example.org",
                "https://ai.example.org/?token=secret",
                "https://ai.example.org/api/chat",
            )) {
            assertTrue(runCatching { ai.save(RoseAi.Config(origin, "a".repeat(64))) }.isFailure)
        }
    }
}
