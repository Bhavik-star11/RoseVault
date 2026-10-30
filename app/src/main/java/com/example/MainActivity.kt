package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.calls.VaultCallEngine
import com.example.data.model.VaultConversation
import com.example.security.BiometricAuthManager
import com.example.ui.RoseVaultRoot
import com.example.ui.theme.RoseVaultTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    val phoneLogin: com.example.ui.auth.PhoneLoginViewModel by viewModels()
    val repository
        get() = (application as RoseVaultApp).repository

    val unlocked = MutableStateFlow(false)
    val accountConnected = MutableStateFlow(false)
    val passwordPrompt = MutableStateFlow(false)
    val passwordEditor = MutableStateFlow(false)
    val passwordBusy = MutableStateFlow(false)
    val passwordError = MutableStateFlow<String?>(null)
    val hasAppPassword = MutableStateFlow(false)
    private val appPassword by lazy { com.example.security.AppPasswordStore(this) }
    private var passwordAction: (() -> Unit)? = null
    private var passwordGeneration = 0

    val pendingConversation = MutableStateFlow<String?>(null)
    lateinit var biometrics: BiometricAuthManager
    lateinit var nearby: com.example.nearby.NearbyChatEngine
    lateinit var calls: VaultCallEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timeout: Job? = null

    private var attachmentConversation: String? = null
    private var pendingCall: Pair<VaultConversation, Boolean>? = null
    private var acceptingCall = false
    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val conversation = attachmentConversation
            attachmentConversation = null
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                verified("Add private attachment") {
                    repository.launch {
                        try {
                            val media = repository.importMedia(uri)
                            if (conversation != null) {
                                repository.send(conversation, "", media)
                                pendingConversation.value = conversation
                            }
                        } finally {
                            runCatching {
                                contentResolver.releasePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                        }
                    }
                }
            }
        }
    val matrix by lazy { com.example.matrix.MatrixVault(applicationContext, repository.crypto) }
    val roseAi by lazy { com.example.ai.RoseAi(applicationContext, repository.crypto) }

    private var matrixAttachmentRoom: String? = null
    private val matrixPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val roomId = matrixAttachmentRoom
            matrixAttachmentRoom = null
            if (uri != null && roomId != null)
                verified("Share encrypted Matrix attachment") {
                    matrix.run {
                        matrix.restore()
                        matrix.openRoom(roomId)
                        val bytes =
                            withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri).use { input ->
                                    requireNotNull(input)
                                    val out = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        require(out.size() + n <= 20 * 1024 * 1024) {
                                            "Keep files under 20 MB."
                                        }
                                        out.write(buffer, 0, n)
                                    }
                                    out.toByteArray()
                                }
                            }
                        matrix.sendFile(
                            bytes,
                            "Private attachment",
                            contentResolver.getType(uri) ?: "application/octet-stream",
                        )
                    }
                }
        }

    fun pickMatrixMedia(roomId: String) {
        matrixAttachmentRoom = roomId
        matrixPicker.launch(arrayOf("*/*"))
    }

    fun openMatrixMedia(source: org.matrix.rustcomponents.sdk.MediaSource, mime: String) {
        matrix.run {
            val bytes = matrix.download(source)
            val file =
                java.io.File(cacheDir, "shared/matrix-" + java.util.UUID.randomUUID()).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            bytes.fill(0)
            withContext(Dispatchers.Main) {
                if (unlocked.value) {
                    val uri =
                        androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity,
                            "$packageName.files",
                            file,
                        )
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } else file.delete()
            }
        }
    }

    private val callPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val request = pendingCall
            pendingCall = null
            if (
                result.isNotEmpty() && result.values.all { it } && unlocked.value && request != null
            ) {
                runCatching {
                        if (acceptingCall) calls.accept()
                        else calls.start(request.first, request.second)
                    }
                    .onFailure { repository.error.value = it.localizedMessage }
            } else
                repository.error.value =
                    "Call permissions were not granted. Allow them in Android App settings and try again."
            acceptingCall = false
        }

    fun pickMedia(conversationId: String? = null) {
        attachmentConversation = conversationId
        picker.launch(arrayOf("*/*"))
    }

    fun placeCall(c: VaultConversation, video: Boolean, accept: Boolean = false) {
        if (!repository.online.value) {
            repository.error.value =
                "Calls need internet. Nearby chats can send messages without internet."
            return
        }
        if (c.transport != "cloud") {
            repository.error.value =
                "Nearby mode supports messages and attachments. Calls need a cloud-paired contact."
            return
        }
        val required =
            if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (
            required.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        )
            runCatching { if (accept) calls.accept() else calls.start(c, video) }
                .onFailure { repository.error.value = it.localizedMessage }
        else {
            pendingCall = c to video
            acceptingCall = accept
            callPermissions.launch(required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        accountConnected.value = repository.uid != "local"
        biometrics = BiometricAuthManager(this)
        hasAppPassword.value = appPassword.configured
        calls = VaultCallEngine(this, repository)
        nearby = com.example.nearby.NearbyChatEngine(this, repository)
        phoneLogin.onConnected = {
            accountConnected.value = repository.uid != "local"
            if (repository.loaded) {
                repository.settings(repository.state.value.settings.copy(onboardingComplete = true))
                repository.connect()
            }
        }
        phoneLogin.attach(this)
        scope.launch {
            repository.cloudApi.userId.collect { accountConnected.value = it != "local" }
        }
        attachmentConversation = savedInstanceState?.getString("attachmentConversation")
        pendingConversation.value = intent.getStringExtra("extra_conversation_id")
        setContent { RoseVaultTheme { RoseVaultRoot(this) } }
    }

    override fun onStart() {
        super.onStart()
        phoneLogin.attach(this)
    }

    fun unlock() {
        biometrics.authenticate(
            "Verify your identity to continue",
            {
                runCatching {
                        repository.clearCache()
                        repository.open()
                        unlocked.value = true
                        touch()
                        applyShield()
                    }
                    .onFailure {
                        repository.error.value = "Could not open vault: ${it.localizedMessage}"
                    }
            },
            { repository.error.value = it },
        )
    }

    private fun openAfterVerification(action: () -> Unit = {}) {
        runCatching {
                if (!repository.loaded) repository.open()
                unlocked.value = true
                touch()
                applyShield()
                action()
            }
            .onFailure {
                repository.error.value = it.localizedMessage ?: "Could not open the vault"
            }
    }

    fun verified(reason: String, action: () -> Unit) {
        if (hasAppPassword.value) {
            passwordAction = { openAfterVerification(action) }
            passwordError.value = null
            passwordPrompt.value = true
        } else
            biometrics.authenticate(
                reason,
                { openAfterVerification(action) },
                { repository.error.value = it },
            )
    }

    fun unlockWithPassword() {
        if (!hasAppPassword.value) return
        passwordAction = { openAfterVerification() }
        passwordError.value = null
        passwordPrompt.value = true
    }

    fun cancelPassword() {
        passwordGeneration++
        passwordAction = null
        passwordPrompt.value = false
        passwordEditor.value = false
        passwordError.value = null
        passwordBusy.value = false
    }

    fun passwordSystemFallback() {
        val action = passwordAction ?: return
        cancelPassword()
        biometrics.authenticate(
            "Verify your identity to continue",
            action,
            { repository.error.value = it },
        )
    }

    fun submitPassword(value: CharArray) {
        if (!passwordPrompt.value || passwordBusy.value) {
            value.fill('\u0000')
            return
        }
        val generation = passwordGeneration
        passwordBusy.value = true
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        runCatching { appPassword.verify(value) }
                    } finally {
                        value.fill('\u0000')
                    }
                }
            if (generation != passwordGeneration || !passwordPrompt.value) return@launch
            passwordBusy.value = false
            if (result.getOrNull() == true) {
                val action = passwordAction
                cancelPassword()
                action?.invoke()
            } else
                passwordError.value =
                    result.exceptionOrNull()?.localizedMessage ?: "Incorrect password. Try again."
        }
    }

    fun editAppPassword() {
        if (!unlocked.value) return
        biometrics.authenticate(
            "Confirm phone ownership to manage your RoseVault password",
            {
                if (!repository.loaded) repository.open()
                unlocked.value = true
                touch()
                passwordError.value = null
                passwordEditor.value = true
            },
            { repository.error.value = it },
        )
    }

    fun saveAppPassword(value: CharArray) {
        if (!passwordEditor.value || passwordBusy.value) {
            value.fill('\u0000')
            return
        }
        val generation = passwordGeneration
        passwordBusy.value = true
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        runCatching { appPassword.prepare(value) }
                    } finally {
                        value.fill('\u0000')
                    }
                }
            if (generation != passwordGeneration || !passwordEditor.value) return@launch
            passwordBusy.value = false
            if (result.isSuccess) {
                try {
                    appPassword.commit(result.getOrThrow())
                } catch (e: Exception) {
                    passwordError.value = "Could not save password. Try again."
                    return@launch
                }
                hasAppPassword.value = true
                cancelPassword()
                repository.error.value =
                    "RoseVault password saved. Your phone screen lock remains available."
            } else
                passwordError.value =
                    result.exceptionOrNull()?.localizedMessage ?: "Could not save password"
        }
    }

    fun removeAppPassword() {
        if (!passwordEditor.value || passwordBusy.value) return
        runCatching { appPassword.remove() }
            .onSuccess {
                hasAppPassword.value = false
                cancelPassword()
                repository.error.value = "RoseVault password removed. Use your phone screen lock."
            }
            .onFailure { passwordError.value = "Could not remove password. Try again." }
    }

    fun closeMatrix() {
        scope.launch { matrix.pause() }
    }

    fun lock() {
        scope.launch { matrix.pause() }
        cancelPassword()
        unlocked.value = false
        timeout?.cancel()
        calls.stopWatching()
        nearby.stop()
        repository.close()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun applyShield() {
        if (!unlocked.value || repository.state.value.settings.secureScreen)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun touch() {
        timeout?.cancel()
        if (unlocked.value) {
            val seconds = repository.state.value.settings.timeoutSeconds
            if (seconds > 0)
                timeout =
                    scope.launch {
                        delay(seconds * 1000L)
                        lock()
                    }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("attachmentConversation", attachmentConversation)
        super.onSaveInstanceState(outState)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        touch()
    }

    override fun onStop() {
        super.onStop()
        if (!biometrics.inProgress) {
            cancelPassword()
            if (unlocked.value) lock()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lock()
        pendingConversation.value = intent.getStringExtra("extra_conversation_id")
    }

    override fun onDestroy() {
        calls.stopWatching()
        nearby.stop()
        repository.close()
        calls.egl.release()
        scope.cancel()
        super.onDestroy()
    }
}
