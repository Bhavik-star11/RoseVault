package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.ai.RoseAi
import kotlinx.coroutines.*

@Composable
fun RoseAiDialog(activity: MainActivity, dismiss: () -> Unit) {
    val ai = activity.roseAi
    val saved = remember { runCatching { ai.config() }.getOrDefault(RoseAi.Config()) }
    var origin by remember { mutableStateOf(saved.origin) }
    var token by remember { mutableStateOf(saved.token) }
    var model by remember { mutableStateOf(saved.model) }
    var destination by remember { mutableStateOf(saved.origin) }
    var task by remember { mutableStateOf("Rewrite my message kindly") }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Rose · private reflection") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Optional self-hosted Ollama. Only text you type here is sent to your configured server. Its operator can read it. No chats are read automatically and this app saves no prompt history. Closing clears your draft and result."
                )
                Text(
                    "General communication support, not medical, legal, or professional counseling. Suggestions can be wrong. No cheating judgments or compatibility scores."
                )
                Text("Server connection")
                OutlinedTextField(
                    origin,
                    { origin = it.take(300) },
                    label = { Text("HTTPS server origin") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    token,
                    { token = it.take(256) },
                    label = { Text("Server access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    model,
                    { model = it.take(100) },
                    label = { Text("Installed Ollama model") },
                    singleLine = true,
                    enabled = !busy,
                )
                TextButton(
                    onClick = {
                        runCatching { ai.save(RoseAi.Config(origin, token, model)) }
                            .onSuccess {
                                destination = origin
                                result =
                                    "Server settings saved. Test a short request before sharing sensitive text."
                            }
                            .onFailure { result = it.localizedMessage ?: "Check server settings." }
                    },
                    enabled = !busy,
                ) {
                    Text("Save server settings")
                }
                TextButton(
                    onClick = {
                        ai.forget()
                        token = ""
                        destination = ""
                        result = "Saved server connection removed."
                    },
                    enabled = !busy,
                ) {
                    Text("Forget server connection")
                }
                listOf(
                        "Rewrite my message kindly",
                        "Help me start a conversation",
                        "Suggest a healthy boundary",
                        "Give date ideas",
                        "Help me apologize",
                        "Suggest a journal prompt",
                    )
                    .forEach { action ->
                        FilterChip(
                            selected = task == action,
                            onClick = { task = action },
                            enabled = !busy,
                            label = { Text(action) },
                        )
                    }
                OutlinedTextField(
                    input,
                    { input = it.take(1600) },
                    label = { Text("Text I choose to send") },
                    minLines = 3,
                    enabled = !busy,
                )
                TextButton(
                    onClick = { consent = true },
                    enabled = destination.isNotBlank() && input.isNotBlank() && !busy,
                ) {
                    Text("Review and ask my server")
                }
                if (busy) {
                    CircularProgressIndicator()
                    TextButton(onClick = { job?.cancel() }) { Text("Stop waiting") }
                }
                if (result.isNotBlank()) Text(result)
                TextButton(
                    onClick = {
                        job?.cancel()
                        input = ""
                        result = ""
                    }
                ) {
                    Text("Clear draft and result")
                }
                Text(
                    "Server AI needs a network connection; it does not work on a fully disconnected phone. Stopping or deleting a draft cannot recall text already sent to the server. If you may be in danger, contact local emergency services or a trusted person who can help you reach safety."
                )
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
    if (consent)
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text("Send this text to your server?") },
            text = {
                Text(
                    "Destination: $destination\n\nTask: $task\n\n$input\n\nOnly this text is submitted. Review your server's retention policy. Nothing is sent to your chat partner."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        consent = false
                        result = ""
                        busy = true
                        job =
                            scope.launch {
                                try {
                                    ai.generate(task, input).collect { result = it }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    result =
                                        "Could not get a response. Check server reachability, model, and access token. A request may already have reached your server."
                                } finally {
                                    busy = false
                                }
                            }
                    }
                ) {
                    Text("Send to my server")
                }
            },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("Keep private") } },
        )
}
