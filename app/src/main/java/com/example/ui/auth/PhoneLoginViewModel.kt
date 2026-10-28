package com.example.ui.auth

import android.app.Activity
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.cloud.SupabaseCloud
import com.example.data.repository.deviceId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

fun normalizePhoneNumber(value: String): String {
    require(value.length <= 40 && value.all { it in "+0123456789 ()-" }) {
        "Enter a phone number with its country code."
    }
    val result = value.filterNot { it in " ()-" }
    require(Regex("[+][1-9][0-9]{6,14}").matches(result)) {
        "Include + and your country code, for example +12025550123."
    }
    return result
}

fun validSmsCode(value: String) = Regex("[0-9]{6}").matches(value)

data class PhoneLoginState(
    val visible: Boolean = false,
    val number: String = "",
    val stage: String = "number",
    val busy: Boolean = false,
    val error: String? = null,
    val resendAt: Long = 0,
    val phoneNumber: String? = null,
    val discoverable: Boolean = false,
    val registered: Boolean = false,
)

class PhoneLoginViewModel : ViewModel() {
    val state = MutableStateFlow(PhoneLoginState())
    var onConnected: (() -> Unit)? = null
    private var api: SupabaseCloud? = null
    private var operation: Job? = null
    private var desiredDiscovery = false

    fun attach(activity: Activity) {
        api = (activity as MainActivity).repository.cloudApi
        state.value = state.value.copy(phoneNumber = api?.phone?.value)
    }

    fun show() {
        state.value =
            state.value.copy(
                visible = true,
                number = state.value.phoneNumber ?: state.value.number,
                error = null,
                stage = if (state.value.stage == "verified") "number" else state.value.stage,
            )
    }

    fun close() {
        operation?.cancel()
        state.value =
            state.value.copy(visible = false, busy = false, stage = "number", error = null)
    }

    fun send(activity: Activity, number: String, discoverable: Boolean, resend: Boolean = false) {
        if (state.value.busy) return
        val normalized =
            runCatching { normalizePhoneNumber(number) }
                .getOrElse {
                    state.value = state.value.copy(error = it.localizedMessage)
                    return
                }
        if (api?.configured != true) {
            state.value =
                state.value.copy(
                    error =
                        "Phone sign-in needs a Supabase project and SMS provider. You can continue offline."
                )
            return
        }
        if (resend && SystemClock.elapsedRealtime() < state.value.resendAt) return
        if (api?.phone?.value != null && api?.phone?.value != normalized) {
            state.value =
                state.value.copy(
                    error =
                        "This vault is linked to another number. Account switching is not supported."
                )
            return
        }
        desiredDiscovery = discoverable
        state.value =
            state.value.copy(number = normalized, busy = true, error = null, stage = "sending")
        operation =
            viewModelScope.launch {
                try {
                    api!!.sendOtp(normalized)
                    state.value =
                        state.value.copy(
                            stage = "code",
                            busy = false,
                            resendAt = SystemClock.elapsedRealtime() + 60_000,
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.value =
                        state.value.copy(
                            stage = "number",
                            busy = false,
                            error = e.localizedMessage ?: "Could not send a verification code.",
                        )
                }
            }
    }

    fun verify(activity: Activity, code: String) {
        if (state.value.busy) return
        if (!validSmsCode(code)) {
            state.value = state.value.copy(error = "Enter the six-digit code.")
            return
        }
        state.value = state.value.copy(stage = "verifying", busy = true, error = null)
        operation =
            viewModelScope.launch {
                try {
                    api!!.verifyOtp(state.value.number, code)
                    state.value =
                        state.value.copy(
                            phoneNumber = api!!.phone.value,
                            stage = "verified",
                            busy = false,
                        )
                    onConnected?.invoke()
                    register(activity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.value =
                        state.value.copy(
                            stage = "code",
                            busy = false,
                            error =
                                e.localizedMessage ?: "The verification code could not be checked.",
                        )
                }
            }
    }

    private suspend fun register(activity: Activity) {
        val repo = (activity as MainActivity).repository
        try {
            api!!.phoneAction(
                "register",
                JSONObject()
                    .put("key", repo.crypto.publicIdentity())
                    .put("deviceId", deviceId(activity))
                    .put("discoverable", desiredDiscovery),
            )
            state.value =
                state.value.copy(registered = true, discoverable = desiredDiscovery, error = null)
            if (repo.loaded) repo.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value =
                state.value.copy(
                    registered = false,
                    error = "Phone verified; device setup needs attention. ${e.localizedMessage}",
                )
        }
    }

    fun refresh(activity: Activity) {
        attach(activity)
        if (api?.configured != true || api?.phone?.value == null) return
        viewModelScope.launch {
            try {
                val data =
                    api!!.phoneAction(
                        "profile",
                        JSONObject()
                            .put(
                                "key",
                                (activity as MainActivity).repository.crypto.publicIdentity(),
                            ),
                    )
                state.value =
                    state.value.copy(
                        phoneNumber = data.getString("phoneNumber"),
                        discoverable = data.optBoolean("discoverable"),
                        registered = data.optBoolean("registered"),
                        error = null,
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.localizedMessage)
            }
        }
    }

    fun discovery(enabled: Boolean) {
        val cloud = api ?: return
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                cloud.phoneAction(
                    "discovery",
                    JSONObject()
                        .put("key", com.example.security.CryptoEngine().publicIdentity())
                        .put("discoverable", enabled),
                )
                state.value = state.value.copy(discoverable = enabled, busy = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value.copy(busy = false, error = e.localizedMessage)
            }
        }
    }

    fun registerAgain(activity: Activity) {
        if (state.value.busy) return
        desiredDiscovery = state.value.discoverable
        state.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                register(activity)
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }
    }
}
