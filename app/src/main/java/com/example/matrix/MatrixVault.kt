package com.example.matrix

import android.content.Context
import android.util.AtomicFile
import com.example.BuildConfig
import com.example.security.CryptoEngine
import java.io.File
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_base.EncryptionState
import uniffi.matrix_sdk_crypto.CollectStrategy

/** SDK owns Olm/Megolm, SQLite persistence, local echoes, send queues and server sync. */
class MatrixVault(
    private val context: Context,
    private val crypto: CryptoEngine,
    private val roots: List<ByteArray> = emptyList(),
) {
    enum class VerificationPhase {
        IDLE,
        WAITING,
        INCOMING,
        READY,
        COMPARING,
        COMPARE,
        DONE,
        CANCELLED,
        FAILED,
    }

    data class RoomRow(
        val id: String,
        val title: String,
        val invited: Boolean,
        val encrypted: Boolean,
    )

    data class MessageRow(
        val key: String,
        val id: EventOrTransactionId,
        val sender: String,
        val body: String,
        val own: Boolean,
        val status: String,
        val media: MediaSource? = null,
        val mime: String = "application/octet-stream",
        val timestamp: ULong = 0u,
    )

    data class State(
        val user: String = "",
        val device: String = "",
        val rooms: List<RoomRow> = emptyList(),
        val messages: List<MessageRow> = emptyList(),
        val active: String? = null,
        val title: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val verification: String = "",
        val sas: String = "",
        val recoveryKey: String = "",
        val typing: List<String> = emptyList(),
        val verificationReady: Boolean = false,
        val verificationPhase: VerificationPhase = VerificationPhase.IDLE,
        val operation: String = "Matrix action",
    )

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var client: Client? = null
    private var sync: SyncService? = null
    private var roomList: RoomList? = null
    private var roomEntries: RoomListEntriesWithDynamicAdaptersResult? = null
    private var timeline: Timeline? = null
    private var room: Room? = null
    private var listener: TaskHandle? = null
    private var typingListener: TaskHandle? = null
    private var verification: SessionVerificationController? = null
    private var pendingVerification: SessionVerificationRequestDetails? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generation = 0
    private var metadata: JSONObject? = null
    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private var networkRegistered = false
    private val networkCallback =
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch { runCatching { setNetworkAvailable(true) } }
            }

            override fun onLost(network: android.net.Network) {
                scope.launch {
                    runCatching { setNetworkAvailable(connectivity.activeNetwork != null) }
                }
            }
        }

    internal suspend fun setNetworkAvailable(available: Boolean) {
        client?.enableAllSendQueues(available)
    }

    private val sessionFile
        get() = AtomicFile(File(context.filesDir, "matrix-session.enc"))

    private val items = mutableListOf<TimelineItem>()
    val hasSession
        get() = sessionFile.baseFile.exists()

    companion object {
        fun validateOrigin(
            origin: String,
            allowLocalHttp: Boolean = BuildConfig.LOCAL_MATRIX_DEV,
        ): String {
            val u = URI(origin.trim())
            val localHttp = allowLocalHttp && u.scheme == "http" && isPrivateIpv4(u.host.orEmpty())
            require(
                (u.scheme == "https" || localHttp) &&
                    !u.host.isNullOrBlank() &&
                    u.rawUserInfo == null &&
                    u.rawQuery == null &&
                    u.rawFragment == null &&
                    u.path in listOf("", "/")
            ) {
                if (allowLocalHttp)
                    "Use HTTPS, or a private IPv4 HTTP address for disposable local tests."
                else "Enter an HTTPS homeserver origin, for example https://matrix.example.org."
            }
            return u.toString().trimEnd('/')
        }

        private fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) })
                return false
            val ip = parts.map { it.toInt() }
            if (ip.any { it !in 0..255 }) return false
            return ip[0] == 10 ||
                ip[0] == 127 ||
                (ip[0] == 192 && ip[1] == 168) ||
                (ip[0] == 172 && ip[1] in 16..31)
        }

        fun validateUser(user: String) {
            require(user.matches(Regex("@[A-Za-z0-9._=/-]+:[A-Za-z0-9.:-]+"))) {
                "Use a full Matrix ID: @name:your-server."
            }
        }
    }

    @Synchronized
    private fun update(block: (State) -> State) {
        mutable.value = block(mutable.value)
    }

    fun clearError() {
        update { it.copy(error = null) }
    }

    fun run(action: suspend () -> Unit) {
        val epoch = generation
        scope.launch {
            update { it.copy(busy = true, error = null, operation = "Matrix action") }
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == generation)
                    update { it.copy(error = failureMessage(e, mutable.value.operation)) }
            } finally {
                if (epoch == generation) update { it.copy(busy = false) }
            }
        }
    }

    private fun failureMessage(error: Exception, operation: String): String {
        if (error is ClientException.MatrixApi) {
            return when (error.code) {
                "M_FORBIDDEN" ->
                    if (operation == "Sign-in")
                        "Sign-in rejected. Check the complete Matrix ID and matching password. [MX-AUTH]"
                    else "The server refused this action. [MX-FORBIDDEN]"
                "M_LIMIT_EXCEEDED" ->
                    "Too many attempts. Wait a little before trying again. [MX-RATE]"
                "M_UNKNOWN_TOKEN",
                "M_MISSING_TOKEN" ->
                    "The saved session is no longer accepted by the server. Keep your recovery key; do not clear app data. [MX-SESSION]"
                else ->
                    "$operation failed. Server response: ${error.code.takeIf { it.matches(Regex("M_[A-Z_]{1,60}")) } ?: "unrecognized"}."
            }
        }
        val type = error.javaClass.simpleName.filter { it.isLetterOrDigit() }.take(40)
        return "$operation failed [$type]. Tap Check server connection to test phone-to-server access. Queued messages stay in the SDK store."
    }

    @Synchronized
    private fun saveSession(session: Session) {
        val j = requireNotNull(metadata)
        require(!j.has("userId") || j.getString("userId") == session.userId) {
            "A different account cannot replace this Matrix session."
        }
        j.put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserver", session.homeserverUrl)
            .put("oauthData", session.oauthData)
            .put("slidingSync", session.slidingSyncVersion.name)
        val out = sessionFile.startWrite()
        try {
            out.write(crypto.seal(j.toString().toByteArray()))
            sessionFile.finishWrite(out)
        } catch (e: Exception) {
            sessionFile.failWrite(out)
            throw e
        }
    }

    private fun readSession(j: JSONObject): Session =
        Session(
            j.getString("accessToken"),
            j.optString("refreshToken").takeIf { it.isNotEmpty() },
            j.getString("userId"),
            j.getString("deviceId"),
            j.getString("homeserver"),
            j.optString("oauthData").takeIf { it.isNotEmpty() },
            SlidingSyncVersion.valueOf(j.getString("slidingSync")),
        )

    private suspend fun build(j: JSONObject, origin: String): Client {
        val dir = File(context.filesDir, "matrix/" + j.getString("store")).apply { mkdirs() }
        val builder =
            ClientBuilder()
                .homeserverUrl(validateOrigin(origin))
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                .disableWellKnownLookup(true)
                .sqliteStore(
                    SqliteStoreBuilder(dir.path, dir.path).passphrase(j.getString("storeKey"))
                )
                .roomKeyRecipientStrategy(CollectStrategy.ERROR_ON_VERIFIED_USER_PROBLEM)
                .autoEnableCrossSigning(true)
                .autoEnableBackups(false)
                .setSessionDelegate(
                    object : ClientSessionDelegate {
                        override fun retrieveSessionFromKeychain(userId: String): Session {
                            val saved = readSession(requireNotNull(metadata))
                            require(saved.userId == userId) { "Matrix session account mismatch." }
                            return saved
                        }

                        override fun saveSessionInKeychain(session: Session) {
                            saveSession(session)
                        }
                    }
                )
        return (if (roots.isNotEmpty())
                builder.addRootCertificates(roots).disableBuiltInRootCertificates()
            else builder)
            .build()
    }

    suspend fun login(origin: String, user: String, password: String) =
        mutex.withLock {
            require(!hasSession) {
                "Restore the existing account on this vault before signing into another account."
            }
            validateUser(user)
            val key =
                ByteArray(32)
                    .also { SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it) }
            val j =
                JSONObject()
                    .put("store", java.util.UUID.randomUUID().toString())
                    .put("storeKey", key)
            metadata = j
            update { it.copy(operation = "Matrix client setup") }
            val c = build(j, origin)
            try {
                update { it.copy(operation = "Sign-in") }
                c.login(user, password, "RoseVault Android", null)
                saveSession(c.session())
                client = c
                update { it.copy(operation = "Encrypted sync setup") }
                activate(c)
            } catch (e: Exception) {
                client = null
                c.close()
                throw e
            }
        }

    suspend fun restore() =
        mutex.withLock {
            if (client != null || !hasSession) return@withLock
            val j = JSONObject(crypto.unseal(sessionFile.readFully()).toString(Charsets.UTF_8))
            metadata = j
            val c = build(j, j.getString("homeserver"))
            try {
                c.restoreSession(readSession(j))
                client = c
                activate(c)
            } catch (e: Exception) {
                c.close()
                throw e
            }
        }

    private suspend fun activate(c: Client) {
        update { it.copy(user = c.userId(), device = c.deviceId()) }
        sync = c.syncService().withOfflineMode().finish().also { it.start() }
        roomList = sync?.roomListService()?.allRooms()
        roomEntries =
            roomList?.entriesWithDynamicAdapters(
                50u,
                object : RoomListEntriesListener {
                    override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                        refreshRooms()
                    }
                },
            )
        roomEntries
            ?.controller()
            ?.setFilter(
                RoomListEntriesDynamicFilterKind.All(
                    listOf(
                        RoomListEntriesDynamicFilterKind.NonLeft,
                        RoomListEntriesDynamicFilterKind.NonSpace,
                    )
                )
            )
        c.enableAllSendQueues(connectivity.activeNetwork != null)
        if (!networkRegistered) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
        }
        c.encryption().waitForE2eeInitializationTasks()
        attachVerification(c)
        scope.launch {
            while (isActive) {
                refreshRooms()
                delay(2000)
            }
        }
    }

    private suspend fun attachVerification(c: Client) {
        verification?.setDelegate(null)
        verification?.close()
        pendingVerification = null
        update { it.copy(verificationPhase = VerificationPhase.IDLE) }
        val epoch = generation
        verification =
            runCatching { c.getSessionVerificationController() }
                .getOrNull()
                ?.also { controller ->
                    controller.setDelegate(
                        object : SessionVerificationControllerDelegate {
                            override fun didReceiveVerificationRequest(
                                details: SessionVerificationRequestDetails
                            ) {
                                if (epoch != generation) return
                                pendingVerification = details
                                update {
                                    it.copy(
                                        verificationPhase = VerificationPhase.INCOMING,
                                        verification =
                                            "Verification requested by ${details.senderProfile.userId}",
                                        sas = "",
                                    )
                                }
                            }

                            override fun didAcceptVerificationRequest() {
                                if (epoch == generation)
                                    update {
                                        it.copy(
                                            verificationPhase = VerificationPhase.READY,
                                            verification = "Ready to compare",
                                            sas = "",
                                        )
                                    }
                            }

                            override fun didStartSasVerification() {
                                if (epoch == generation)
                                    update {
                                        it.copy(
                                            verificationPhase = VerificationPhase.COMPARING,
                                            verification = "Preparing secure comparison…",
                                        )
                                    }
                            }

                            override fun didReceiveVerificationData(data: SessionVerificationData) {
                                if (epoch != generation) return
                                val sequence =
                                    when (data) {
                                        is SessionVerificationData.Decimals ->
                                            data.values.joinToString(" · ")
                                        is SessionVerificationData.Emojis ->
                                            data.emojis.joinToString(" ") { it.symbol() }
                                    }
                                update {
                                    it.copy(
                                        verificationPhase = VerificationPhase.COMPARE,
                                        sas = sequence,
                                        verification =
                                            "Compare with the other person through a trusted channel.",
                                    )
                                }
                            }

                            override fun didFail() {
                                if (epoch != generation) return
                                pendingVerification = null
                                update {
                                    it.copy(
                                        verificationPhase = VerificationPhase.FAILED,
                                        verification =
                                            "Verification failed. Do not approve a mismatch.",
                                        sas = "",
                                    )
                                }
                            }

                            override fun didCancel() {
                                if (epoch != generation) return
                                pendingVerification = null
                                update {
                                    it.copy(
                                        verificationPhase = VerificationPhase.CANCELLED,
                                        verification = "Verification cancelled",
                                        sas = "",
                                    )
                                }
                            }

                            override fun didFinish() {
                                if (epoch != generation) return
                                pendingVerification = null
                                update {
                                    it.copy(
                                        verificationPhase = VerificationPhase.DONE,
                                        verification = "Verification completed",
                                        sas = "",
                                    )
                                }
                            }
                        }
                    )
                }
        update {
            it.copy(
                verificationReady = verification != null,
                verification =
                    if (verification == null)
                        "Device identity is not ready. Recover keys or verify this account using a trusted device."
                    else it.verification,
            )
        }
    }

    private fun refreshRooms() {
        val c = client ?: return
        update {
            it.copy(
                rooms =
                    c.rooms()
                        .filter { r ->
                            r.membership() in listOf(Membership.JOINED, Membership.INVITED)
                        }
                        .map { r ->
                            RoomRow(
                                r.id(),
                                r.displayName() ?: "Private room",
                                r.membership() == Membership.INVITED,
                                r.encryptionState() == EncryptionState.ENCRYPTED,
                            )
                        }
            )
        }
    }

    suspend fun createDm(user: String): String {
        validateUser(user)
        val c = requireNotNull(client)
        require(user != c.userId())
        c.getDmRooms(user)
            .firstOrNull {
                it.encryptionState() == EncryptionState.ENCRYPTED &&
                    it.membership() == Membership.JOINED
            }
            ?.let {
                return it.id()
            }
        return c.createRoom(
            CreateRoomParameters(
                name = null,
                isEncrypted = true,
                isDirect = true,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PRIVATE_CHAT,
                invite = listOf(user),
                historyVisibilityOverride = RoomHistoryVisibility.Joined,
            )
        )
    }

    suspend fun accept(roomId: String) {
        requireNotNull(client).getRoom(roomId)?.join()
        refreshRooms()
    }

    suspend fun decline(roomId: String) {
        requireNotNull(client).getRoom(roomId)?.leave()
        refreshRooms()
    }

    suspend fun openRoom(roomId: String) {
        typingListener?.cancel()
        typingListener = null
        listener?.cancel()
        listener = null
        timeline?.close()
        timeline = null
        items.clear()
        val r =
            requireNotNull(client).getRoom(roomId)
                ?: withTimeout(30000) { requireNotNull(client).awaitRoomRemoteEcho(roomId) }
        require(r.encryptionState() == EncryptionState.ENCRYPTED) {
            "Only encrypted rooms can be opened in RoseVault."
        }
        require(r.membership() == Membership.JOINED)
        require(r.activeMembersCount() <= 2uL) { "This pilot opens two-person rooms only." }
        room = r
        update {
            it.copy(
                active = roomId,
                title = r.displayName() ?: "Private room",
                messages = emptyList(),
            )
        }
        val t =
            r.timelineWithConfiguration(
                TimelineConfiguration(
                    TimelineFocus.Live(false),
                    TimelineFilter.All,
                    null,
                    DateDividerMode.DAILY,
                    uniffi.matrix_sdk_ui.TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS,
                    false,
                )
            )
        timeline = t
        typingListener?.cancel()
        typingListener =
            r.subscribeToTypingNotifications(
                object : TypingNotificationsListener {
                    override fun call(typingUserIds: List<String>) {
                        if (mutable.value.active == roomId)
                            update { it.copy(typing = typingUserIds.filter { u -> u != cUser() }) }
                    }
                }
            )
        val epoch = generation
        listener =
            t.addListener(
                object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) {
                        if (epoch != generation || mutable.value.active != roomId) return
                        synchronized(items) {
                            if (epoch != generation || mutable.value.active != roomId) return
                            diff.forEach { d ->
                                when (d) {
                                    is TimelineDiff.Append -> items.addAll(d.values)
                                    is TimelineDiff.Clear -> items.clear()
                                    is TimelineDiff.PushFront -> items.add(0, d.value)
                                    is TimelineDiff.PushBack -> items.add(d.value)
                                    is TimelineDiff.PopFront ->
                                        if (items.isNotEmpty()) items.removeAt(0) else Unit
                                    is TimelineDiff.PopBack ->
                                        if (items.isNotEmpty()) items.removeAt(items.lastIndex)
                                        else Unit
                                    is TimelineDiff.Insert -> items.add(d.index.toInt(), d.value)
                                    is TimelineDiff.Set -> items[d.index.toInt()] = d.value
                                    is TimelineDiff.Remove -> items.removeAt(d.index.toInt())
                                    is TimelineDiff.Truncate ->
                                        while (items.size > d.length.toInt()) items.removeAt(
                                            items.lastIndex
                                        )
                                    is TimelineDiff.Reset -> {
                                        items.clear()
                                        items.addAll(d.values)
                                    }
                                }
                            }
                            val rows =
                                items.mapNotNull { item ->
                                    item.asEvent()?.let { e ->
                                        val kind =
                                            (e.content as? TimelineItemContent.MsgLike)
                                                ?.content
                                                ?.kind ?: return@let null
                                        val message = (kind as? MsgLikeKind.Message)?.content
                                        val attachment =
                                            (message?.msgType as? MessageType.File)?.content
                                        MessageRow(
                                            item.uniqueId().toString(),
                                            e.eventOrTransactionId,
                                            e.sender,
                                            message?.body
                                                ?: if (kind is MsgLikeKind.UnableToDecrypt)
                                                    "Unable to decrypt · verify/recover this device"
                                                else if (kind is MsgLikeKind.Redacted)
                                                    "Message removed"
                                                else "Encrypted event",
                                            e.isOwn,
                                            if (
                                                e.isOwn &&
                                                    e.readReceipts.keys.any { it != e.sender }
                                            )
                                                "Read"
                                            else
                                                when (e.localSendState) {
                                                    is EventSendState.NotSentYet -> "Queued"
                                                    is EventSendState.SendingFailed ->
                                                        "Needs retry / verification"
                                                    is EventSendState.Sent -> "Sent"
                                                    else -> if (e.isOwn) "Sent" else "Received"
                                                },
                                            attachment?.source,
                                            attachment?.info?.mimetype
                                                ?: "application/octet-stream",
                                            e.timestamp,
                                        )
                                    }
                                }
                            if (epoch == generation && mutable.value.active == roomId)
                                update { it.copy(messages = rows) }
                        }
                    }
                }
            )
    }

    fun back() {
        typingListener?.cancel()
        typingListener = null
        listener?.cancel()
        listener = null
        timeline?.close()
        timeline = null
        room = null
        synchronized(items) { items.clear() }
        update { it.copy(active = null, messages = emptyList(), title = "") }
    }

    suspend fun send(text: String) {
        require(text.isNotBlank() && text.toByteArray().size <= 16000)
        require(requireNotNull(room).encryptionState() == EncryptionState.ENCRYPTED)
        require(requireNotNull(room).activeMembersCount() <= 2uL) {
            "Room membership changed. Review it before sending."
        }
        val t = requireNotNull(timeline)
        t.send(
            requireNotNull(t.createMessageContent(MessageType.Text(TextMessageContent(text, null))))
        )
    }

    suspend fun sendFile(bytes: ByteArray, name: String, mime: String) {
        require(bytes.size <= 20 * 1024 * 1024) { "Keep files under 20 MB." }
        require(requireNotNull(room).encryptionState() == EncryptionState.ENCRYPTED)
        require(requireNotNull(room).activeMembersCount() <= 2uL) {
            "Room membership changed. Review it before sending."
        }
        val handle =
            requireNotNull(timeline)
                .sendAttachment(
                    UploadParameters(
                        UploadSource.Data(bytes, name.take(120)),
                        null,
                        null,
                        null,
                        null,
                    ),
                    AttachmentKind.File(FileInfo(mime, bytes.size.toULong(), null, null)),
                )
        scope.launch {
            try {
                handle.join()
            } finally {
                handle.close()
            }
        }
    }

    suspend fun download(source: MediaSource): ByteArray {
        val description = runCatching { JSONObject(source.toJson()) }.getOrNull()
        val encrypted =
            description?.optJSONObject("file")
                ?: description?.optJSONObject("Encrypted")
                ?: description?.optJSONObject("encrypted")
                ?: description
        require(
            encrypted != null &&
                encrypted.has("key") &&
                encrypted.has("iv") &&
                encrypted.has("hashes")
        ) {
            "This attachment is not encrypted; RoseVault will not open it."
        }
        val bytes = requireNotNull(client).getMediaContent(source)
        require(bytes.size <= 20 * 1024 * 1024) { "The attachment exceeds this app's 20 MB limit." }
        return bytes
    }

    suspend fun edit(id: EventOrTransactionId, body: String) {
        val t = requireNotNull(timeline)
        require(body.isNotBlank() && body.toByteArray().size <= 16000)
        t.edit(
            id,
            EditedContent.RoomMessage(
                requireNotNull(
                    t.createMessageContent(MessageType.Text(TextMessageContent(body, null)))
                )
            ),
        )
    }

    suspend fun paginate() {
        timeline?.paginateBackwards(30u)
    }

    suspend fun markRead() {
        timeline?.markAsRead(ReceiptType.READ)
    }

    private fun cUser() = client?.userId().orEmpty()

    fun typingHint(value: Boolean) {
        scope.launch { runCatching { room?.typingNotice(value) } }
    }

    fun readHint() {
        scope.launch { runCatching { timeline?.markAsRead(ReceiptType.READ) } }
    }

    suspend fun typing(value: Boolean) {
        room?.typingNotice(value)
    }

    suspend fun react(id: EventOrTransactionId, emoji: String) {
        timeline?.toggleReaction(id, emoji)
    }

    suspend fun redact(id: EventOrTransactionId) {
        timeline?.redactEvent(id, "Removed by sender")
    }

    suspend fun retry() {
        requireNotNull(client).enableAllSendQueues(true)
    }

    suspend fun requestVerification(user: String) {
        validateUser(user)
        val identity = requireNotNull(client).encryption().userIdentity(user, true)
        requireNotNull(identity) { "The other person's encryption identity is not ready." }
        identity.close()
        requireNotNull(verification) {
                "Device identity is not ready. Restore or recover it first."
            }
            .requestUserVerification(user)
        update {
            it.copy(
                verificationPhase = VerificationPhase.WAITING,
                verification = "Verification request sent. Ask the other person to compare.",
            )
        }
    }

    suspend fun acceptVerification() {
        val request =
            requireNotNull(pendingVerification) { "There is no incoming verification request." }
        requireNotNull(client)
            .encryption()
            .userIdentity(request.senderProfile.userId, true)
            ?.close()
        val controller = requireNotNull(verification)
        controller.acknowledgeVerificationRequest(request.senderProfile.userId, request.flowId)
        controller.acceptVerificationRequest()
        pendingVerification = null
        controller.startSasVerification()
    }

    suspend fun startVerification() {
        requireNotNull(verification).startSasVerification()
    }

    suspend fun approveVerification() {
        require(mutable.value.sas.isNotEmpty())
        requireNotNull(verification).approveVerification()
    }

    suspend fun cancelVerification() {
        val controller = requireNotNull(verification)
        val request = pendingVerification
        if (request != null) {
            controller.acknowledgeVerificationRequest(request.senderProfile.userId, request.flowId)
            controller.declineVerification()
            pendingVerification = null
        } else controller.cancelVerification()
    }

    suspend fun enableRecovery() {
        val key =
            requireNotNull(client)
                .encryption()
                .enableRecovery(
                    false,
                    null,
                    object : EnableRecoveryProgressListener {
                        override fun onUpdate(status: EnableRecoveryProgress) {}
                    },
                )
        attachVerification(requireNotNull(client))
        update { it.copy(recoveryKey = key) }
    }

    fun hideRecoveryKey() {
        update { it.copy(recoveryKey = "") }
    }

    suspend fun awaitBackupUpload() {
        requireNotNull(client).encryption().waitForBackupUploadSteadyState(null)
    }

    suspend fun recover(key: String) {
        requireNotNull(client).encryption().recover(key)
        attachVerification(requireNotNull(client))
        update {
            it.copy(verification = "Recovery completed. Historical keys may continue downloading.")
        }
    }

    suspend fun pause() {
        mutex.withLock {
            generation++
            if (networkRegistered) {
                connectivity.unregisterNetworkCallback(networkCallback)
                networkRegistered = false
            }
            client?.enableAllSendQueues(false)
            mutable.value = State()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            listener?.cancel()
            listener = null
            typingListener?.cancel()
            typingListener = null
            verification?.setDelegate(null)
            verification?.close()
            verification = null
            pendingVerification = null
            roomEntries?.entriesStream()?.cancel()
            roomEntries?.close()
            roomEntries = null
            roomList?.close()
            roomList = null
            sync?.stop()
            sync?.close()
            sync = null
            timeline?.close()
            timeline = null
            room = null
            synchronized(items) { items.clear() }
            client?.pause()
            client?.close()
            client = null
            metadata = null
            mutable.value = State()
        }
    }
}
