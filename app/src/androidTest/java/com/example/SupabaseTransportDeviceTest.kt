package com.example

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cloud.CloudException
import com.example.cloud.SupabaseCloud
import com.example.security.CryptoEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional test-only fixture. Production source has no token injection or test login route. */
class SupabaseTransportDeviceTest {
    @Test
    fun authenticatedRestRefreshStorageAndCallTransitions(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureFile = File(context.filesDir, "transport-fixture.json")
        assumeTrue(
            "Requires an isolated test fixture supplied by the test operator",
            fixtureFile.exists(),
        )
        val fixture = JSONObject(fixtureFile.readText())
        val sessionFile = AtomicFile(File(context.filesDir, "supabase-session.enc"))
        val binding = AtomicFile(File(context.filesDir, "supabase-account.enc"))
        val oldSession = runCatching { sessionFile.readFully() }.getOrNull()
        val oldBinding = runCatching { binding.readFully() }.getOrNull()
        try {
            binding.delete()
            val session = fixture.getJSONObject("session").put("expires_at", 0)
            val output = sessionFile.startWrite()
            output.write(CryptoEngine().seal(session.toString().toByteArray()))
            sessionFile.finishWrite(output)
            val api = SupabaseCloud(context)
            assertEquals(fixture.getString("uid"), api.userId.value)
            val cid = fixture.getString("conversationId")
            assertTrue(api.rows("rv_conversations", "&id=eq.$cid").isNotEmpty())
            assertFalse(
                File(context.filesDir, "supabase-session.enc").readText().contains("access_token")
            )
            val mid = java.util.UUID.randomUUID().toString()
            val path = "media/$cid/$mid"
            val bytes = "ENCv1:transport-fixture:encrypted-bytes".toByteArray()
            assertNull(api.upload(path, bytes))
            assertArrayEquals(bytes, api.download(path))
            assertArrayEquals(bytes, api.upload(path, bytes))
            api.insert(
                "rv_messages",
                JSONObject()
                    .put("id", mid)
                    .put("conversation_id", cid)
                    .put("sender", api.userId.value)
                    .put("envelope", "ENCv1:transport-fixture:message")
                    .put("created_at", System.currentTimeMillis())
                    .put("expires_at", 0),
            )
            assertTrue(api.rows("rv_messages", "&id=eq.$mid").isNotEmpty())
            val callId = java.util.UUID.randomUUID().toString()
            api.insert(
                "rv_calls",
                JSONObject()
                    .put("id", callId)
                    .put("conversation_id", cid)
                    .put("from_uid", api.userId.value)
                    .put("to_uid", fixture.getString("peerUid"))
                    .put("status", "ringing")
                    .put("video", false)
                    .put("created_at", System.currentTimeMillis())
                    .put("offer", "ENCv1:transport-fixture:offer"),
            )
            api.patch("rv_calls", "id=eq.$callId", JSONObject().put("status", "ended"))
            assertEquals(
                "ended",
                api.rows("rv_calls", "&id=eq.$callId").first().getString("status"),
            )
            val profile =
                api.phoneAction("profile", JSONObject().put("key", fixture.getString("publicKey")))
            assertTrue(profile.getBoolean("registered"))
            try {
                api.function("turn-credentials")
                fail("Relay must not issue credentials without configuration")
            } catch (e: CloudException) {
                assertEquals(503, e.status)
            }
            api.delete("rv_messages", "id=eq.$mid")
            val deletion = JSONObject().put("prefixes", org.json.JSONArray().put(path))
            api.request(
                "DELETE",
                "/storage/v1/object/vault-media",
                deletion.toString().toByteArray(),
            )
        } finally {
            sessionFile.delete()
            binding.delete()
            fixtureFile.delete()
            if (oldSession != null) {
                val out = sessionFile.startWrite()
                out.write(oldSession)
                sessionFile.finishWrite(out)
            }
            if (oldBinding != null) {
                val out = binding.startWrite()
                out.write(oldBinding)
                binding.finishWrite(out)
            }
        }
    }
}
