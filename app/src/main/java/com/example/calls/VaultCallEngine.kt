package com.example.calls

import android.content.Context
import android.media.AudioManager
import com.example.data.model.VaultConversation
import com.example.data.repository.RoseVaultRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.webrtc.*

data class CallState(
    val conversation: VaultConversation? = null,
    val id: String = "",
    val status: String = "Idle",
    val video: Boolean = false,
    val incoming: Boolean = false,
    val muted: Boolean = false,
    val cameraOff: Boolean = false,
)

class VaultCallEngine(private val context: Context, private val repository: RoseVaultRepository) {
    val state = MutableStateFlow(CallState())
    val egl: EglBase = EglBase.create()
    val localVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo = MutableStateFlow<VideoTrack?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audio: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var capturer: VideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private val listeners = mutableListOf<Job>()
    private val watches = mutableListOf<Job>()
    private val seen = mutableSetOf<String>()
    private val queued = mutableListOf<IceCandidate>()
    private var remoteReady = false
    private var remoteSdpSet = false
    private val api
        get() = repository.cloudApi

    private var callJob: Job? = null

    fun watch(conversations: List<VaultConversation>) {
        watches.forEach { it.cancel() }
        watches.clear()
        if (
            repository.uid == "local" ||
                conversations.none { it.partnerUid.isNotEmpty() && it.transport == "cloud" }
        )
            return
        watches +=
            scope.launch {
                while (isActive) {
                    if (repository.online.value && state.value.status in listOf("Idle", "Ended")) {
                        try {
                            val calls =
                                api.rows(
                                    "rv_calls",
                                    "&to_uid=eq.${repository.uid}&status=eq.ringing&created_at=gt.${System.currentTimeMillis()-60_000}",
                                )
                            val d =
                                calls.firstOrNull { row ->
                                    conversations.any { it.id == row.getString("conversation_id") }
                                }
                            if (d != null) {
                                val c =
                                    conversations.first { it.id == d.getString("conversation_id") }
                                require(d.getString("from_uid") == c.partnerUid)
                                state.value =
                                    CallState(
                                        c,
                                        d.getString("id"),
                                        "Incoming call",
                                        d.getBoolean("video"),
                                        true,
                                    )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            repository.error.value = e.localizedMessage
                        }
                    }
                    delay(2_000)
                }
            }
    }

    fun start(c: VaultConversation, video: Boolean) {
        run {
            require(
                c.partnerUid.isNotEmpty() && c.transport == "cloud" && repository.uid != "local"
            ) {
                "Pair a signed-in contact to place a call"
            }
            require(state.value.status in listOf("Idle", "Ended")) { "End your current call first" }
            state.value = CallState(c, java.util.UUID.randomUUID().toString(), "Connecting", video)
            callJob =
                scope.launch {
                    try {
                        prepare(video)
                        val offer = createSdp(true)
                        peer!!.setLocal(offer)
                        val encrypted =
                            encrypt(
                                JSONObject()
                                    .put("type", offer.type.canonicalForm())
                                    .put("sdp", offer.description)
                                    .toString()
                            )
                        api.insert(
                            "rv_calls",
                            JSONObject()
                                .put("id", state.value.id)
                                .put("conversation_id", c.id)
                                .put("from_uid", repository.uid)
                                .put("to_uid", c.partnerUid)
                                .put("status", "ringing")
                                .put("video", video)
                                .put("created_at", System.currentTimeMillis())
                                .put("offer", encrypted),
                        )
                        state.value = state.value.copy(status = "Ringing")
                        listen()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fail(e)
                    }
                }
        }
    }

    fun accept() {
        callJob =
            scope.launch {
                try {
                    state.value = state.value.copy(status = "Connecting")
                    val d =
                        api.rows("rv_calls", "&id=eq.${state.value.id}").firstOrNull()
                            ?: error("Call is unavailable")
                    require(
                        d.getString("status") == "ringing" &&
                            d.getLong("created_at") > System.currentTimeMillis() - 60_000
                    ) {
                        "Call has expired"
                    }
                    prepare(state.value.video)
                    val offer = JSONObject(decrypt(d.getString("offer")!!))
                    peer!!.setRemote(
                        SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(offer.getString("type")),
                            offer.getString("sdp"),
                        )
                    )
                    remoteReady = true
                    remoteSdpSet = true
                    val answer = createSdp(false)
                    peer!!.setLocal(answer)
                    api.patch(
                        "rv_calls",
                        "id=eq.${state.value.id}",
                        JSONObject()
                            .put(
                                "answer",
                                encrypt(
                                    JSONObject()
                                        .put("type", answer.type.canonicalForm())
                                        .put("sdp", answer.description)
                                        .toString()
                                ),
                            )
                            .put("status", "accepted"),
                    )
                    listen()
                } catch (e: Exception) {
                    fail(e)
                }
            }
    }

    private suspend fun prepare(video: Boolean) {
        val result = withTimeout(15_000) { api.function("turn-credentials") }
        val urls =
            (0 until result.getJSONArray("urls").length()).map {
                result.getJSONArray("urls").getString(it)
            }
        require(
            urls.isNotEmpty() && urls.all { it.startsWith("turn:") || it.startsWith("turns:") }
        ) {
            "TURN relay is not configured"
        }
        val ice =
            PeerConnection.IceServer.builder(urls)
                .setUsername(result.getString("username"))
                .setPassword(result.getString("credential"))
                .createIceServer()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        factory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
        val config =
            PeerConnection.RTCConfiguration(listOf(ice)).apply {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
        val expectedCallId = state.value.id
        peer =
            factory!!.createPeerConnection(
                config,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        scope.launch {
                            try {
                                // SDP and ICE data are encrypted with the paired conversation key.
                                while (
                                    state.value.id == expectedCallId &&
                                        state.value.status == "Connecting" &&
                                        !state.value.incoming
                                ) delay(100)
                                if (
                                    state.value.id != expectedCallId ||
                                        state.value.status in
                                            listOf("Idle", "Ended", "Call unavailable")
                                )
                                    return@launch
                                api.insert(
                                    "rv_candidates",
                                    JSONObject()
                                        .put("id", java.util.UUID.randomUUID().toString())
                                        .put("call_id", state.value.id)
                                        .put("sender", repository.uid)
                                        .put(
                                            "payload",
                                            encrypt(
                                                JSONObject()
                                                    .put("mid", candidate.sdpMid)
                                                    .put("index", candidate.sdpMLineIndex)
                                                    .put("sdp", candidate.sdp)
                                                    .toString()
                                            ),
                                        ),
                                )
                            } catch (e: Exception) {
                                repository.error.value = "ICE signaling failed"
                            }
                        }
                    }

                    override fun onConnectionChange(
                        connection: PeerConnection.PeerConnectionState
                    ) {
                        scope.launch {
                            when (connection) {
                                PeerConnection.PeerConnectionState.CONNECTED ->
                                    state.value =
                                        state.value.copy(status = "Connected · encrypted transport")
                                PeerConnection.PeerConnectionState.FAILED ->
                                    end("Connection failed")
                                else -> Unit
                            }
                        }
                    }

                    override fun onAddTrack(
                        receiver: RtpReceiver,
                        streams: Array<out MediaStream>,
                    ) {
                        (receiver.track() as? VideoTrack)?.let { remoteVideo.value = it }
                    }

                    override fun onSignalingChange(s: PeerConnection.SignalingState) {}

                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

                    override fun onAddStream(stream: MediaStream) {}

                    override fun onRemoveStream(stream: MediaStream) {}

                    override fun onDataChannel(channel: DataChannel) {}

                    override fun onRenegotiationNeeded() {}
                },
            ) ?: error("Cannot initialize WebRTC")
        audioSource = factory!!.createAudioSource(MediaConstraints())
        audio = factory!!.createAudioTrack("audio", audioSource)
        audio?.setEnabled(!state.value.muted)
        peer!!.addTrack(audio, listOf("rosevault"))
        if (video) {
            val enumerator = Camera2Enumerator(context)
            val name =
                enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.first()
            capturer = enumerator.createCapturer(name, null)
            videoSource = factory!!.createVideoSource(false)
            texture = SurfaceTextureHelper.create("VaultCamera", egl.eglBaseContext)
            capturer!!.initialize(texture, context, videoSource!!.capturerObserver)
            if (!state.value.cameraOff) capturer!!.startCapture(640, 480, 24)
            localVideo.value = factory!!.createVideoTrack("video", videoSource)
            localVideo.value?.setEnabled(!state.value.cameraOff)
            peer!!.addTrack(localVideo.value, listOf("rosevault"))
        }
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode =
            AudioManager.MODE_IN_COMMUNICATION
    }

    private fun listen() {
        val callId = state.value.id
        listeners +=
            scope.launch {
                while (isActive && state.value.id == callId) {
                    try {
                        val d =
                            api.rows("rv_calls", "&id=eq.$callId").firstOrNull()
                                ?: error("Call is unavailable")
                        if (d.getString("status") == "ended") {
                            cleanup()
                            state.value = state.value.copy(status = "Ended")
                            return@launch
                        }
                        if (!state.value.incoming && !remoteSdpSet && !d.isNull("answer")) {
                            val j = JSONObject(decrypt(d.getString("answer")))
                            peer!!.setRemote(
                                SessionDescription(
                                    SessionDescription.Type.fromCanonicalForm(j.getString("type")),
                                    j.getString("sdp"),
                                )
                            )
                            remoteSdpSet = true
                            remoteReady = true
                            queued.forEach { peer?.addIceCandidate(it) }
                            queued.clear()
                        }
                        api.rows("rv_candidates", "&call_id=eq.$callId")
                            .filter {
                                it.getString("sender") == state.value.conversation?.partnerUid &&
                                    it.getString("id") !in seen
                            }
                            .forEach { row ->
                                val j = JSONObject(decrypt(row.getString("payload")))
                                val candidate =
                                    IceCandidate(
                                        j.getString("mid"),
                                        j.getInt("index"),
                                        j.getString("sdp"),
                                    )
                                if (remoteReady) peer?.addIceCandidate(candidate)
                                else queued += candidate
                                seen += row.getString("id")
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fail(e)
                        return@launch
                    }
                    delay(2_000)
                }
            }
    }

    private fun encrypt(text: String): String {
        val c = state.value.conversation!!
        return repository.crypto.encrypt(
            repository.crypto.sharedKey(c.partnerKey, c.id),
            text.toByteArray(),
            "call:${c.id}:${state.value.id}",
        )
    }

    private fun decrypt(text: String): String {
        val c = state.value.conversation!!
        return repository.crypto
            .decrypt(
                repository.crypto.sharedKey(c.partnerKey, c.id),
                text,
                "call:${c.id}:${state.value.id}",
            )
            .toString(Charsets.UTF_8)
    }

    fun mute() {
        state.value = state.value.copy(muted = !state.value.muted)
        audio?.setEnabled(!state.value.muted)
    }

    fun camera() {
        state.value = state.value.copy(cameraOff = !state.value.cameraOff)
        localVideo.value?.setEnabled(!state.value.cameraOff)
        runCatching {
                if (state.value.cameraOff) capturer?.stopCapture()
                else capturer?.startCapture(640, 480, 24)
            }
            .onFailure {
                repository.error.value = "Camera could not be changed: ${it.localizedMessage}"
            }
    }

    fun end(reason: String = "Ended") {
        val id = state.value.id
        if (id.isNotEmpty())
            scope.launch {
                runCatching {
                    api.patch("rv_calls", "id=eq.$id", JSONObject().put("status", "ended"))
                }
            }
        cleanup()
        state.value = state.value.copy(status = reason)
    }

    fun dismiss() {
        end()
        state.value = CallState()
    }

    fun stopWatching() {
        watches.forEach { it.cancel() }
        watches.clear()
        if (state.value.status != "Idle") dismiss()
    }

    private fun cleanup() {
        callJob?.cancel()
        callJob = null
        listeners.forEach { it.cancel() }
        listeners.clear()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        localVideo.value?.dispose()
        localVideo.value = null
        remoteVideo.value = null
        videoSource?.dispose()
        videoSource = null
        texture?.dispose()
        texture = null
        audio?.dispose()
        audio = null
        audioSource?.dispose()
        audioSource = null
        peer?.close()
        peer?.dispose()
        peer = null
        factory?.dispose()
        factory = null
        seen.clear()
        queued.clear()
        remoteReady = false
        remoteSdpSet = false
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode =
            AudioManager.MODE_NORMAL
    }

    private fun fail(e: Exception) {
        repository.error.value = e.localizedMessage ?: "Call failed"
        end("Call unavailable")
    }

    private suspend fun createSdp(offer: Boolean): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer =
                object : SdpObserver {
                    override fun onCreateSuccess(s: SessionDescription) {
                        if (continuation.isActive) continuation.resume(s)
                    }

                    override fun onCreateFailure(message: String) {
                        if (continuation.isActive)
                            continuation.resumeWithException(IllegalStateException(message))
                    }

                    override fun onSetSuccess() {}

                    override fun onSetFailure(message: String) {}
                }
            if (offer) peer!!.createOffer(observer, MediaConstraints())
            else peer!!.createAnswer(observer, MediaConstraints())
        }

    private suspend fun PeerConnection.setLocal(s: SessionDescription) = setDescription(s, true)

    private suspend fun PeerConnection.setRemote(s: SessionDescription) = setDescription(s, false)

    private suspend fun PeerConnection.setDescription(s: SessionDescription, local: Boolean): Unit =
        suspendCancellableCoroutine { continuation ->
            val o =
                object : SdpObserver {
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onSetFailure(message: String) {
                        if (continuation.isActive)
                            continuation.resumeWithException(IllegalStateException(message))
                    }

                    override fun onCreateSuccess(s: SessionDescription) {}

                    override fun onCreateFailure(message: String) {}
                }
            if (local) setLocalDescription(o, s) else setRemoteDescription(o, s)
        }
}
