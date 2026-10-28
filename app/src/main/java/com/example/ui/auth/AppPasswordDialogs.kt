package com.example.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity

@Composable
fun AppPasswordDialogs(activity: MainActivity) {
    val prompt by activity.passwordPrompt.collectAsStateWithLifecycle()
    val editor by activity.passwordEditor.collectAsStateWithLifecycle()
    val busy by activity.passwordBusy.collectAsStateWithLifecycle()
    val error by activity.passwordError.collectAsStateWithLifecycle()
    val configured by activity.hasAppPassword.collectAsStateWithLifecycle()
    if (!prompt && !editor) return
    var password by remember(prompt, editor) { mutableStateOf("") }
    var confirmation by remember(prompt, editor) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = activity::cancelPassword,
        title = {
            Text(if (editor) "Your RoseVault password" else "Unlock with RoseVault password")
        },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editor)
                    Text(
                        "Choose 12 to 128 characters. This password stays on this phone. Your phone screen lock remains available for recovery."
                    )
                OutlinedTextField(
                    password,
                    { password = it.take(128) },
                    label = { Text(if (editor) "New password" else "Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (editor)
                    OutlinedTextField(
                        confirmation,
                        { confirmation = it.take(128) },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (editor && confirmation.isNotEmpty() && confirmation != password)
                    Text("Passwords do not match.", color = MaterialTheme.colorScheme.error)
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                if (prompt)
                    TextButton(
                        onClick = {
                            password = ""
                            activity.passwordSystemFallback()
                        },
                        enabled = !busy,
                    ) {
                        Text("Use phone screen lock instead")
                    }
                if (editor && configured)
                    TextButton(onClick = activity::removeAppPassword, enabled = !busy) {
                        Text("Remove RoseVault password")
                    }
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                    !busy &&
                        (if (editor) password.length >= 12 && confirmation == password
                        else password.isNotEmpty()),
                onClick = {
                    val value = password.toCharArray()
                    password = ""
                    confirmation = ""
                    if (editor) activity.saveAppPassword(value) else activity.submitPassword(value)
                },
            ) {
                Text(if (editor) "Save password" else "Unlock vault")
            }
        },
        dismissButton = { TextButton(onClick = activity::cancelPassword) { Text("Cancel") } },
    )
}
