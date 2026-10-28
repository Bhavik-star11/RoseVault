package com.example.nearby

import com.example.data.model.*
import com.example.data.repository.*
import com.example.security.CryptoEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Application encryption and durable receipt acknowledgements, independent of the radio API. */
class NearbyChatProtocol(
    private val repo: RoseVaultRepository,
    private val transmitFrame: suspend (String, ByteArray) -> Unit,
    private val paired: (String, VaultConversation) -> Unit = { _, _ -> },
) {
    private val trusted = mutableMapOf<String, String>()
    private val peers = mutableMapOf<String, VaultConversation>()
    private val outgoing = Mutex()

    private data class Assembly(
        val parts: Array<String?>,
        val started: Long = System.currentTimeMillis(),
        var size: Int = 0,
    )

    private val chunks = mutableMapOf<String, Assembly>()

    fun trust(endpoint: String, name: String) {
        trusted[endpoint] = name.take(48)
    }

    fun forget(endpoint: String) {
        trusted.remove(endpoint)
        peers.remove(endpoint)
        chunks.keys.filter { it.startsWith("$endpoint:") }.forEach { chunks.remove(it) }
    }

    fun clear() {
        trusted.clear()
        peers.clear()
        chunks.clear()
    }

    fun hasConversation(id: String) = peers.values.any { it.id == id }

    suspend fun hello(endpoint: String, name: String) {
        require(endpoint in trusted)
        frame(
            endpoint,
            JSONObject()
                .put("type", "hello")
                .put("version", 1)
                .put("key", repo.crypto.publicIdentity())
                .put("name", name.take(48)),
        )
    }

    private suspend fun frame(endpoint: String, j: JSONObject) {
        val bytes = j.toString().toByteArray()
        require(bytes.size <= 32_000)
        transmitFrame(endpoint, bytes)
    }

    suspend fun send(c: VaultConversation, m: EncryptedMessage, media: VaultMedia?) =
        outgoing.withLock {
            val endpoint = peers.entries.firstOrNull { it.value.id == c.id }?.key ?: return@withLock
            val body =
                JSONObject()
                    .put("message", m.copy(attachment = if (media == null) "" else "nearby").json())
            if (media != null) {
                val bytes = repo.crypto.unseal(File(repo.context.filesDir, media.file).readBytes())
                try {
                    body.put("media", CryptoEngine.b64(bytes))
                } finally {
                    bytes.fill(0)
                }
            }
            val envelope =
                repo.crypto.encrypt(
                    repo.crypto.sharedKey(c.partnerKey, c.id),
                    body.toString().toByteArray(),
                    "nearby:${c.id}:${m.id}:${m.senderId}:message",
                )
            sendPacket(
                endpoint,
                JSONObject()
                    .put("type", "message")
                    .put("id", m.id)
                    .put("sender", m.senderId)
                    .put("envelope", envelope),
            )
        }

    private suspend fun sendPacket(endpoint: String, packet: JSONObject) {
        val bytes = packet.toString().toByteArray()
        if (bytes.size <= 24_000) {
            transmitFrame(endpoint, bytes)
            return
        }
        require(bytes.size <= 40 * 1024 * 1024) { "Attachment is too large for a nearby transfer" }
        val id = UUID.randomUUID().toString()
        val total = (bytes.size + 11_999) / 12_000
        for (index in 0 until total) {
            val data = bytes.copyOfRange(index * 12_000, minOf(bytes.size, (index + 1) * 12_000))
            frame(
                endpoint,
                JSONObject()
                    .put("type", "chunk")
                    .put("id", id)
                    .put("index", index)
                    .put("total", total)
                    .put("data", CryptoEngine.b64(data)),
            )
        }
    }

    suspend fun receive(endpoint: String, bytes: ByteArray) {
        require(repo.loaded && endpoint in trusted) { "Unverified nearby connection" }
        require(bytes.size <= 32_000)
        val j = JSONObject(bytes.toString(Charsets.UTF_8))
        when (j.getString("type")) {
            "hello" -> {
                require(j.getInt("version") == 1)
                val key = j.getString("key")
                require(key.length < 512)
                val old = peers[endpoint]
                require(old == null || old.partnerKey == key) {
                    "Peer key changed during the connection"
                }
                val c = repo.nearbyConversation(key, trusted.getValue(endpoint))
                peers[endpoint] = c
                paired(endpoint, c)
                repo.retry()
            }
            "chunk" -> {
                require(endpoint in peers)
                chunks.entries.removeAll { System.currentTimeMillis() - it.value.started > 120_000 }
                val id = j.getString("id")
                require(id.matches(Regex("[a-f0-9-]{36}")))
                val index = j.getInt("index")
                val total = j.getInt("total")
                require(total in 1..3500 && index in 0 until total)
                val key = "$endpoint:$id"
                if (key !in chunks) require(chunks.size < 2) { "Too many incomplete transfers" }
                val a = chunks.getOrPut(key) { Assembly(arrayOfNulls(total)) }
                require(a.parts.size == total)
                val data = j.getString("data")
                require(data.length <= 16_000)
                if (a.parts[index] == null) {
                    a.size += data.length
                    require(a.size <= 56 * 1024 * 1024)
                    a.parts[index] = data
                }
                if (a.parts.all { it != null }) {
                    chunks.remove(key)
                    val output = java.io.ByteArrayOutputStream()
                    a.parts.forEach { output.write(CryptoEngine.decode(it!!)) }
                    val packet = output.toByteArray()
                    require(packet.size <= 40 * 1024 * 1024)
                    receivePacket(endpoint, JSONObject(packet.toString(Charsets.UTF_8)))
                }
            }
            else -> receivePacket(endpoint, j)
        }
    }

    private suspend fun receivePacket(endpoint: String, j: JSONObject) {
        val c = peers[endpoint] ?: error("Exchange device keys before chatting")
        val id = j.getString("id")
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val sender = j.getString("sender")
        require(sender == c.partnerUid)
        val type = j.getString("type")
        require(type == "message" || type == "ack")
        val plain =
            repo.crypto.decrypt(
                repo.crypto.sharedKey(c.partnerKey, c.id),
                j.getString("envelope"),
                "nearby:${c.id}:$id:$sender:$type",
            )
        if (type == "ack") {
            require(plain.toString(Charsets.UTF_8) == id)
            repo.acknowledgeNearby(c.id, id)
            return
        }
        val body = JSONObject(plain.toString(Charsets.UTF_8))
        val message = messageFromJson(body.getJSONObject("message"))
        require(message.id == id && message.senderId == sender && message.conversationId == c.id)
        require(message.text.toByteArray().size <= 16_000)
        val media = if (body.has("media")) CryptoEngine.decode(body.getString("media")) else null
        require(
            (media == null && message.attachment.isEmpty()) ||
                (media != null && message.attachment == "nearby")
        ) {
            "Invalid nearby attachment"
        }
        try {
            repo.acceptNearby(c, message, media)
        } finally {
            media?.fill(0)
            plain.fill(0)
        }
        val ack =
            repo.crypto.encrypt(
                repo.crypto.sharedKey(c.partnerKey, c.id),
                id.toByteArray(),
                "nearby:${c.id}:$id:${repo.nearbyId}:ack",
            )
        frame(
            endpoint,
            JSONObject()
                .put("type", "ack")
                .put("id", id)
                .put("sender", repo.nearbyId)
                .put("envelope", ack),
        )
    }
}
