@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
fun SecurityScreen(activity: MainActivity, state: VaultState, modifier: Modifier) {
    val repo = activity.repository
    val hasAppPassword by activity.hasAppPassword.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf<String?>(null) }
    var bench by remember { mutableStateOf<String?>(null) }
    val phone by activity.phoneLogin.state.collectAsStateWithLifecycle()
    LaunchedEffect(signedInForRefresh(activity)) { activity.phoneLogin.refresh(activity) }
    val signedIn by activity.accountConnected.collectAsStateWithLifecycle()
    var showAudit by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf<List<VaultNotification>?>(null) }
    val notification =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (!allowed) repo.error.value = "Notifications are disabled in Android settings."
        }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Eyebrow("Your boundaries, beautifully kept")
            Spacer(Modifier.height(10.dp))
            Title("Peace of mind.", "Privacy is personal. Make it yours.")
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Brand(58)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Your private world", fontFamily = FontFamily.Serif, fontSize = 23.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (phone.phoneNumber != null) "Phone connected · ${phone.phoneNumber}"
                            else if (signedIn) "Existing account connected"
                            else "Local vault · on this phone",
                            color = BlushPink,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Android Keystore encryption at rest. Identity verification at every entry.",
                    color = MutedRoseGray,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                )
                if (!com.example.BuildConfig.CLOUD_ENABLED) {
                    Spacer(Modifier.height(14.dp))
                    Text("Offline mode · no account needed", color = BlushPink)
                    Text(
                        "Use Nearby from Chats to message someone close by. Both phones must open RoseVault and accept the connection.",
                        color = MutedRoseGray,
                        fontSize = 12.sp,
                    )
                } else if (phone.phoneNumber == null) {
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { activity.phoneLogin.show() }) {
                        Text("Sign in with phone number")
                    }
                } else {
                    TextButton(onClick = { activity.phoneLogin.show() }) {
                        Text("Verify phone on this device")
                    }
                    SettingRow(
                        Icons.Rounded.Phone,
                        "Find me by phone number",
                        "Allow verified users who know your number to start a chat",
                    ) {
                        Switch(
                            phone.discoverable,
                            { activity.phoneLogin.discovery(it) },
                            enabled = !phone.busy && phone.registered,
                        )
                    }
                    if (!phone.registered)
                        TextButton(
                            onClick = { activity.phoneLogin.registerAgain(activity) },
                            enabled = !phone.busy,
                        ) {
                            Text("Finish phone setup")
                        }
                    if (!phone.registered)
                        Text(
                            "Verify this device to enable phone discovery.",
                            color = MutedRoseGray,
                            fontSize = 12.sp,
                        )
                    phone.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
                if (signedIn) {

                    TextButton(
                        onClick = {
                            runCatching { code = repo.contactCode() }
                                .onFailure { repo.error.value = it.localizedMessage }
                        }
                    ) {
                        Icon(Icons.Rounded.Link, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Share my contact code")
                    }
                    Text(
                        "Pairing is tied to this device. Reinstalling the app creates new keys.",
                        color = MutedRoseGray,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Eyebrow("Unlock your vault")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Use your phone screen lock or your own RoseVault password.",
                    color = MutedRoseGray,
                    fontSize = 13.sp,
                )
                TextButton(onClick = activity::editAppPassword) {
                    Text(
                        if (hasAppPassword) "Manage RoseVault password"
                        else "Set RoseVault password"
                    )
                }
                Text(
                    "Your phone screen lock can change or remove a forgotten app password. Phone-number sign-in cannot unlock this vault.",
                    color = MutedRoseGray,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(16.dp))
                Eyebrow("Privacy controls")
                Spacer(Modifier.height(12.dp))
                SettingRow(
                    Icons.Rounded.ScreenshotMonitor,
                    "Screen capture shield",
                    "Hide content from screenshots and app previews",
                ) {
                    Switch(
                        state.settings.secureScreen,
                        {
                            repo.settings(state.settings.copy(secureScreen = it))
                            activity.applyShield()
                        },
                    )
                }
                HorizontalDivider(
                    color = GlassBorder.copy(alpha = .2f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Text("Auto-lock after inactivity", fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to "30 sec", 60 to "1 min", 300 to "5 min").forEach { (seconds, label)
                        ->
                        FilterChip(
                            selected = state.settings.timeoutSeconds == seconds,
                            onClick = {
                                repo.settings(state.settings.copy(timeoutSeconds = seconds))
                                activity.touch()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "The vault always locks when you leave the app. Calls end when locked.",
                    fontSize = 11.sp,
                    color = MutedRoseGray,
                    lineHeight = 17.sp,
                )
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Eyebrow("Discreet by default")
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    Icons.Rounded.NotificationsNone,
                    "Protected activity only",
                    "Sender names and message text never appear in notifications",
                ) {}
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33)
                            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else
                            activity.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(
                                        android.provider.Settings.EXTRA_APP_PACKAGE,
                                        activity.packageName,
                                    )
                            )
                    }
                ) {
                    Text("Enable private notifications")
                }
                TextButton(
                    onClick = {
                        activity.verified("Open notification vault") {
                            notices = repo.notifications()
                        }
                    }
                ) {
                    Text("Open notification vault")
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Eyebrow("This trusted device")
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    Icons.Rounded.Smartphone,
                    Build.MODEL,
                    "Android ${Build.VERSION.RELEASE} · current device",
                ) {}
                Spacer(Modifier.height(12.dp))
                Text(
                    "Remote session revocation and multi-device ratcheting are not enabled in this version.",
                    color = MutedRoseGray,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Eyebrow("Hardware key bench")
                Spacer(Modifier.height(12.dp))
                Text("Put your vault to the test.", fontFamily = FontFamily.Serif, fontSize = 23.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Two biometric prompts authorize encryption and decryption separately. This verifies a real authenticated AES-GCM round trip.",
                    color = MutedRoseGray,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                                val original = "RoseVault hardware check".toByteArray()
                                activity.biometrics.cipher(
                                    "Authorize encryption",
                                    repo.crypto.createBiometricEncryptCryptoObject(),
                                    { cipher ->
                                        val envelope =
                                            repo.crypto.encryptWithAuthenticatedCipher(
                                                cipher,
                                                original,
                                            )
                                        activity.biometrics.cipher(
                                            "Authorize decryption",
                                            repo.crypto.createBiometricDecryptCryptoObject(
                                                envelope
                                            ),
                                            { decryptCipher ->
                                                val decoded =
                                                    decryptCipher.doFinal(
                                                        com.example.security.CryptoEngine.decode(
                                                            envelope.split(':')[2]
                                                        )
                                                    )
                                                bench =
                                                    if (decoded.contentEquals(original))
                                                        "Passed · authenticated AES-256-GCM round trip"
                                                    else "Verification failed"
                                                repo.audit("Hardware cipher tested")
                                            },
                                            { repo.error.value = it },
                                        )
                                    },
                                    { repo.error.value = it },
                                )
                            }
                            .onFailure { repo.error.value = it.localizedMessage }
                    }
                ) {
                    Icon(Icons.Rounded.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Run hardware check")
                }
                bench?.let { Text(it, color = EmeraldEncrypted, fontSize = 12.sp) }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                SettingRow(
                    Icons.Rounded.History,
                    "Security activity",
                    "Unlocks, privacy changes, and key checks",
                ) {
                    IconButton(onClick = { showAudit = !showAudit }) {
                        Icon(
                            if (showAudit) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            "Show activity",
                        )
                    }
                }
                if (showAudit)
                    state.audit.take(20).forEach { event ->
                        Spacer(Modifier.height(12.dp))
                        Text(event.text, fontSize = 13.sp)
                        Text(
                            Instant.ofEpochMilli(event.at)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MMM d · HH:mm")),
                            fontSize = 10.sp,
                            color = MutedRoseGray,
                        )
                    }
            }
        }
        item {
            OutlinedButton(onClick = activity::lock, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Lock, null)
                Spacer(Modifier.width(8.dp))
                Text("Lock now")
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "ROSEVAULT  /  B-NOVA\nBuilt with care. Kept with you.",
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MutedRoseGray.copy(alpha = .6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    notices?.let { items ->
        AlertDialog(
            onDismissRequest = { notices = null },
            title = { Text("Notification vault") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (items.isEmpty()) Text("No protected notifications yet.")
                    items.forEach { notice ->
                        TextButton(
                            onClick = {
                                notices = null
                                activity.pendingConversation.value = notice.conversationId
                            }
                        ) {
                            Text(
                                state.conversations.find { it.id == notice.conversationId }?.title
                                    ?: "Protected conversation"
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { notices = null }) { Text("Done") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        repo.clearNotifications()
                        notices = emptyList()
                    }
                ) {
                    Text("Clear notifications")
                }
            },
        )
    }
    code?.let { value ->
        AlertDialog(
            onDismissRequest = { code = null },
            title = { Text("Your device contact code") },
            text = {
                Column {
                    Text(
                        "Share this through a trusted channel. Both people must add the other's code, then compare safety numbers.",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(value, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activity.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, value),
                                "Share contact code",
                            )
                        )
                    }
                ) {
                    Text("Share")
                }
            },
            dismissButton = { TextButton(onClick = { code = null }) { Text("Close") } },
        )
    }
}

@Composable
fun SettingRow(icon: ImageVector, title: String, body: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = BlushPink, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 11.sp, lineHeight = 17.sp, color = MutedRoseGray)
        }
        action()
    }
}

private fun signedInForRefresh(activity: MainActivity) = activity.repository.uid
