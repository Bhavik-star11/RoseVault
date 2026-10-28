package com.example.data.repository

import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject

internal fun array(items: List<JSONObject>) = JSONArray().apply { items.forEach { put(it) } }

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

internal fun VaultState.encode(): String =
    JSONObject()
        .apply {
            put(
                "conversations",
                array(
                    conversations.map {
                        JSONObject().apply {
                            put("id", it.id)
                            put("title", it.title)
                            put("subtitle", it.subtitle)
                            put("partnerUid", it.partnerUid)
                            put("partnerKey", it.partnerKey)
                            put("privateSpace", it.privateSpace)
                            put("memoryFree", it.memoryFree)
                            put("ephemeral", it.ephemeral)
                            put("memoryFreeCutoff", it.memoryFreeCutoff)
                            put("transport", it.transport)
                        }
                    }
                ),
            )
            put("messages", array(messages.map { it.json() }))
            put(
                "dates",
                array(
                    dates.map {
                        JSONObject().apply {
                            put("id", it.id)
                            put("title", it.title)
                            put("date", it.date)
                            put("time", it.time)
                            put("note", it.note)
                            put("conversationId", it.conversationId)
                        }
                    }
                ),
            )
            put(
                "media",
                array(
                    media.map {
                        JSONObject().apply {
                            put("id", it.id)
                            put("name", it.name)
                            put("mime", it.mime)
                            put("file", it.file)
                            put("bytes", it.bytes)
                        }
                    }
                ),
            )
            put(
                "audit",
                array(
                    audit.take(200).map {
                        JSONObject().apply {
                            put("text", it.text)
                            put("at", it.at)
                        }
                    }
                ),
            )
            put(
                "settings",
                JSONObject().apply {
                    put("secureScreen", settings.secureScreen)
                    put("timeoutSeconds", settings.timeoutSeconds)
                    put("notificationPolicy", settings.notificationPolicy)
                    put("onboardingComplete", settings.onboardingComplete)
                },
            )
        }
        .toString()

internal fun decodeState(text: String): VaultState {
    val j = JSONObject(text)
    val s = j.getJSONObject("settings")
    return VaultState(
        conversations =
            j.getJSONArray("conversations").objects().map {
                VaultConversation(
                    it.getString("id"),
                    it.getString("title"),
                    it.optString("subtitle"),
                    it.optString("partnerUid"),
                    it.optString("partnerKey"),
                    it.optBoolean("privateSpace"),
                    it.optBoolean("memoryFree"),
                    it.optBoolean("ephemeral"),
                    it.optLong("memoryFreeCutoff"),
                    it.optString("transport", "cloud"),
                )
            },
        messages = j.getJSONArray("messages").objects().map { messageFromJson(it) },
        dates =
            j.getJSONArray("dates").objects().map {
                VirtualDateEvent(
                    it.getString("id"),
                    it.getString("title"),
                    it.getString("date"),
                    it.getString("time"),
                    it.optString("note"),
                    it.optString("conversationId"),
                )
            },
        media =
            j.getJSONArray("media").objects().map {
                VaultMedia(
                    it.getString("id"),
                    it.getString("name"),
                    it.getString("mime"),
                    it.getString("file"),
                    it.getLong("bytes"),
                )
            },
        audit =
            j.getJSONArray("audit").objects().map {
                SecurityEvent(it.getString("text"), it.getLong("at"))
            },
        settings =
            VaultSettings(
                s.getBoolean("secureScreen"),
                s.getInt("timeoutSeconds"),
                s.getString("notificationPolicy"),
                s.optBoolean("onboardingComplete"),
            ),
    )
}

internal fun EncryptedMessage.json(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("conversationId", conversationId)
        put("senderId", senderId)
        put("text", text)
        put("createdAt", createdAt)
        put("expiresAt", expiresAt)
        put("attachment", attachment)
        put("mime", mime)
        put("delivery", delivery)
    }

internal fun messageFromJson(j: JSONObject) =
    EncryptedMessage(
        j.getString("id"),
        j.getString("conversationId"),
        j.getString("senderId"),
        j.optString("text"),
        j.getLong("createdAt"),
        j.optLong("expiresAt"),
        j.optString("attachment"),
        j.optString("mime"),
        j.optString("delivery"),
    )
