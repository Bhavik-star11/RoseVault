@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.calls.VaultCallEngine
import com.example.data.model.*
import com.example.data.repository.*
import com.example.ui.*
import com.example.ui.theme.*
import java.time.*
import org.webrtc.SurfaceViewRenderer

@Composable
fun CalendarScreen(repo: RoseVaultRepository, state: VaultState, modifier: Modifier) {
    var adding by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var time by remember { mutableStateOf("19:00") }
    var note by remember { mutableStateOf("") }
    var sharing by remember { mutableStateOf("") }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Eyebrow("Make time for us")
            Spacer(Modifier.height(10.dp))
            Title("Something to look\nforward to.", "The best plans start with a little intention.")
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(LocalDate.now().month.name)
                        Text(
                            LocalDate.now().year.toString(),
                            fontFamily = FontFamily.Serif,
                            fontSize = 32.sp,
                        )
                    }
                    Icon(
                        Icons.Rounded.FavoriteBorder,
                        null,
                        tint = BlushPink,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val today = LocalDate.now()
                    (0..6).forEach { offset ->
                        val day = today.plusDays(offset.toLong())
                        Column(
                            Modifier.background(
                                    if (offset == 0) NeonRose else Color.Transparent,
                                    RoundedCornerShape(18.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(day.dayOfWeek.name.take(1), fontSize = 10.sp, color = BlushPink)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                day.dayOfMonth.toString(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Your plans",
                    fontSize = 19.sp,
                    fontFamily = FontFamily.Serif,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Rounded.Add, null)
                    Text("Plan a date")
                }
            }
        }
        if (state.dates.isEmpty())
            item {
                Empty(
                    Icons.Rounded.NightsStay,
                    "Save a little time together.",
                    "A movie night, a walk, a call that lasts too long. Add your first plan.",
                )
            }
        items(state.dates, key = { it.id }) { event ->
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(56.dp).background(GlassSurface, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            runCatching { LocalDate.parse(event.date).dayOfMonth.toString() }
                                .getOrDefault("♡"),
                            fontSize = 25.sp,
                            color = BlushPink,
                            fontFamily = FontFamily.Serif,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(event.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("${event.date} · ${event.time}", color = BlushPink, fontSize = 12.sp)
                        if (event.note.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(event.note, color = MutedRoseGray, fontSize = 13.sp)
                        }
                    }
                    IconButton(onClick = { repo.deleteDate(event.id) }) {
                        Icon(Icons.Rounded.Close, "Remove plan")
                    }
                }
            }
        }
        item {
            Text(
                "Plans are encrypted on this phone. Shared plans sync encrypted with your paired partner.",
                color = MutedRoseGray,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }
    }
    if (adding)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Make a little plan") },
            text = {
                Column(
                    Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(name, { name = it }, label = { Text("What's the plan?") })
                    OutlinedTextField(date, { date = it }, label = { Text("Date · YYYY-MM-DD") })
                    OutlinedTextField(time, { time = it }, label = { Text("Time · HH:mm") })
                    OutlinedTextField(note, { note = it }, label = { Text("A little note") })
                    Text("Keep on this phone, or share encrypted with:", fontSize = 12.sp)
                    FilterChip(
                        selected = sharing.isEmpty(),
                        onClick = { sharing = "" },
                        label = { Text("Only me") },
                    )
                    state.conversations
                        .filter { it.partnerUid.isNotEmpty() && it.transport == "cloud" }
                        .forEach { c ->
                            FilterChip(
                                selected = sharing == c.id,
                                onClick = { sharing = c.id },
                                label = { Text(c.title) },
                            )
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                                LocalDate.parse(date)
                                LocalTime.parse(time)
                                repo.addDate(
                                    VirtualDateEvent(
                                        title = name.trim(),
                                        date = date,
                                        time = time,
                                        note = note,
                                        conversationId = sharing,
                                    )
                                )
                                adding = false
                                name = ""
                                note = ""
                            }
                            .onFailure { problem = "Enter a valid date and time" }
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("Save plan")
                }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
}

@Composable
fun CallDialog(engine: VaultCallEngine, onAccept: (VaultConversation, Boolean) -> Unit) {
    val state by engine.state.collectAsStateWithLifecycle()
    val local by engine.localVideo.collectAsStateWithLifecycle()
    val remote by engine.remoteVideo.collectAsStateWithLifecycle()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties =
            androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn,
            ),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(MidnightVelvet, ObsidianPlum)))
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Eyebrow(if (state.video) "Private video call" else "Private voice call")
            Spacer(Modifier.height(24.dp))
            Text(
                state.conversation?.title ?: "Call",
                fontFamily = FontFamily.Serif,
                fontSize = 36.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(state.status, color = BlushPink, textAlign = TextAlign.Center)
            Box(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 28.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(GlassSurface.copy(alpha = .5f)),
                contentAlignment = Alignment.Center,
            ) {
                if (remote != null)
                    AndroidView(
                        factory = { context ->
                            SurfaceViewRenderer(context).apply {
                                init(engine.egl.eglBaseContext, null)
                                remote?.addSink(this)
                            }
                        },
                        onRelease = { renderer ->
                            remote?.removeSink(renderer)
                            renderer.release()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                else
                    Icon(
                        if (state.video) Icons.Rounded.Videocam else Icons.Rounded.FavoriteBorder,
                        null,
                        tint = BlushPink,
                        modifier = Modifier.size(80.dp),
                    )
                if (local != null)
                    AndroidView(
                        factory = { context ->
                            SurfaceViewRenderer(context).apply {
                                init(engine.egl.eglBaseContext, null)
                                setMirror(true)
                                setZOrderMediaOverlay(true)
                                local?.addSink(this)
                            }
                        },
                        onRelease = { renderer ->
                            local?.removeSink(renderer)
                            renderer.release()
                        },
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(12.dp)
                                .size(100.dp, 140.dp)
                                .clip(RoundedCornerShape(18.dp)),
                    )
            }
            Text(
                "Relay-only connection · compare safety numbers in chat",
                fontSize = 11.sp,
                color = MutedRoseGray,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.incoming && state.status == "Incoming call")
                    FilledIconButton(
                        onClick = { onAccept(state.conversation!!, state.video) },
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = EmeraldEncrypted
                            ),
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(Icons.Rounded.Call, "Accept call", tint = ObsidianPlum)
                    }
                else {
                    IconButton(onClick = engine::mute) {
                        Icon(
                            if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            "Toggle microphone",
                        )
                    }
                    if (state.video)
                        IconButton(onClick = engine::camera) {
                            Icon(
                                if (state.cameraOff) Icons.Rounded.VideocamOff
                                else Icons.Rounded.Videocam,
                                "Toggle camera",
                            )
                        }
                }
                FilledIconButton(
                    onClick = engine::dismiss,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = DangerRed),
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(Icons.Rounded.CallEnd, "End call")
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun CallsScreen(activity: MainActivity, state: VaultState, modifier: Modifier) {
    fun call(c: VaultConversation, video: Boolean) {
        activity.placeCall(c, video)
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Eyebrow("Distance feels a little smaller")
            Spacer(Modifier.height(10.dp))
            Title("A voice, a little\ncloser.", "For the hellos that turn into hours.")
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    null,
                    tint = BlushPink,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Just the two of you.", fontFamily = FontFamily.Serif, fontSize = 27.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Voice and video, with encrypted signaling and a relay-only connection that conceals your IP address from your partner.",
                    color = MutedRoseGray,
                    lineHeight = 23.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Calls stay active while RoseVault is open and unlocked.",
                    color = BlushPink,
                    fontSize = 12.sp,
                )
            }
        }
        val paired =
            state.conversations.filter { it.partnerUid.isNotEmpty() && it.transport == "cloud" }
        if (paired.isEmpty())
            item {
                Empty(
                    Icons.Rounded.Link,
                    "Bring your person closer.",
                    "Verify your phone number in Security, then exchange device contact codes and create a paired space in Chats.",
                )
            }
        items(paired, key = { it.id }) { c ->
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, fontSize = 20.sp, fontFamily = FontFamily.Serif)
                        Text("Paired device", fontSize = 11.sp, color = MutedRoseGray)
                    }
                    IconButton(onClick = { call(c, false) }) {
                        Icon(Icons.Rounded.Call, "Call " + c.title)
                    }
                    IconButton(onClick = { call(c, true) }) {
                        Icon(Icons.Rounded.Videocam, "Video call " + c.title)
                    }
                }
            }
        }
    }
}
