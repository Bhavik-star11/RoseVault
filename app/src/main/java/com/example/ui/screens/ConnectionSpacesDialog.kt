package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.data.model.*
import com.example.ui.theme.MutedRoseGray

@Composable
fun ConnectionSpacesDialog(
    activity: MainActivity,
    c: VaultConversation,
    state: VaultState,
    dismiss: () -> Unit,
) {
    val repo = activity.repository
    val self = if (c.transport == "nearby") repo.nearbyId else repo.uid
    val history = state.messages.filter { it.conversationId == c.id }
    val view = ConnectionSpaces.view(history, self, c.partnerUid)
    val selected = state.privateIntentions[c.id] ?: "Unsure"
    var share by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf("journal") }
    var prompt by remember { mutableIntStateOf(0) }
    val prompts =
        when (view.label) {
            "Dating" ->
                listOf(
                    "What would make getting to know each other feel comfortable?",
                    "What is a low-pressure date we would both enjoy?",
                    "What would you like me to understand about your boundaries?",
                )
            "Lovers" ->
                listOf(
                    "What helped you feel supported this week?",
                    "What is one small goal we would enjoy sharing?",
                    "Which memory would you like to revisit together?",
                )
            else ->
                listOf(
                    "What kind of shared space would feel comfortable to you?",
                    "What is one plan we would both enjoy?",
                    "Is there a boundary you would like me to understand?",
                )
        }
    val paired = c.partnerUid.isNotBlank() && !c.memoryFree && !c.ephemeral
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Connection Spaces") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Shared space: ${view.label}")
                Text(
                    "Your intention stays on this phone until you choose to share an invitation. Different intentions never change your chat or reveal anyone's private choice.",
                    color = MutedRoseGray,
                )
                Text("My private intention")
                (ConnectionSpaces.labels + "Unsure").forEach { label ->
                    FilterChip(
                        selected = selected == label,
                        onClick = { repo.privateIntention(c.id, label) },
                        label = { Text(label) },
                    )
                }
                TextButton(
                    onClick = { share = true },
                    enabled =
                        paired &&
                            selected in ConnectionSpaces.labels &&
                            view.pending.none { it.senderId == self },
                ) {
                    Text("Review invitation to share")
                }
                if (!paired)
                    Text(
                        "Invitations need a paired chat with disappearing and memory-free modes off. Normal chat stays available.",
                        color = MutedRoseGray,
                    )
                view.pending.forEach { offer ->
                    HorizontalDivider()
                    Text(
                        if (offer.senderId == self)
                            "Your ${offer.text} invitation is waiting. There is no deadline."
                        else
                            "An invitation to a ${offer.text} space. You can decline or leave it for later; normal chat continues."
                    )
                    if (offer.senderId != self) {
                        Button(
                            onClick = {
                                repo.launch {
                                    repo.send(
                                        c.id,
                                        offer.text,
                                        kind = "space.accept",
                                        reference = offer.id,
                                    )
                                }
                            }
                        ) {
                            Text("Agree to ${offer.text} space")
                        }
                        TextButton(
                            onClick = {
                                repo.launch {
                                    repo.send(
                                        c.id,
                                        "Not now",
                                        kind = "space.decline",
                                        reference = offer.id,
                                    )
                                }
                            }
                        ) {
                            Text("Not now")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                repo.launch {
                                    repo.send(
                                        c.id,
                                        "Invitation withdrawn",
                                        kind = "space.close",
                                        reference = offer.id,
                                    )
                                }
                            }
                        ) {
                            Text("Withdraw invitation")
                        }
                    }
                }
                if (view.agreement.isNotEmpty()) {
                    Text(
                        "Both people agreed. Either person can return to a neutral space without losing the chat.",
                        color = MutedRoseGray,
                    )
                    TextButton(
                        onClick = {
                            repo.launch {
                                repo.send(
                                    c.id,
                                    "Return to neutral",
                                    kind = "space.close",
                                    reference = view.agreement,
                                )
                            }
                        }
                    ) {
                        Text("Return to neutral space")
                    }
                }
                HorizontalDivider()
                Text("An optional conversation starter")
                Text(prompts[prompt % prompts.size])
                TextButton(onClick = { prompt++ }) { Text("Another question") }
                TextButton(
                    onClick = { repo.launch { repo.send(c.id, prompts[prompt % prompts.size]) } }
                ) {
                    Text("Share this question in chat")
                }
                HorizontalDivider()
                Text("Our Story")
                Text(
                    "Share only what you choose. Notes use the chat's encryption and delivery queue; the other person can keep a copy.",
                    color = MutedRoseGray,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("journal", "mood", "moment").forEach { value ->
                        FilterChip(
                            selected = tool == value,
                            onClick = { tool = value },
                            label = { Text(value.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                OutlinedTextField(
                    note,
                    { note = it.take(2000) },
                    label = { Text("Optional $tool to share") },
                    minLines = 2,
                )
                TextButton(
                    onClick = {
                        val value = note
                        repo.launch {
                            repo.send(c.id, value, kind = tool)
                            if (note == value) note = ""
                        }
                    },
                    enabled = note.isNotBlank(),
                ) {
                    Text("Share $tool")
                }
                history
                    .filter { it.kind in setOf("moment", "journal", "mood") }
                    .takeLast(20)
                    .reversed()
                    .forEach { m ->
                        Text(
                            "${m.kind.replaceFirstChar { it.uppercase() }} · ${if (repo.isMine(m)) "You" else "Shared with you"}\n${m.text}"
                        )
                    }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
    )
    if (share)
        AlertDialog(
            onDismissRequest = { share = false },
            title = { Text("Share your invitation?") },
            text = {
                Text(
                    "This sends your choice of $selected to the other person in this encrypted chat. Your shared label changes only if they explicitly agree. They may decline or say not now. This does not grant access to other chats or your local vault."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        share = false
                        repo.launch {
                            repo.send(
                                c.id,
                                selected,
                                kind = "space.offer",
                                reference = view.agreement,
                            )
                        }
                    }
                ) {
                    Text("Send invitation")
                }
            },
            dismissButton = { TextButton(onClick = { share = false }) { Text("Keep private") } },
        )
}
