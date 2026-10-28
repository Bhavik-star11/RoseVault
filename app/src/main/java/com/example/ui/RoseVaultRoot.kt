@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.data.model.*
import com.example.data.repository.*
import com.example.ui.auth.*
import com.example.ui.screens.*
import com.example.ui.theme.*
import java.time.*
import kotlinx.coroutines.delay

@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(GlassSurface.copy(alpha = .66f), MidnightVelvet.copy(alpha = .8f))
                )
            )
            .border(1.dp, GlassBorder.copy(alpha = .35f), RoundedCornerShape(24.dp))
            .padding(20.dp),
        content = content,
    )
}

@Composable
fun Brand(size: Int = 72) {
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape((size * .27).dp))
            .background(Brush.linearGradient(listOf(BlushPink, NeonRose, Color(0xFFAD1457)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "R",
            fontFamily = FontFamily.Serif,
            fontSize = (size * .8).sp,
            fontWeight = FontWeight.Bold,
            color = SoftRoseText,
        )
        Icon(
            Icons.Rounded.Lock,
            null,
            tint = Color(0xFFAD1457),
            modifier =
                Modifier.size((size * .22).dp).offset(x = (-size * .035).dp, y = (-size * .11).dp),
        )
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        color = BlushPink,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun Title(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            title,
            fontFamily = FontFamily.Serif,
            fontSize = 36.sp,
            lineHeight = 43.sp,
            color = SoftRoseText,
        )
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = MutedRoseGray, fontSize = 14.sp)
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, body: String) {
    Glass(Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = BlushPink, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(20.dp))
        Text(title, fontSize = 20.sp, lineHeight = 28.sp, fontFamily = FontFamily.Serif)
        Spacer(Modifier.height(8.dp))
        Text(body, color = MutedRoseGray, lineHeight = 22.sp)
    }
}

@Composable
fun RoseVaultRoot(activity: MainActivity) {
    val repo = activity.repository
    val unlocked by activity.unlocked.collectAsStateWithLifecycle()
    val connected by activity.accountConnected.collectAsStateWithLifecycle()
    val hasAppPassword by activity.hasAppPassword.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val error by repo.error.collectAsStateWithLifecycle()
    val pending by activity.pendingConversation.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    var newSpace by remember { mutableStateOf(false) }
    var showNearby by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val call by activity.calls.state.collectAsStateWithLifecycle()
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            repo.error.value = null
        }
    }
    LaunchedEffect(unlocked, pending) {
        if (unlocked && pending != null) {
            conversationId = pending
            activity.pendingConversation.value = null
        }
    }
    LaunchedEffect(unlocked, state.conversations.map { it.id }) {
        if (unlocked) activity.calls.watch(state.conversations)
    }
    LaunchedEffect(unlocked) {
        if (!unlocked) {
            conversationId = null
            newSpace = false
        }
        while (unlocked) {
            delay(15_000)
            repo.sweep()
            repo.retry()
        }
    }
    BackHandler(enabled = unlocked && conversationId != null) { conversationId = null }
    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF762045), ObsidianPlum, Color(0xFF240B1B)),
                    radius = 1500f,
                )
            )
    ) {
        if (!unlocked) {
            Column(
                Modifier.fillMaxSize()
                    .background(Color(0xFF15181D))
                    .safeDrawingPadding()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(88.dp).background(Color(0xFF242A32), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        null,
                        tint = Color(0xFFB8C4D0),
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    "System Security Vault",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.height(12.dp))
                Text("Identity verification required", color = Color(0xFF9CA7B5))
                Spacer(Modifier.height(40.dp))
                Button(
                    onClick = activity::unlock,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFCDD8E5),
                            contentColor = Color(0xFF15181D),
                        ),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Icon(Icons.Rounded.Fingerprint, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Verify identity")
                }
                if (hasAppPassword) {
                    TextButton(onClick = activity::unlockWithPassword) {
                        Text("Use RoseVault password", color = Color(0xFFCDD8E5))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Text("Protected on this device", fontSize = 12.sp, color = Color(0xFF83909E))
            }
        } else if (
            com.example.BuildConfig.CLOUD_ENABLED &&
                !state.settings.onboardingComplete &&
                !connected
        ) {
            AuthScreen(activity)
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Row(
                        Modifier.fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (conversationId != null)
                            IconButton(onClick = { conversationId = null }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                            }
                        else Brand(36)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "RoseVault",
                            fontFamily = FontFamily.Serif,
                            fontSize = 23.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showNearby = true }) {
                            Icon(Icons.Rounded.WifiTethering, "Nearby chats", tint = BlushPink)
                        }
                        IconButton(onClick = activity::lock) {
                            Icon(Icons.Rounded.Lock, "Lock vault", tint = BlushPink)
                        }
                    }
                },
                bottomBar = {
                    if (conversationId == null)
                        NavigationBar(containerColor = Color(0xEE350C22), tonalElevation = 0.dp) {
                            listOf(
                                    "Chats" to Icons.AutoMirrored.Rounded.Chat,
                                    "Calls" to Icons.Rounded.Call,
                                    "Vault" to Icons.Rounded.PhotoLibrary,
                                    "Dates" to Icons.Rounded.CalendarMonth,
                                    "Security" to Icons.Rounded.Shield,
                                )
                                .forEachIndexed { i, (name, icon) ->
                                    NavigationBarItem(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        icon = { Icon(icon, name) },
                                        label = { Text(name, fontSize = 11.sp) },
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                indicatorColor = GlassSurface,
                                                selectedIconColor = BlushPink,
                                                selectedTextColor = BlushPink,
                                                unselectedIconColor =
                                                    MutedRoseGray.copy(alpha = .7f),
                                                unselectedTextColor =
                                                    MutedRoseGray.copy(alpha = .7f),
                                            ),
                                    )
                                }
                        }
                },
                floatingActionButton = {
                    if (conversationId == null && tab == 0)
                        FloatingActionButton(
                            onClick = { newSpace = true },
                            containerColor = NeonRose,
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Rounded.Add, "Create space")
                        }
                },
            ) { padding ->
                val c = state.conversations.find { it.id == conversationId }
                if (c != null) ChatScreen(activity, c, state, Modifier.padding(padding))
                else
                    when (tab) {
                        0 ->
                            ChatsScreen(
                                state,
                                { selected ->
                                    if (selected.privateSpace)
                                        activity.verified("Open private relationship space") {
                                            conversationId = selected.id
                                        }
                                    else conversationId = selected.id
                                },
                                Modifier.padding(padding),
                            )
                        1 -> CallsScreen(activity, state, Modifier.padding(padding))
                        2 -> MediaScreen(activity, state, Modifier.padding(padding))
                        3 -> CalendarScreen(repo, state, Modifier.padding(padding))
                        4 -> SecurityScreen(activity, state, Modifier.padding(padding))
                    }
            }
        }
        if (!unlocked)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (newSpace && unlocked)
        NewSpaceDialog({ newSpace = false }) { title, privateSpace, code, phone ->
            if (phone.isNotBlank()) {
                val id = repo.startPhoneConversation(title, privateSpace, phone)
                conversationId = id
            } else repo.newConversation(title, privateSpace, code)
            newSpace = false
        }
    if (unlocked && call.status != "Idle")
        CallDialog(activity.calls, { c, video -> activity.placeCall(c, video, true) })
    if (unlocked && showNearby) NearbyChatsDialog(activity) { showNearby = false }
    if (unlocked) NearbyConfirmationDialog(activity)
    AppPasswordDialogs(activity)
    if (unlocked && com.example.BuildConfig.CLOUD_ENABLED) PhoneLoginDialog(activity)
}
