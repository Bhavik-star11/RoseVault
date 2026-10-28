package com.example.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

class BiometricAuthManager(private val activity: FragmentActivity) {
    var inProgress = false
        private set

    fun authenticate(reason: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val allowed =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (
            BiometricManager.from(activity).canAuthenticate(allowed) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            onError("Set up a screen lock or strong biometric in Android Settings first.")
            return
        }
        inProgress = true
        val prompt =
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        inProgress = false
                        onSuccess()
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        inProgress = false
                        (activity as? com.example.MainActivity)?.lock()
                        onError(message.toString())
                    }
                },
            )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("System Security Vault")
                .setSubtitle(reason)
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }

    fun cipher(
        reason: String,
        crypto: BiometricPrompt.CryptoObject,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (
            BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            onError(
                "Enroll a strong fingerprint or biometric in Android Settings to run this check. Your device PIN can still unlock the vault."
            )
            return
        }
        inProgress = true
        val prompt =
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        inProgress = false
                        runCatching {
                                result.cryptoObject?.cipher?.let(onSuccess)
                                    ?: onError("Hardware cipher unavailable")
                            }
                            .onFailure {
                                onError(
                                    it.localizedMessage ?: "Hardware cipher verification failed"
                                )
                            }
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        inProgress = false
                        (activity as? com.example.MainActivity)?.lock()
                        onError(message.toString())
                    }
                },
            )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Hardware key verification")
                .setSubtitle(reason)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .build(),
            crypto,
        )
    }
}
