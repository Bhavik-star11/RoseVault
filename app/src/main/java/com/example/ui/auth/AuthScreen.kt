package com.example.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.ui.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun AuthScreen(activity: MainActivity) {
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(28.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Brand(96)
        Spacer(Modifier.height(32.dp))
        Eyebrow("Your number. Your private world.")
        Spacer(Modifier.height(16.dp))
        Text(
            "Welcome to\nRoseVault.",
            fontFamily = FontFamily.Serif,
            fontSize = 42.sp,
            lineHeight = 49.sp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Sign in with your phone number to find your people and start encrypted conversations.",
            color = MutedRoseGray,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { activity.phoneLogin.show() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Continue with phone number")
        }
        TextButton(
            onClick = {
                activity.repository.settings(
                    activity.repository.state.value.settings.copy(onboardingComplete = true)
                )
            }
        ) {
            Text("Continue offline")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Local notes and Nearby chats work without an account. Your vault stays protected by Android identity verification.",
            fontSize = 12.sp,
            color = MutedRoseGray,
            lineHeight = 19.sp,
        )
    }
}

@Composable
fun PhoneLoginDialog(activity: MainActivity) {
    val model = activity.phoneLogin
    val state by model.state.collectAsStateWithLifecycle()
    var number by remember(state.visible) { mutableStateOf(state.number) }
    var code by remember(state.stage) { mutableStateOf("") }
    var discoverable by remember(state.visible) { mutableStateOf(state.discoverable) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.visible) {
        while (state.visible) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    if (!state.visible) return
    val remaining = ((state.resendAt - now + 999) / 1000).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = model::close,
        title = {
            Text(if (state.stage == "verified") "Phone connected" else "Sign in with your number")
        },
        text = {
            Column(
                Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (state.stage) {
                    "number",
                    "sending" -> {
                        OutlinedTextField(
                            number,
                            { number = it.take(40) },
                            label = { Text("Phone number with country code") },
                            placeholder = { Text("+12025550123") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            enabled = !state.busy,
                            singleLine = true,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Let people who know my number find me",
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                            )
                            Switch(discoverable, { discoverable = it }, enabled = !state.busy)
                        }
                        Text(
                            "Your project’s SMS provider sends the verification code. Standard SMS rates may apply. Supabase and the SMS provider process your number for authentication. Your number is shared with people you message.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MutedRoseGray,
                        )
                    }
                    "code",
                    "verifying" -> {
                        Text("Enter the code sent to ${state.number}")
                        OutlinedTextField(
                            code,
                            { code = it.filter { c -> c in '0'..'9' }.take(6) },
                            label = { Text("Six-digit SMS code") },
                            keyboardOptions =
                                KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !state.busy,
                            singleLine = true,
                        )
                        TextButton(
                            onClick = { model.send(activity, state.number, discoverable, true) },
                            enabled = !state.busy && remaining == 0L,
                        ) {
                            Text(
                                if (remaining > 0) "Resend code in ${remaining}s" else "Resend code"
                            )
                        }
                    }
                    "verified" ->
                        Text(
                            "Your account is connected. Biometric or device verification still unlocks this phone’s vault."
                        )
                }
                if (state.busy)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (state.stage == "sending") "Sending verification code…"
                            else "Verifying…"
                        )
                    }
            }
        },
        confirmButton = {
            when (state.stage) {
                "number",
                "sending" ->
                    TextButton(
                        onClick = { model.send(activity, number, discoverable) },
                        enabled = !state.busy && number.isNotBlank(),
                    ) {
                        Text("Send verification code")
                    }
                "code",
                "verifying" ->
                    TextButton(
                        onClick = { model.verify(activity, code) },
                        enabled = !state.busy && code.length == 6,
                    ) {
                        Text("Verify & sign in")
                    }
                else -> TextButton(onClick = model::close) { Text("Done") }
            }
        },
        dismissButton = { TextButton(onClick = model::close) { Text("Cancel") } },
    )
}
