@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.data.model.*
import com.example.data.repository.*
import com.example.ui.*
import com.example.ui.theme.*
import java.time.*
import kotlinx.coroutines.launch

@Composable
fun MediaScreen(activity: MainActivity, state: VaultState, modifier: Modifier) {
    val repo = activity.repository
    var export by remember { mutableStateOf<VaultMedia?>(null) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Eyebrow("Only for your eyes")
            Spacer(Modifier.height(10.dp))
            Title("The little things.", "A private home for the moments you keep.")
            Glass(Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Rounded.CollectionsBookmark,
                    null,
                    tint = BlushPink,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Some moments deserve a vault.",
                    fontFamily = FontFamily.Serif,
                    fontSize = 26.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Photos, voice notes, videos, and files. Encrypted before they touch storage.",
                    color = MutedRoseGray,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { activity.pickMedia() }) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a private moment")
                }
            }
        }
        item {
            Text(
                "${state.media.size} saved moments",
                color = MutedRoseGray,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        items(state.media, key = { it.id }) { media ->
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            media.mime.startsWith("image") -> Icons.Rounded.Image
                            media.mime.startsWith("audio") -> Icons.Rounded.GraphicEq
                            media.mime.startsWith("video") -> Icons.Rounded.Movie
                            else -> Icons.Rounded.Description
                        },
                        null,
                        tint = BlushPink,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(media.name, maxLines = 1)
                        Text(
                            "${"%.1f".format(media.bytes/1024.0)} KB · encrypted",
                            color = MutedRoseGray,
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = { export = media }) {
                        Icon(Icons.Rounded.LockOpen, "Open attachment")
                    }
                    IconButton(onClick = { repo.deleteMedia(media.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Delete attachment")
                    }
                }
            }
        }
    }
    export?.let { m ->
        AlertDialog(
            onDismissRequest = { export = null },
            title = { Text("Open with another app?") },
            text = {
                Text(
                    "After verification, a decrypted temporary copy will be shared with the app you choose. That app can retain a copy."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        export = null
                        activity.biometrics.authenticate(
                            "Open private media",
                            {
                                repo.launch {
                                    val uri = repo.openMedia(m.file, m.mime)
                                    activity.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, m.mime)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    )
                                }
                            },
                            { repo.error.value = it },
                        )
                    }
                ) {
                    Text("Verify & open")
                }
            },
            dismissButton = { TextButton(onClick = { export = null }) { Text("Cancel") } },
        )
    }
}
