package com.example.nearby

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.repository.RoseVaultRepository
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await

data class NearbyPeer(val id: String, val name: String)

data class NearbyConfirmation(
    val id: String,
    val name: String,
    val digits: String,
    val accepted: Boolean = false,
)

data class NearbyState(
    val status: String = "Stopped",
    val peers: List<NearbyPeer> = emptyList(),
    val confirmation: NearbyConfirmation? = null,
    val connected: List<NearbyPeer> = emptyList(),
)

class NearbyChatEngine(private val context: Context, private val repo: RoseVaultRepository) {
    val state = MutableStateFlow(NearbyState())
    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val names = mutableMapOf<String, String>()
    private var alias = "RoseVault device"
    private var generation = 0
    private val protocol =
        NearbyChatProtocol(
            repo,
            { endpoint, bytes -> client.sendPayload(endpoint, Payload.fromBytes(bytes)).await() },
            { endpoint, c ->
                state.value =
                    state.value.copy(
                        connected =
                            (state.value.connected.filterNot { it.id == endpoint } +
                                NearbyPeer(endpoint, c.title)),
                        status = "Connected · works without internet",
                    )
            },
        )

    init {
        repo.nearbySender = { c, m, media -> protocol.send(c, m, media) }
    }

    fun isConversationConnected(id: String) = protocol.hasConversation(id)

    fun requiredPermissions(): Array<String> =
        buildList {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                else {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            .toTypedArray()

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.error.value = "Nearby: ${e.localizedMessage}"
                state.value = state.value.copy(status = "Connection needs attention")
            }
        }
    }

    fun start(name: String) {
        require(repo.loaded) { "Unlock first" }
        require(
            requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            "Allow nearby device permissions first"
        }
        require(
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                ConnectionResult.SUCCESS
        ) {
            "Update Google Play services to use Nearby chats"
        }
        val bluetooth = context.getSystemService(BluetoothManager::class.java).adapter
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        require(bluetooth?.isEnabled == true && wifi.isWifiEnabled) {
            "Turn on Bluetooth and Wi-Fi. An internet connection is not required."
        }
        stop()
        alias = name.trim().take(48).ifBlank { "RoseVault device" }
        state.value = NearbyState(status = "Starting…")
        val run = generation
        launch {
            client
                .startAdvertising(
                    alias,
                    SERVICE,
                    lifecycle,
                    AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
                )
                .await()
            if (run != generation) return@launch
            client
                .startDiscovery(
                    SERVICE,
                    discovery,
                    DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
                )
                .await()
            if (run == generation)
                state.value = state.value.copy(status = "Looking for nearby phones")
        }
    }

    fun connect(id: String) {
        if (!repo.loaded) return
        state.value = state.value.copy(status = "Requesting connection…")
        launch { client.requestConnection(alias, id, lifecycle).await() }
    }

    fun confirm(matches: Boolean) {
        val pending = state.value.confirmation ?: return
        if (!matches) {
            client.rejectConnection(pending.id)
            state.value = state.value.copy(confirmation = null, status = "Connection rejected")
            return
        }
        state.value =
            state.value.copy(
                confirmation = pending.copy(accepted = true),
                status = "Waiting for the other person",
            )
        launch { client.acceptConnection(pending.id, payload).await() }
    }

    fun stop() {
        generation++
        scope.coroutineContext.cancelChildren()
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        protocol.clear()
        names.clear()
        state.value = NearbyState()
    }

    private val discovery =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                if (!repo.loaded) return
                names[id] = info.endpointName.take(48)
                state.value =
                    state.value.copy(
                        peers =
                            state.value.peers.filterNot { it.id == id } +
                                NearbyPeer(id, info.endpointName.take(48))
                    )
            }

            override fun onEndpointLost(id: String) {
                state.value = state.value.copy(peers = state.value.peers.filterNot { it.id == id })
            }
        }
    private val lifecycle =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
                if (
                    !repo.loaded ||
                        state.value.status == "Stopped" ||
                        info.authenticationDigits.isBlank() ||
                        state.value.confirmation != null
                ) {
                    client.rejectConnection(id)
                    return
                }
                names[id] = info.endpointName.take(48)
                state.value =
                    state.value.copy(
                        confirmation =
                            NearbyConfirmation(
                                id,
                                info.endpointName.take(48),
                                info.authenticationDigits,
                            ),
                        status = "Compare the connection code",
                    )
            }

            override fun onConnectionResult(id: String, result: ConnectionResolution) {
                val confirmed = state.value.confirmation
                if (
                    result.status.isSuccess &&
                        confirmed?.id == id &&
                        confirmed.accepted &&
                        repo.loaded
                ) {
                    protocol.trust(id, names[id] ?: "Nearby friend")
                    state.value =
                        state.value.copy(confirmation = null, status = "Exchanging device keys…")
                    launch { protocol.hello(id, alias) }
                } else {
                    client.disconnectFromEndpoint(id)
                    state.value =
                        state.value.copy(confirmation = null, status = "Not connected · try again")
                }
            }

            override fun onDisconnected(id: String) {
                protocol.forget(id)
                state.value =
                    state.value.copy(
                        connected = state.value.connected.filterNot { it.id == id },
                        confirmation = state.value.confirmation?.takeUnless { it.id == id },
                        status = "Disconnected · messages will stay queued",
                    )
            }
        }
    private val payload =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val bytes = payload.asBytes() ?: return
                launch { protocol.receive(endpointId, bytes) }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate,
            ) {
                if (update.status == PayloadTransferUpdate.Status.FAILURE)
                    repo.error.value = "Nearby transfer interrupted. Your message remains queued."
            }
        }

    companion object {
        const val SERVICE = "com.aistudio.rosevault.offline.v1"
    }
}
