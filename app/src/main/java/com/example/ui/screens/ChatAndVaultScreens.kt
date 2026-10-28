@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.data.model.*
import com.example.data.repository.*
import com.example.ui.*
import com.example.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun ChatsScreen(state: VaultState, onOpen: (VaultConversation) -> Unit, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Eyebrow("A world between us")
            Spacer(Modifier.height(10.dp))
            Title("Closer. Privately.", "Your words. Your moments. Your space.")
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Find a conversation") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                Modifier.padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Your conversations",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("${state.conversations.size} spaces", fontSize = 12.sp, color = MutedRoseGray)
            }
        }
        items(state.conversations.filter { it.title.contains(query, true) }, key = { it.id }) { c ->
            val latest = state.messages.lastOrNull { it.conversationId == c.id }
            Glass(Modifier.fillMaxWidth().clickable { onOpen(c) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(54.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(NeonRose.copy(alpha = .6f), GlassSurface)
                                ),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c.privateSpace) Icon(Icons.Rounded.Favorite, null, tint = BlushPink)
                        else
                            Text(
                                c.title.take(1).uppercase(),
                                fontFamily = FontFamily.Serif,
                                fontSize = 25.sp,
                            )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            latest?.let {
                                if (it.attachment.isNotEmpty()) "Private attachment"
                                else it.text.take(40)
                            } ?: c.subtitle,
                            color = MutedRoseGray,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    Icon(
                        if (c.partnerUid.isNotEmpty()) Icons.Rounded.Lock
                        else Icons.Rounded.Smartphone,
                        null,
                        tint = BlushPink,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (c.memoryFree || c.ephemeral) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (c.memoryFree) "◌  Memory-free · this session only"
                        else "◷  Messages expire after 24 hours",
                        color = AmberWarning,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    null,
                    tint = MutedRoseGray.copy(alpha = .6f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "A little more privacy. A lot more you.",
                    color = MutedRoseGray.copy(alpha = .6f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
fun NewSpaceDialog(
    onDismiss: () -> Unit,
    onCreate: suspend (String, Boolean, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(if (com.example.BuildConfig.CLOUD_ENABLED) 0 else 1) }
    var privateSpace by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Start a conversation", fontFamily = FontFamily.Serif) },
        text = {
            Column(
                Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (com.example.BuildConfig.CLOUD_ENABLED) listOf("Phone number", "On this phone", "Contact code") else listOf("On this phone")).forEachIndexed {
                        i,
                        label ->
                        FilterChip(
                            selected = mode == (if (com.example.BuildConfig.CLOUD_ENABLED) i else 1),
                            onClick = {
                                mode = if (com.example.BuildConfig.CLOUD_ENABLED) i else 1
                                problem = null
                            },
                            enabled = !busy,
                            label = { Text(label, fontSize = 10.sp) },
                        )
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (mode == 0)
                    OutlinedTextField(
                        phone,
                        { phone = it.take(40) },
                        label = { Text("Phone number with country code") },
                        placeholder = { Text("+12025550123") },
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                            ),
                        singleLine = true,
                        enabled = !busy,
                    )
                OutlinedTextField(
                    name,
                    { name = it.take(80) },
                    label = { Text(if (mode == 0) "Name (optional)" else "Name") },
                    singleLine = true,
                    enabled = !busy,
                )
                if (mode == 2)
                    OutlinedTextField(
                        code,
                        { code = it.take(4096) },
                        label = { Text("Partner contact code") },
                        maxLines = 4,
                        enabled = !busy,
                    )
                Text(
                    when (mode) {
                        0 ->
                            "Both people need a verified RoseVault phone account. The other person must allow phone discovery. Messages stay encrypted; they are not carrier SMS."
                        1 -> "An encrypted space on this phone, available without internet."
                        else ->
                            "Exchange contact codes through a trusted channel and compare safety numbers."
                    },
                    fontSize = 12.sp,
                    color = MutedRoseGray,
                    lineHeight = 18.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Require identity verification", modifier = Modifier.weight(1f))
                    Switch(privateSpace, { privateSpace = it }, enabled = !busy)
                }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            onCreate(
                                name,
                                privateSpace,
                                if (mode == 2) code else "",
                                if (mode == 0) phone else "",
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem = e.localizedMessage ?: "The conversation could not be started"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && (if (mode == 0) phone.isNotBlank() else name.isNotBlank()),
            ) {
                Text(if (mode == 0) "Start encrypted chat" else "Create space")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
fun ChatScreen(
    activity: MainActivity,
    c: VaultConversation,
    state: VaultState,
    modifier: Modifier,
) {
    val repo = activity.repository
    val nearbyState by activity.nearby.state.collectAsStateWithLifecycle()
    var text by remember(c.id) { mutableStateOf("") }
    var safety by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var requestedVideo by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val messages = state.messages.filter { it.conversationId == c.id }
    fun call(video: Boolean) {
        activity.placeCall(c, video)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1)
    }
    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.title, fontSize = 25.sp, fontFamily = FontFamily.Serif)
                Text(
                    if (c.transport == "nearby") "Nearby · tap to verify safety number"
                    else if (c.partnerUid.isEmpty()) "Encrypted on this device"
                    else "Paired · verify safety number",
                    fontSize = 11.sp,
                    color = BlushPink,
                    modifier =
                        if (c.partnerKey.isNotBlank()) Modifier.clickable { safety = true }
                        else Modifier,
                )
            }
            if (c.partnerUid.isNotEmpty() && c.transport == "cloud") {
                IconButton(onClick = { call(false) }) { Icon(Icons.Rounded.Call, "Voice call") }
                IconButton(onClick = { call(true) }) { Icon(Icons.Rounded.Videocam, "Video call") }
            }
            IconButton(onClick = { privacy = true }) { Icon(Icons.Rounded.Tune, "Chat privacy") }
        }
        HorizontalDivider(color = GlassBorder.copy(alpha = .25f))
        if (messages.isEmpty())
            Box(Modifier.weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.FavoriteBorder,
                        null,
                        tint = BlushPink,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Every connection starts here.",
                        fontFamily = FontFamily.Serif,
                        fontSize = 23.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Leave a thought, a moment, a little love.",
                        fontSize = 13.sp,
                        color = MutedRoseGray,
                    )
                }
            }
        else
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    val mine = repo.isMine(m)
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    ) {
                        Column(
                            Modifier.widthIn(max = 300.dp)
                                .background(
                                    if (mine) Color(0xFFA32B60) else GlassSurface,
                                    RoundedCornerShape(
                                        20.dp,
                                        20.dp,
                                        if (mine) 4.dp else 20.dp,
                                        if (mine) 20.dp else 4.dp,
                                    ),
                                )
                                .padding(16.dp)
                        ) {
                            if (m.text.isNotEmpty()) Text(m.text, lineHeight = 23.sp)
                            if (m.attachment.isNotEmpty())
                                TextButton(
                                    onClick = {
                                        activity.biometrics.authenticate(
                                            "Open encrypted attachment",
                                            {
                                                repo.launch {
                                                    val uri =
                                                        repo.openMedia(
                                                            m.attachment,
                                                            m.mime,
                                                            c.id,
                                                            m.id,
                                                        )
                                                    activity.startActivity(
                                                        Intent(Intent.ACTION_VIEW)
                                                            .setDataAndType(uri, m.mime)
                                                            .addFlags(
                                                                Intent
                                                                    .FLAG_GRANT_READ_URI_PERMISSION
                                                            )
                                                    )
                                                }
                                            },
                                            { repo.error.value = it },
                                        )
                                    }
                                ) {
                                    Icon(Icons.Rounded.AttachFile, null)
                                    Text("Open private attachment")
                                }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "${Instant.ofEpochMilli(m.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} · ${m.delivery}",
                                fontSize = 9.sp,
                                color = BlushPink,
                            )
                        }
                    }
                }
            }
        if (c.partnerUid.isNotEmpty())
            Text(
                if (c.transport == "nearby")
                    if (
                        nearbyState.connected.isNotEmpty() &&
                            activity.nearby.isConversationConnected(c.id)
                    )
                        "Nearby connected · no internet needed"
                    else "Disconnected · messages are saved and queued"
                else if (!repo.online.collectAsStateWithLifecycle().value)
                    "Offline · messages are saved and queued"
                else "Online · encrypted delivery",
                color = MutedRoseGray,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        if (c.memoryFree)
            Text(
                "Memory-free · disappears when the vault locks",
                color = AmberWarning,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { activity.pickMedia(c.id) }) {
                Icon(Icons.Rounded.AddCircleOutline, "Attach encrypted file")
            }
            OutlinedTextField(
                text,
                { text = it },
                placeholder = { Text("A little message…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(26.dp),
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    val value = text
                    repo.launch {
                        repo.send(c.id, value)
                        if (text == value) text = ""
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
            }
        }
    }
    if (safety)
        AlertDialog(
            onDismissRequest = { safety = false },
            title = { Text("Compare in person") },
            text = {
                Column {
                    Text(
                        "Compare this number with your partner over a trusted channel. Matching numbers verify the paired device keys."
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        runCatching { repo.crypto.safetyNumber(c.partnerKey) }
                            .getOrDefault("Local-only space"),
                        fontFamily = FontFamily.Monospace,
                        color = BlushPink,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { safety = false }) { Text("Done") } },
        )
    if (privacy)
        AlertDialog(
            onDismissRequest = { privacy = false },
            title = { Text("Conversation privacy") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Memory-free", modifier = Modifier.weight(1f))
                        Switch(c.memoryFree, { repo.setMode(c.id, memoryFree = it) })
                    }
                    Text(
                        "Turning this on removes local history. New messages, including undelivered ones, disappear on lock. Encrypted server packets expire after 24 hours; this cannot erase a partner’s copies.",
                        fontSize = 12.sp,
                        color = MutedRoseGray,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("24-hour messages", modifier = Modifier.weight(1f))
                        Switch(c.ephemeral, { repo.setMode(c.id, ephemeral = it) })
                    }
                    TextButton(
                        onClick = {
                            repo.retry()
                            privacy = false
                            repo.error.value =
                                if (c.transport == "nearby")
                                    "Queued messages retry when your phones reconnect in Nearby chats."
                                else if (!repo.online.value)
                                    "Messages are saved offline and retry when internet returns."
                                else "Retrying pending messages."
                        }
                    ) {
                        Text("Retry pending messages")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { privacy = false }) { Text("Done") } },
        )
}
