package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.example.data.model.*
import com.example.security.CryptoEngine
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class RoseVaultRepository(val context: Context, val crypto: CryptoEngine = CryptoEngine()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(VaultState())
    val state = mutable.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private val vault = AtomicFile(File(context.filesDir, "rosevault.enc"))
    val cloudApi = (context.applicationContext as com.example.RoseVaultApp).cloud
    private var syncJob: Job? = null
    private val cursors = mutableMapOf<String, Long>()
    val uid
        get() = cloudApi.userId.value

    val cloudReady
        get() = cloudApi.configured

    var loaded = false
        private set

    var nearbySender: (suspend (VaultConversation, EncryptedMessage, VaultMedia?) -> Unit)? = null
    private val outgoingMutex = Mutex()
    val online = MutableStateFlow(false)
    val nearbyId
        get() =
            "nearby-" +
                MessageDigest.getInstance("SHA-256")
                    .digest(crypto.publicIdentity().toByteArray())
                    .take(16)
                    .joinToString("") { "%02x".format(it) }

    fun isMine(message: EncryptedMessage) =
        message.senderId == "local" || message.senderId == uid || message.senderId == nearbyId

    private fun internetAvailable(): Boolean =
        connectivity.activeNetwork?.let {
            connectivity
                .getNetworkCapabilities(it)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

    private val received = mutableSetOf<String>()
    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private var networkRegistered = false
    private var retryJob: Job? = null
    private val networkCallback =
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch {
                    val previous = online.value
                    online.value = internetAvailable()
                    if (loaded) {
                        retry()
                        if (!previous && online.value && uid != "local") connect()
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities,
            ) {
                scope.launch {
                    val previous = online.value
                    online.value = internetAvailable()
                    if (loaded) {
                        retry()
                        if (!previous && online.value && uid != "local") connect()
                    }
                }
            }

            override fun onLost(network: android.net.Network) {
                scope.launch { online.value = internetAvailable() }
            }
        }

    private fun persist(value: VaultState = mutable.value) {
        if (!loaded) return
        val durable =
            value.copy(
                messages =
                    value.messages.filter { m ->
                        value.conversations.none { it.id == m.conversationId && it.memoryFree }
                    }
            )
        val bytes = crypto.seal(durable.encode().toByteArray())
        val output = vault.startWrite()
        try {
            output.write(bytes)
            vault.finishWrite(output)
        } catch (e: Exception) {
            vault.failWrite(output)
            throw e
        }
    }

    @Synchronized
    private fun change(block: (VaultState) -> VaultState) {
        if (!loaded) return
        val next = block(mutable.value)
        persist(next)
        mutable.value = next
    }

    @Synchronized
    fun open() {
        if (!loaded) {
            mutable.value =
                if (vault.baseFile.exists() || File(vault.baseFile.path + ".bak").exists())
                    decodeState(crypto.unseal(vault.readFully()).toString(Charsets.UTF_8))
                else
                    VaultState(
                        conversations =
                            listOf(
                                VaultConversation(
                                    id = "journal",
                                    title = "Notes to myself",
                                    subtitle = "A little space, just for you",
                                )
                            )
                    )
            loaded = true
            com.example.security.NotificationVaultHelper.policy(
                context,
                mutable.value.settings.notificationPolicy,
            )
            online.value = internetAvailable()
            sweep()
            audit("Vault unlocked")
            if (!networkRegistered) {
                connectivity.registerDefaultNetworkCallback(networkCallback)
                networkRegistered = true
            }
            connect()
            retry()
        }
    }

    @Synchronized
    fun close() {
        if (loaded) {
            change { s ->
                s.copy(
                    conversations =
                        s.conversations.map {
                            if (it.memoryFree)
                                it.copy(memoryFreeCutoff = System.currentTimeMillis())
                            else it
                        }
                )
            }
            audit("Vault locked")
            if (networkRegistered) {
                connectivity.unregisterNetworkCallback(networkCallback)
                networkRegistered = false
            }
            scope.coroutineContext.cancelChildren()
            syncJob?.cancel()
            syncJob = null
            cursors.clear()
            mutable.value = VaultState()
            loaded = false
            received.clear()
        }
    }

    fun audit(text: String) {
        change { it.copy(audit = (listOf(SecurityEvent(text)) + it.audit).take(200)) }
    }

    fun privateIntention(id: String, intention: String) {
        require(intention in setOf("Friends", "Dating", "Lovers", "Unsure"))
        change { it.copy(privateIntentions = it.privateIntentions + (id to intention)) }
    }

    fun settings(value: VaultSettings) {
        com.example.security.NotificationVaultHelper.policy(context, value.notificationPolicy)
        change { it.copy(settings = value) }
        audit("Privacy settings updated")
    }

    @Synchronized
    fun sweep() {
        if (loaded)
            change {
                it.copy(
                    messages =
                        it.messages.filter { m ->
                            m.expiresAt == 0L || m.expiresAt > System.currentTimeMillis()
                        }
                )
            }
    }

    fun newConversation(title: String, privateSpace: Boolean, contactCode: String = "") {
        require(title.isNotBlank()) { "Give this space a name" }
        var conversation = VaultConversation(title = title.trim(), privateSpace = privateSpace)
        if (contactCode.isNotBlank()) {
            require(uid != "local") { "Sign in with your phone number before pairing" }
            val contact = JSONObject(contactCode)
            require(contact.getInt("version") == 1)
            val peer = contact.getString("uid")
            val key = contact.getString("key")
            require(peer != uid) { "Use a contact code from a different account" }
            crypto.sharedKey(key, "validation")
            val id =
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        listOf(crypto.publicIdentity(), key)
                            .sorted()
                            .joinToString(":")
                            .toByteArray()
                    )
                    .joinToString("") { "%02x".format(it) }
            conversation =
                conversation.copy(
                    id = id,
                    partnerUid = peer,
                    partnerKey = key,
                    subtitle = "Compare safety numbers before sharing",
                )
            val c = conversation
            launch {
                val result =
                    cloudApi.phoneAction(
                        "pair",
                        JSONObject()
                            .put("key", crypto.publicIdentity())
                            .put("peerUid", peer)
                            .put("peerKey", key),
                    )
                require(result.getString("id") == c.id) { "Contact identity mismatch" }
                attach(c)
            }
        }
        val c = conversation
        change { it.copy(conversations = it.conversations.filterNot { old -> old.id == c.id } + c) }
        audit("Private space created")
    }

    suspend fun startPhoneConversation(
        title: String,
        privateSpace: Boolean,
        number: String,
    ): String {
        check(loaded) { "Unlock the vault first" }
        val phone = com.example.ui.auth.normalizePhoneNumber(number)
        require(cloudApi.phone.value != null && uid != "local") {
            "Sign in with your phone number in Security first."
        }
        require(online.value) {
            "Starting a new phone-number chat needs internet. Saved chats and Nearby mode still work offline."
        }
        val data =
            cloudApi.phoneAction(
                "startChat",
                JSONObject().put("phoneNumber", phone).put("key", crypto.publicIdentity()),
            )
        val id = data.getString("id")
        val peer = data.getString("peerUid")
        val peerKey = data.getString("peerKey")
        crypto.sharedKey(peerKey, id)
        val expected =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    listOf(crypto.publicIdentity(), peerKey)
                        .sorted()
                        .joinToString(":")
                        .toByteArray()
                )
                .joinToString("") { "%02x".format(it) }
        require(id == expected) { "The phone contact key could not be verified." }
        val existing = mutable.value.conversations.firstOrNull { it.id == id }
        if (existing != null) {
            require(existing.partnerKey == peerKey && existing.partnerUid == peer)
            return id
        }
        val c =
            VaultConversation(
                id = id,
                title = title.trim().ifEmpty { phone },
                subtitle = "Phone chat · compare safety numbers",
                partnerUid = peer,
                partnerKey = peerKey,
                privateSpace = privateSpace,
            )
        change { it.copy(conversations = it.conversations + c) }
        attach(c)
        audit("Phone conversation created")
        return id
    }

    fun contactCode(): String {
        require(uid != "local") { "Sign in with your phone number to get your contact code" }
        return JSONObject()
            .put("version", 1)
            .put("uid", uid)
            .put("key", crypto.publicIdentity())
            .toString()
    }

    fun setMode(id: String, memoryFree: Boolean? = null, ephemeral: Boolean? = null) {
        change { s ->
            s.copy(
                conversations =
                    s.conversations.map {
                        if (it.id == id)
                            it.copy(
                                memoryFree = memoryFree ?: it.memoryFree,
                                ephemeral = ephemeral ?: it.ephemeral,
                                memoryFreeCutoff =
                                    if (memoryFree == true) System.currentTimeMillis()
                                    else it.memoryFreeCutoff,
                            )
                        else it
                    },
                messages =
                    if (memoryFree == true) s.messages.filterNot { it.conversationId == id }
                    else s.messages,
            )
        }
        audit("Conversation privacy updated")
    }

    suspend fun send(
        id: String,
        text: String,
        media: VaultMedia? = null,
        kind: String = "text",
        reference: String = "",
    ) {
        require(loaded) { "Unlock the vault first" }
        require(text.isNotBlank() || media != null)
        require(
            kind in
                setOf(
                    "text",
                    "space.offer",
                    "space.accept",
                    "space.decline",
                    "space.close",
                    "moment",
                    "mood",
                    "journal",
                    "goal",
                    "movie",
                )
        )
        require(text.toByteArray().size <= 16_000) { "Keep each message under 16 KB" }
        val c = mutable.value.conversations.first { it.id == id }
        val message =
            EncryptedMessage(
                conversationId = id,
                senderId = if (c.transport == "nearby") nearbyId else uid,
                text = text.trim(),
                kind = kind,
                reference = reference,
                expiresAt =
                    if (c.ephemeral || c.memoryFree) System.currentTimeMillis() + 86_400_000 else 0,
                attachment = media?.file ?: "",
                mime = media?.mime ?: "",
                delivery = if (c.partnerUid.isEmpty()) "Local only" else "Pending",
            )
        change { it.copy(messages = it.messages + message) }
        if (c.partnerUid.isNotEmpty()) launch { dispatch(c, message, media) }
    }

    private suspend fun dispatch(
        c: VaultConversation,
        m: EncryptedMessage,
        media: VaultMedia? = null,
    ) =
        outgoingMutex.withLock {
            if (
                !loaded || mutable.value.messages.none { it.id == m.id && it.delivery == "Pending" }
            )
                return@withLock
            if (c.transport == "nearby") nearbySender?.invoke(c, m, media)
            else if (uid != "local" && internetAvailable())
                withTimeoutOrNull(15_000) { transmit(c, m, media) }
        }

    private suspend fun transmit(
        c: VaultConversation,
        m: EncryptedMessage,
        media: VaultMedia? = null,
    ) {
        try {
            val already = cloudApi.rows("rv_messages", "&id=eq.${m.id}")
            if (already.isNotEmpty()) {
                require(already.first().getString("sender") == uid) { "Message identity collision" }
                change {
                    it.copy(
                        messages =
                            it.messages.map { old ->
                                if (old.id == m.id) old.copy(delivery = "Sent") else old
                            }
                    )
                }
                return
            }
            val key = crypto.sharedKey(c.partnerKey, c.id)
            var payload = m
            if (media != null) {
                val bytes = crypto.unseal(File(context.filesDir, media.file).readBytes())
                val blob = crypto.encrypt(key, bytes, "media:${c.id}:${m.id}").toByteArray()
                bytes.fill(0)
                val path = "media/${c.id}/${m.id}"
                cloudApi.upload(path, blob)?.let { existing ->
                    val expected =
                        crypto.decrypt(key, blob.toString(Charsets.UTF_8), "media:${c.id}:${m.id}")
                    val stored =
                        crypto.decrypt(
                            key,
                            existing.toString(Charsets.UTF_8),
                            "media:${c.id}:${m.id}",
                        )
                    try {
                        require(MessageDigest.isEqual(expected, stored)) {
                            "Attachment identity collision"
                        }
                    } finally {
                        expected.fill(0)
                        stored.fill(0)
                    }
                }
                payload = m.copy(attachment = path)
            }
            val envelope =
                crypto.encrypt(key, payload.json().toString().toByteArray(), "${c.id}:${m.id}:$uid")
            cloudApi.insert(
                "rv_messages",
                JSONObject()
                    .put("id", m.id)
                    .put("conversation_id", c.id)
                    .put("sender", uid)
                    .put("envelope", envelope)
                    .put("created_at", m.createdAt)
                    .put("expires_at", m.expiresAt),
            )
            change {
                it.copy(
                    messages =
                        it.messages.map { old ->
                            if (old.id == m.id) old.copy(delivery = "Sent") else old
                        }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = "Message remains queued: ${e.localizedMessage}"
        }
    }

    fun retry() {
        if (!loaded || retryJob?.isActive == true) return
        retryJob =
            scope.launch {
                try {
                    if (!loaded) return@launch
                    sweep()
                    val cutoff = System.currentTimeMillis() - 86_400_000
                    change {
                        it.copy(
                            messages =
                                it.messages.map { m ->
                                    if (m.delivery == "Pending" && m.createdAt < cutoff)
                                        m.copy(delivery = "Delivery expired")
                                    else m
                                }
                        )
                    }
                    mutable.value.messages
                        .filter { it.delivery == "Pending" }
                        .forEach { m ->
                            if (!loaded) return@launch
                            mutable.value.conversations
                                .find { it.id == m.conversationId }
                                ?.let { c ->
                                    dispatch(
                                        c,
                                        m,
                                        mutable.value.media.find { it.file == m.attachment },
                                    )
                                }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (loaded) error.value = "Messages remain queued: ${e.localizedMessage}"
                }
            }
    }

    fun connect() {
        syncJob?.cancel()
        if (uid == "local" || !loaded || !cloudReady) return
        syncJob =
            scope.launch {
                while (isActive && loaded && uid != "local") {
                    if (internetAvailable()) {
                        try {
                            val documents = cloudApi.rows("rv_conversations")
                            for (doc in documents) {
                                if (!loaded) break
                                val id = doc.getString("id")
                                val keys = doc.getJSONObject("keys")
                                if (keys.optString(uid) != crypto.publicIdentity()) continue
                                var c = mutable.value.conversations.firstOrNull { it.id == id }
                                if (c == null) {
                                    val data =
                                        cloudApi.phoneAction(
                                            "chatInfo",
                                            JSONObject()
                                                .put("id", id)
                                                .put("key", crypto.publicIdentity()),
                                        )
                                    val peer = data.getString("peerUid")
                                    val key = data.getString("peerKey")
                                    require(keys.optString(peer) == key)
                                    crypto.sharedKey(key, id)
                                    c =
                                        VaultConversation(
                                            id = id,
                                            title = data.optString("phoneNumber", "Phone contact"),
                                            subtitle = "Phone chat · compare safety numbers",
                                            partnerUid = peer,
                                            partnerKey = key,
                                        )
                                    val incoming = c
                                    change { it.copy(conversations = it.conversations + incoming) }
                                }
                                require(c.partnerKey == keys.getString(c.partnerUid)) {
                                    "Pinned contact key changed"
                                }
                                syncConversation(c)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (loaded) error.value = e.localizedMessage ?: "Cloud sync unavailable"
                        }
                    }
                    delay(5_000)
                }
            }
    }

    private fun attach(c: VaultConversation) {
        if (loaded && uid != "local") connect()
    }

    private suspend fun syncConversation(c: VaultConversation) {
        val events =
            cloudApi.rows("rv_events", "&conversation_id=eq.${c.id}").map { d ->
                val sender = d.getString("sender")
                require(sender == uid || sender == c.partnerUid)
                val id = d.getString("id")
                val j =
                    JSONObject(
                        crypto
                            .decrypt(
                                crypto.sharedKey(c.partnerKey, c.id),
                                d.getString("envelope"),
                                "event:${c.id}:$id:$sender",
                            )
                            .toString(Charsets.UTF_8)
                    )
                VirtualDateEvent(
                    id,
                    j.getString("title"),
                    j.getString("date"),
                    j.getString("time"),
                    j.optString("note"),
                    c.id,
                )
            }
        change {
            it.copy(
                dates =
                    (it.dates.filterNot { old -> old.conversationId == c.id } + events).sortedBy { d
                        ->
                        d.date + d.time
                    }
            )
        }
        val rows =
            cloudApi.rows(
                "rv_messages",
                "&conversation_id=eq.${c.id}&seq=gt.${cursors[c.id]?:0}&order=seq.asc",
                paginate = false,
            )
        for (d in rows) {
            if (!loaded) return
            val seq = d.getLong("seq")
            val sender = d.getString("sender")
            val id = d.getString("id")
            if (sender != uid && id !in received) {
                require(sender == c.partnerUid) { "Message sender mismatch" }
                val m =
                    messageFromJson(
                        JSONObject(
                            crypto
                                .decrypt(
                                    crypto.sharedKey(c.partnerKey, c.id),
                                    d.getString("envelope"),
                                    "${c.id}:$id:$sender",
                                )
                                .toString(Charsets.UTF_8)
                        )
                    )
                require(m.id == id && m.senderId == sender && m.conversationId == c.id)
                val current = mutable.value.conversations.firstOrNull { it.id == c.id } ?: return
                if (
                    (!current.memoryFree || m.createdAt > current.memoryFreeCutoff) &&
                        (m.expiresAt == 0L || m.expiresAt > System.currentTimeMillis())
                ) {
                    change {
                        it.copy(
                            messages =
                                (it.messages.filterNot { old -> old.id == m.id } +
                                        m.copy(delivery = "Received"))
                                    .sortedBy { msg -> msg.createdAt }
                        )
                    }
                }
                received += id
            }
            cursors[c.id] = seq
        }
    }

    suspend fun importMedia(uri: Uri): VaultMedia =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name =
                resolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst())
                        it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    else "Private attachment"
                } ?: "Private attachment"
            val bytes =
                resolver.openInputStream(uri)!!.use { input ->
                    input.takeBounded(20 * 1024 * 1024 + 1)
                }
            require(bytes.size <= 20 * 1024 * 1024) { "Choose a file smaller than 20 MB" }
            val id = newId()
            val file = "media/$id.enc"
            File(context.filesDir, "media").mkdirs()
            File(context.filesDir, file).writeBytes(crypto.seal(bytes))
            val media =
                VaultMedia(
                    id,
                    name,
                    resolver.getType(uri) ?: "application/octet-stream",
                    file,
                    bytes.size.toLong(),
                )
            bytes.fill(0)
            withContext(Dispatchers.Main) {
                change { it.copy(media = it.media + media) }
                audit("Encrypted media added")
            }
            media
        }

    suspend fun openMedia(
        file: String,
        mime: String,
        conversationId: String = "",
        messageId: String = "",
    ): Uri =
        withContext(Dispatchers.IO) {
            val bytes =
                if (file.startsWith("media/") && file.count { it == '/' } == 2) {
                    val c = mutable.value.conversations.first { it.id == conversationId }
                    val cache = File(context.filesDir, "received/$messageId.enc")
                    val blob =
                        if (cache.exists()) cache.readBytes()
                        else {
                            require(internetAvailable()) {
                                "This attachment hasn't been downloaded yet. Connect once to save it offline."
                            }
                            cloudApi.download(file).also {
                                cache.parentFile!!.mkdirs()
                                cache.writeBytes(it)
                            }
                        }
                    crypto.decrypt(
                        crypto.sharedKey(c.partnerKey, c.id),
                        blob.toString(Charsets.UTF_8),
                        "media:$conversationId:$messageId",
                    )
                } else {
                    require(file.matches(Regex("media/[a-f0-9-]+[.]enc")))
                    crypto.unseal(File(context.filesDir, file).readBytes())
                }
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val target =
                File(
                    dir,
                    "${newId()}.${android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"}",
                )
            target.writeBytes(bytes)
            bytes.fill(0)
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                target,
            )
        }

    fun deleteMedia(id: String) {
        val selected = mutable.value.media.find { it.id == id }
        if (
            selected != null &&
                mutable.value.messages.any {
                    it.attachment == selected.file && it.delivery == "Pending"
                }
        ) {
            error.value =
                "This file is queued for delivery. Reconnect and deliver it before removing it."
            return
        }
        mutable.value.media.find { it.id == id }?.let { File(context.filesDir, it.file).delete() }
        change { it.copy(media = it.media.filterNot { m -> m.id == id }) }
        audit("Media removed from vault")
    }

    fun addDate(event: VirtualDateEvent) {
        change { it.copy(dates = (it.dates + event).sortedBy { d -> d.date + d.time }) }
        if (event.conversationId.isNotEmpty())
            launch {
                val c = mutable.value.conversations.first { it.id == event.conversationId }
                val j =
                    JSONObject()
                        .put("title", event.title)
                        .put("date", event.date)
                        .put("time", event.time)
                        .put("note", event.note)
                val envelope =
                    crypto.encrypt(
                        crypto.sharedKey(c.partnerKey, c.id),
                        j.toString().toByteArray(),
                        "event:${c.id}:${event.id}:$uid",
                    )
                cloudApi.insert(
                    "rv_events",
                    JSONObject()
                        .put("id", event.id)
                        .put("conversation_id", c.id)
                        .put("sender", uid)
                        .put("envelope", envelope),
                )
            }
    }

    fun deleteDate(id: String) {
        val event = mutable.value.dates.firstOrNull { it.id == id }
        change { it.copy(dates = it.dates.filterNot { d -> d.id == id }) }
        if (event != null && event.conversationId.isNotEmpty())
            launch {
                cloudApi.delete("rv_events", "id=eq.$id&conversation_id=eq.${event.conversationId}")
            }
    }

    fun nearbyConversation(peerKey: String, name: String): VaultConversation {
        check(loaded)
        crypto.sharedKey(peerKey, "validation")
        require(peerKey != crypto.publicIdentity()) { "Cannot pair this device with itself" }
        val id =
            "nearby-" +
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        listOf(crypto.publicIdentity(), peerKey)
                            .sorted()
                            .joinToString(":")
                            .toByteArray()
                    )
                    .joinToString("") { "%02x".format(it) }
        val peerId =
            "nearby-" +
                MessageDigest.getInstance("SHA-256")
                    .digest(peerKey.toByteArray())
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
        val existing = mutable.value.conversations.find { it.id == id && it.transport == "nearby" }
        if (existing != null) {
            require(existing.partnerKey == peerKey)
            return existing
        }
        val conversation =
            VaultConversation(
                id = id,
                title = name.take(48).ifBlank { "Nearby friend" },
                subtitle = "Nearby · no internet needed",
                partnerUid = peerId,
                partnerKey = peerKey,
                transport = "nearby",
            )
        change { it.copy(conversations = it.conversations + conversation) }
        return conversation
    }

    fun acceptNearby(c: VaultConversation, m: EncryptedMessage, mediaBytes: ByteArray? = null) {
        check(loaded)
        require(c.transport == "nearby" && m.conversationId == c.id && m.senderId == c.partnerUid)
        require(m.id.matches(Regex("[a-f0-9-]{36}")))
        require(m.createdAt <= System.currentTimeMillis() + 300_000) {
            "Check the time on both phones"
        }
        if (m.expiresAt > 0 && m.expiresAt <= System.currentTimeMillis()) return
        val current = mutable.value.conversations.first { it.id == c.id }
        if (current.memoryFree && m.createdAt <= current.memoryFreeCutoff) return
        if (mutable.value.messages.any { it.id == m.id }) return
        var message = m.copy(delivery = "Received nearby")
        if (mediaBytes != null) {
            require(mediaBytes.size <= 20 * 1024 * 1024)
            val path = "media/${m.id}.enc"
            File(context.filesDir, "media").mkdirs()
            File(context.filesDir, path).writeBytes(crypto.seal(mediaBytes))
            message = message.copy(attachment = path)
        }
        change { it.copy(messages = (it.messages + message).sortedBy { m -> m.createdAt }) }
    }

    fun acknowledgeNearby(conversationId: String, id: String) {
        if (!loaded) return
        change {
            it.copy(
                messages =
                    it.messages.map { m ->
                        if (
                            m.id == id &&
                                m.conversationId == conversationId &&
                                m.senderId == nearbyId &&
                                m.delivery == "Pending"
                        )
                            m.copy(delivery = "Delivered nearby")
                        else m
                    }
            )
        }
    }

    fun notifications(): List<VaultNotification> {
        check(loaded)
        return File(context.filesDir, "notification-inbox")
            .listFiles()
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                        require(file.length() < 2048)
                        val parts =
                            crypto.unseal(file.readBytes()).toString(Charsets.UTF_8).split(':')
                        require(parts.size == 2)
                        VaultNotification(file.nameWithoutExtension, parts[0], file.lastModified())
                    }
                    .getOrNull()
            }
    }

    fun clearNotifications() {
        check(loaded)
        File(context.filesDir, "notification-inbox").deleteRecursively()
        androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
    }

    fun clearCache() {
        File(context.cacheDir, "shared").deleteRecursively()
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.localizedMessage ?: "Operation failed"
            }
        }
    }
}

private fun java.io.InputStream.takeBounded(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < max) {
        val size = read(buffer, 0, minOf(buffer.size, max - output.size()))
        if (size < 0) break
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
