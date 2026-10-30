package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import kotlinx.coroutines.delay

@Composable
fun MatrixScreen(activity: MainActivity, close: () -> Unit) {
    val matrix = activity.matrix
    val state by matrix.state.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var peer by remember { mutableStateOf("") }
    var draft by remember(state.active) { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var security by remember { mutableStateOf(false) }
    var editBody by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<com.example.matrix.MatrixVault.MessageRow?>(null) }
    var selected by remember { mutableStateOf<com.example.matrix.MatrixVault.MessageRow?>(null) }
    var receipts by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (matrix.hasSession) matrix.run { matrix.restore() } }
    LaunchedEffect(draft, typing) {
        if (state.active != null && typing) {
            matrix.typingHint(draft.isNotBlank())
            delay(4000)
            matrix.typingHint(false)
        }
    }
    LaunchedEffect(receipts, state.messages.lastOrNull()?.key) { if (receipts) matrix.readHint() }
    Dialog(
        onDismissRequest = close,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = SecureFlagPolicy.SecureOn,
            ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Matrix · RoseVault", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = close) { Text("Local vault") }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = matrix::clearError) { Text("Dismiss error") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.user.isEmpty()) {
                    Text(
                        "Use an account on your own Matrix homeserver. No Twilio or SMS is required. The server operator creates accounts while public registration is disabled."
                    )
                    Text(
                        "Your existing local vault and Nearby chats remain on this phone. Matrix does not automatically import old Supabase conversations."
                    )
                    if (matrix.hasSession) {
                        TextButton(
                            onClick = { matrix.run { matrix.restore() } },
                            enabled = !state.busy,
                        ) {
                            Text("Restore Matrix session")
                        }
                    } else {
                        OutlinedTextField(
                            server,
                            { server = it.take(300) },
                            label = { Text("Homeserver HTTPS origin") },
                            placeholder = { Text("https://matrix.example.org") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            user,
                            { user = it.take(255) },
                            label = { Text("Matrix ID") },
                            placeholder = { Text("@name:example.org") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            password,
                            { password = it.take(512) },
                            label = { Text("Matrix account password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                val secret = password
                                password = ""
                                matrix.run { matrix.login(server, user, secret) }
                            },
                            enabled =
                                !state.busy &&
                                    server.isNotBlank() &&
                                    user.isNotBlank() &&
                                    password.isNotEmpty(),
                        ) {
                            Text("Sign in to homeserver")
                        }
                    }
                    Text(
                        "Password login is for your private Synapse deployment. OAuth-only public homeservers need a separate sign-in flow; they are not supported by this pilot."
                    )
                } else if (state.active == null) {
                    Text("${state.user}\nDevice: ${state.device}")
                    TextButton(onClick = { security = true }) {
                        Text("Device verification and recovery")
                    }
                    OutlinedTextField(
                        peer,
                        { peer = it.take(255) },
                        label = { Text("Person's Matrix ID") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            matrix.run {
                                val id = matrix.createDm(peer)
                                matrix.openRoom(id)
                            }
                        },
                        enabled = !state.busy && peer.isNotBlank(),
                    ) {
                        Text("Start encrypted conversation")
                    }
                    Text(
                        "Invitations are optional. Confirm the person's ID before accepting; room names and membership are visible to the server."
                    )
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.rooms, key = { it.id }) { row ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(row.title)
                                    Text(
                                        if (row.encrypted) "Encrypted room"
                                        else "Encryption not confirmed · opening blocked"
                                    )
                                    if (row.invited)
                                        Row {
                                            TextButton(
                                                onClick = { matrix.run { matrix.accept(row.id) } }
                                            ) {
                                                Text("Accept invitation")
                                            }
                                            TextButton(
                                                onClick = { matrix.run { matrix.decline(row.id) } }
                                            ) {
                                                Text("Decline")
                                            }
                                        }
                                    else
                                        TextButton(
                                            onClick = { matrix.run { matrix.openRoom(row.id) } },
                                            enabled = row.encrypted,
                                        ) {
                                            Text("Open chat")
                                        }
                                }
                            }
                        }
                    }
                } else {
                    Row {
                        TextButton(onClick = { matrix.back() }) { Text("Conversations") }
                        TextButton(onClick = { security = true }) { Text("Verify devices") }
                    }
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    if (state.typing.isNotEmpty()) Text("${state.typing.joinToString()} is typing…")
                    Text("Encrypted · queued offline · calls are not enabled in this Matrix pilot")
                    Row {
                        FilterChip(
                            selected = receipts,
                            onClick = {
                                receipts = !receipts
                                if (receipts) matrix.readHint()
                            },
                            label = { Text("Read receipts") },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = typing,
                            onClick = {
                                typing = !typing
                                if (!typing) matrix.typingHint(false)
                            },
                            label = { Text("Share typing") },
                        )
                    }
                    TextButton(
                        onClick = { activity.pickMatrixMedia(requireNotNull(state.active)) }
                    ) {
                        Text("Share encrypted file")
                    }
                    TextButton(onClick = { matrix.run { matrix.paginate() } }) {
                        Text("Load earlier messages")
                    }
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.messages, key = { it.key }) { message ->
                            ElevatedCard(
                                onClick = { selected = message },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        if (message.own) "You" else message.sender,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(message.body)
                                    Text(
                                        message.status,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (message.media != null) {
                                        Text(
                                            "Opens in another app, which may retain a copy.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        TextButton(
                                            onClick = {
                                                activity.openMatrixMedia(
                                                    message.media,
                                                    message.mime,
                                                )
                                            }
                                        ) {
                                            Text("Open private attachment")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        draft,
                        { draft = it.take(12000) },
                        label = { Text("Encrypted message") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                    Row {
                        Button(
                            onClick = {
                                val text = draft
                                matrix.run {
                                    matrix.send(text)
                                    if (draft == text) draft = ""
                                }
                            },
                            enabled = draft.isNotBlank() && !state.busy,
                        ) {
                            Text("Send")
                        }
                        TextButton(onClick = { matrix.run { matrix.retry() } }) {
                            Text("Retry queue")
                        }
                    }
                }
            }
        }
    }
    selected?.let { message ->
        AlertDialog(
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            onDismissRequest = { selected = null },
            title = { Text("Message actions") },
            text = {
                Text(
                    "${message.body}\n\nServer acceptance is Sent; it is not a delivery or read confirmation. Redaction cannot erase screenshots or another person's saved copies."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selected = null
                        matrix.run { matrix.react(message.id, "❤") }
                    }
                ) {
                    Text("React ❤")
                }
            },
            dismissButton = {
                if (message.own)
                    Column {
                        TextButton(
                            onClick = {
                                editing = message
                                editBody = message.body
                                selected = null
                            }
                        ) {
                            Text("Edit message")
                        }
                        TextButton(
                            onClick = {
                                selected = null
                                matrix.run { matrix.redact(message.id) }
                            }
                        ) {
                            Text("Remove my message")
                        }
                    }
                else TextButton(onClick = { selected = null }) { Text("Close") }
            },
        )
    }
    editing?.let { message ->
        AlertDialog(
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            onDismissRequest = { editing = null },
            title = { Text("Edit my message") },
            text = {
                OutlinedTextField(
                    editBody,
                    { editBody = it.take(12000) },
                    label = { Text("Replacement text") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = editBody
                        editing = null
                        matrix.run { matrix.edit(message.id, text) }
                    },
                    enabled = editBody.isNotBlank(),
                ) {
                    Text("Save edit")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (security)
        AlertDialog(
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            onDismissRequest = {
                security = false
                matrix.hideRecoveryKey()
            },
            title = { Text("Matrix security") },
            text = {
                androidx.compose.foundation.rememberScrollState().let { scroll ->
                    Column(
                        Modifier.heightIn(max = 450.dp).verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Your device: ${state.device}. Compare the SDK's emoji/number sequence using a trusted channel. Never approve a mismatch."
                        )
                        OutlinedTextField(
                            peer,
                            { peer = it.take(255) },
                            label = { Text("Matrix ID to verify") },
                        )
                        TextButton(
                            onClick = { matrix.run { matrix.requestVerification(peer) } },
                            enabled =
                                !state.busy &&
                                    peer.isNotBlank() &&
                                    state.verificationReady &&
                                    state.verificationPhase in
                                        listOf(
                                            com.example.matrix.MatrixVault.VerificationPhase.IDLE,
                                            com.example.matrix.MatrixVault.VerificationPhase.DONE,
                                            com.example.matrix.MatrixVault.VerificationPhase
                                                .CANCELLED,
                                            com.example.matrix.MatrixVault.VerificationPhase.FAILED,
                                        ),
                        ) {
                            Text("Request verification")
                        }
                        Text(state.verification)
                        TextButton(
                            onClick = { matrix.run { matrix.acceptVerification() } },
                            enabled =
                                !state.busy &&
                                    state.verificationReady &&
                                    state.verificationPhase ==
                                        com.example.matrix.MatrixVault.VerificationPhase.INCOMING,
                        ) {
                            Text("Accept request and compare")
                        }
                        TextButton(
                            onClick = { matrix.run { matrix.startVerification() } },
                            enabled =
                                !state.busy &&
                                    state.verificationReady &&
                                    state.verificationPhase ==
                                        com.example.matrix.MatrixVault.VerificationPhase.READY,
                        ) {
                            Text("Start secure comparison")
                        }
                        if (state.sas.isNotEmpty()) {
                            Text(state.sas)
                            TextButton(onClick = { matrix.run { matrix.approveVerification() } }) {
                                Text("Both sequences match")
                            }
                        }
                        TextButton(onClick = { matrix.run { matrix.cancelVerification() } }) {
                            Text("Cancel verification / mismatch")
                        }
                        Text(
                            "Recovery encrypts room-key backup with a recovery secret. Keep that secret offline; losing both devices and recovery secret can make history unreadable."
                        )
                        TextButton(onClick = { matrix.run { matrix.enableRecovery() } }) {
                            Text("Set up encrypted key recovery")
                        }
                        if (state.recoveryKey.isNotEmpty())
                            Text("Save this recovery key privately:\n${state.recoveryKey}")
                        OutlinedTextField(
                            recovery,
                            { recovery = it.take(400) },
                            label = { Text("Existing recovery key") },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        TextButton(
                            onClick = {
                                val secret = recovery
                                recovery = ""
                                matrix.run { matrix.recover(secret) }
                            },
                            enabled = recovery.isNotBlank(),
                        ) {
                            Text("Recover room keys")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        security = false
                        matrix.hideRecoveryKey()
                    }
                ) {
                    Text("Done")
                }
            },
        )
}
