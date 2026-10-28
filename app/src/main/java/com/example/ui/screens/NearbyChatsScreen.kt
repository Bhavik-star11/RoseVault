package com.example.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.ui.theme.*

@Composable
fun NearbyChatsDialog(activity: MainActivity, onDismiss: () -> Unit) {
    val engine = activity.nearby
    val state by engine.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("My RoseVault") }
    var problem by remember { mutableStateOf<String?>(null) }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            result ->
            if (result.isNotEmpty() && result.values.all { it })
                runCatching { engine.start(name) }.onFailure { problem = it.localizedMessage }
            else
                problem =
                    "Nearby chats need Bluetooth and nearby-device permissions. You can allow them in Android App settings."
        }
    fun start() {
        val required = engine.requiredPermissions()
        if (
            required.all {
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            }
        )
            runCatching { engine.start(name) }.onFailure { problem = it.localizedMessage }
        else permissions.launch(required)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nearby chats") },
        text = {
            Column(
                Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Message nearby phones without internet or Google sign-in. Both phones need RoseVault open, with Bluetooth and Wi-Fi turned on."
                )
                OutlinedTextField(
                    name,
                    { name = it.take(48) },
                    label = { Text("Name visible to nearby people") },
                    singleLine = true,
                )
                Text(state.status, color = BlushPink)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.status == "Stopped" || state.status == "Connection needs attention")
                    Button(onClick = ::start, enabled = name.isNotBlank()) {
                        Text("Find nearby phones")
                    }
                if (state.peers.isEmpty() && state.connected.isEmpty())
                    Text(
                        "On the other phone, open Nearby chats and tap Find nearby phones too.",
                        color = MutedRoseGray,
                    )
                state.peers
                    .filter { p -> state.connected.none { it.id == p.id } }
                    .forEach { peer ->
                        OutlinedButton(
                            onClick = { engine.connect(peer.id) },
                            enabled = state.confirmation == null,
                        ) {
                            Text("Connect to ${peer.name}")
                        }
                    }
                state.connected.forEach { peer ->
                    Text("Connected: ${peer.name}", color = EmeraldEncrypted)
                }
                TextButton(
                    onClick = { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                ) {
                    Text("Bluetooth & Wi-Fi settings")
                }
                TextButton(
                    onClick = {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${activity.packageName}"),
                            )
                        )
                    }
                ) {
                    Text("App permissions")
                }
                if (state.status != "Stopped")
                    TextButton(onClick = engine::stop) { Text("Stop Nearby") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Back to chats") } },
    )
}

@Composable
fun NearbyConfirmationDialog(activity: MainActivity) {
    val state by activity.nearby.state.collectAsStateWithLifecycle()
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { activity.nearby.confirm(false) },
            title = { Text("Is this the same code?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Compare with ${confirmation.name} in person. Accept only if both phones show the same code."
                    )
                    Text(
                        confirmation.digits,
                        style = MaterialTheme.typography.displayMedium,
                        color = BlushPink,
                    )
                    if (confirmation.accepted) Text("Waiting for the other person to confirm…")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { activity.nearby.confirm(true) },
                    enabled = !confirmation.accepted,
                ) {
                    Text("Codes match")
                }
            },
            dismissButton = {
                TextButton(onClick = { activity.nearby.confirm(false) }) { Text("Reject") }
            },
        )
    }
}
