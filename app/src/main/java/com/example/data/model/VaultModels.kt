package com.example.data.model

import java.util.UUID

fun newId() = UUID.randomUUID().toString()

data class VaultConversation(
    val id: String = newId(),
    val title: String,
    val subtitle: String = "Your private corner",
    val partnerUid: String = "",
    val partnerKey: String = "",
    val privateSpace: Boolean = false,
    val memoryFree: Boolean = false,
    val ephemeral: Boolean = false,
    val memoryFreeCutoff: Long = 0,
    val transport: String = "cloud",
)

data class EncryptedMessage(
    val id: String = newId(),
    val conversationId: String,
    val senderId: String = "local",
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0,
    val attachment: String = "",
    val mime: String = "",
    val delivery: String = "Local only",
    val kind: String = "text",
    val reference: String = "",
)

data class VirtualDateEvent(
    val id: String = newId(),
    val title: String,
    val date: String,
    val time: String,
    val note: String = "",
    val conversationId: String = "",
)

data class SecurityEvent(val text: String, val at: Long = System.currentTimeMillis())

data class VaultMedia(
    val id: String = newId(),
    val name: String,
    val mime: String,
    val file: String,
    val bytes: Long,
)

data class TrustedDevice(
    val id: String,
    val name: String,
    val fingerprint: String,
    val revoked: Boolean = false,
)

data class VaultSettings(
    val secureScreen: Boolean = true,
    val timeoutSeconds: Int = 60,
    val notificationPolicy: String = "PRIVATE",
    val onboardingComplete: Boolean = false,
)

data class VaultState(
    val conversations: List<VaultConversation> = emptyList(),
    val messages: List<EncryptedMessage> = emptyList(),
    val dates: List<VirtualDateEvent> = emptyList(),
    val media: List<VaultMedia> = emptyList(),
    val audit: List<SecurityEvent> = emptyList(),
    val settings: VaultSettings = VaultSettings(),
    val privateIntentions: Map<String, String> = emptyMap(),
)

enum class BiometricUnlockScope {
    APP_COLD_START_OR_RESUME,
    NOTIFICATION_VAULT_DEEP_LINK,
    PRIVATE_RELATIONSHIP_SPACE,
    MEDIA_OR_REEL_VAULT_UNLOCK,
    TRUSTED_DEVICE_REAUTHENTICATION,
}

data class VaultNotification(val id: String, val conversationId: String, val at: Long)
